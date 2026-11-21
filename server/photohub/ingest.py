"""Shared ingestion logic: analyse files, then register them (F2 dedupe + F3 placement)."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import xmp
from .config import Settings
from .db import (Album, AlbumItem, ImportItem, ImportJob, Media, User, grant_access, set_location,
                 touch_media)
from .fileops import copy_verified, library_dir, plan_destination
from .hashing import HASH_VERSION, content_hash, file_sha256, hamming, kind_of, phash
from .metadata import Meta, file_mtime_utc, read_many


@dataclass
class Candidate:
    path: Path
    kind: str
    file_hash: str = ""
    content_hash: str = ""
    phash: str | None = None
    bytes: int = 0
    meta: Meta = field(default_factory=Meta)
    albums: set[str] = field(default_factory=set)
    pair_key: str | None = None
    error: str | None = None


def collect_media(root: Path) -> list[Path]:
    files = []
    for p in sorted(root.rglob("*")):
        if p.is_file() and not p.name.startswith(".") and kind_of(p) and "@eaDir" not in p.parts:
            files.append(p)
    return files


def analyse(paths: list[Path], settings: Settings) -> list[Candidate]:
    cands = [Candidate(path=p, kind=kind_of(p) or "photo") for p in paths]

    def work(c: Candidate) -> Candidate:
        try:
            c.bytes = c.path.stat().st_size
            c.file_hash = file_sha256(c.path)
            c.content_hash = content_hash(c.path, c.kind, c.file_hash, settings.ffmpeg_bin)
            if c.kind == "photo":
                c.phash = phash(c.path)
        except Exception as e:  # keep going; recorded as error
            c.error = f"{type(e).__name__}: {e}"
        return c

    with ThreadPoolExecutor(max_workers=max(1, settings.workers)) as ex:
        cands = list(ex.map(work, cands))
    metas = read_many([c.path for c in cands if not c.error],
                      {c.path: c.kind for c in cands}, exiftool=settings.exiftool_bin,
                      default_tz=settings.default_tz_minutes)
    for c in cands:
        if c.path in metas:
            c.meta = metas[c.path]
    _assign_pairs(cands)
    return cands


def _assign_pairs(cands: list[Candidate]) -> None:
    """Live Photo (HEIC+MOV) / motion photo halves: same pair id, or same folder+stem."""
    by_id: dict[str, list[Candidate]] = {}
    for c in cands:
        if c.meta.pair_id:
            by_id.setdefault("id:" + c.meta.pair_id, []).append(c)
    by_stem: dict[tuple[Path, str], list[Candidate]] = {}
    for c in cands:
        by_stem.setdefault((c.path.parent, c.path.stem.lower()), []).append(c)
    for key, group in list(by_id.items()) + [(f"stem:{k[1]}", g) for k, g in by_stem.items()]:
        kinds = {c.kind for c in group}
        if len(group) >= 2 and kinds == {"photo", "video"}:
            for c in group:
                if c.pair_key is None:
                    c.pair_key = key if key.startswith("id:") else f"{key}|{group[0].file_hash[:12]}"


def _find_similar(s: Session, c: Candidate, threshold: int) -> Media | None:
    """D4: a storage-saver copy on Google has different pixels. Same capture time (±2 s)
    and a near pHash (photos) / near duration (videos) => same moment."""
    if c.meta.taken_at is None:
        return None
    t = c.meta.taken_at
    rows = s.execute(select(Media).where(Media.kind == c.kind, Media.status == "active",
                                         Media.taken_at.between(t - timedelta(seconds=2),
                                                                t + timedelta(seconds=2)))).scalars()
    for m in rows:
        if c.kind == "photo" and c.phash and m.phash and hamming(c.phash, m.phash) <= threshold:
            if c.bytes <= m.bytes:
                return m
        if c.kind == "video" and m.duration and c.meta.duration and abs(m.duration - c.meta.duration) <= 1.0:
            if c.bytes <= m.bytes:
                return m
    return None


def _album(s: Session, name: str, origin: str, owner_id: int | None) -> Album:
    owner_cond = Album.owner_id.is_(None) if owner_id is None else Album.owner_id == owner_id
    a = s.execute(select(Album).where(Album.name == name, Album.origin == origin, owner_cond)).scalar_one_or_none()
    if a is None:
        a = Album(name=name, origin=origin, owner_id=owner_id)
        s.add(a)
        s.flush()
    return a


def _add_albums(s: Session, m: Media, names: set[str], origin: str, owner_id: int | None = None) -> bool:
    """Google albums belong to the person whose Takeout listed them (owner_id)."""
    changed = False
    for n in sorted(names):
        a = _album(s, n, origin, owner_id)
        if s.get(AlbumItem, (a.id, m.id)) is None:
            s.add(AlbumItem(album_id=a.id, media_id=m.id))
            changed = True
    s.flush()
    return changed


def album_names(s: Session, media_id: int, owner_id: int | None = None) -> list[str]:
    """Album names that may be written next to the file: shared albums and the file owner's own
    (another family member's Google album names stay out of someone else's personal space)."""
    from sqlalchemy import or_
    cond = Album.owner_id.is_(None) if owner_id is None else or_(Album.owner_id.is_(None), Album.owner_id == owner_id)
    return list(s.execute(select(Album.name).join(AlbumItem, AlbumItem.album_id == Album.id)
                          .where(AlbumItem.media_id == media_id, cond)).scalars())


def write_sidecar(s: Session, m: Media) -> None:
    p = Path(m.nas_path)
    if p.exists():
        xmp.write(p, content_hash=m.content_hash, albums=album_names(s, m.id, m.owner_id),
                  description=m.description, taken_at=m.taken_at, tz_offset=m.tz_offset)


def new_media(c: Candidate, nas_path: Path, fallback_time, owner_id: int | None = None) -> Media:  # noqa: ANN001
    md = c.meta
    return Media(owner_id=owner_id, content_hash=c.content_hash, hash_version=HASH_VERSION, file_hash=c.file_hash,
                 phash=c.phash, kind=c.kind, mime=md.mime,
                 taken_at=md.taken_at or fallback_time, tz_offset=md.tz_offset,
                 lat=md.lat, lon=md.lon, width=md.width, height=md.height, duration=md.duration,
                 bytes=c.bytes, nas_path=str(nas_path), description=md.description,
                 pair_key=c.pair_key)


def merge_into(s: Session, m: Media, c: Candidate) -> bool:
    """Exact duplicate: keep the library copy, add information only it lacks (F2)."""
    changed = False
    if m.lat is None and c.meta.lat is not None:
        m.lat, m.lon = c.meta.lat, c.meta.lon
        changed = True
    if not m.description and c.meta.description:
        m.description = c.meta.description
        changed = True
    if m.pair_key is None and c.pair_key:
        m.pair_key = c.pair_key
        changed = True
    return changed


def takeout_destination(settings: Settings, user: User | None) -> tuple[Path, int | None]:
    """Where a person's Takeout photos go: their personal space (default) or the shared space."""
    if user is not None and user.takeout_target == "personal" and user.personal_root:
        return Path(user.personal_root) / "PhotoHub", user.id
    return settings.library, None


def ingest_takeout_candidate(s: Session, settings: Settings, job: ImportJob, c: Candidate,
                             rel: str, apply: bool, aside_dir: Path, user: User | None = None) -> ImportItem:
    """One Takeout file of `user`. Returns the ImportItem (already added to the session)."""
    uid = user.id if user is not None else 0
    prev = s.execute(select(ImportItem).where(ImportItem.job_id == job.id,
                                              ImportItem.original_path == rel)).scalar_one_or_none()
    if prev is not None and prev.result != "error":
        return prev  # resumed run: already handled
    item = prev or ImportItem(job_id=job.id, original_path=rel, result="error")
    if prev is None:
        s.add(item)

    if c.error:
        item.result, item.message = "error", c.error
        return item

    existing = s.execute(select(Media).where(Media.content_hash == c.content_hash)).scalar_one_or_none()
    if existing is not None:
        changed = merge_into(s, existing, c)
        changed |= _add_albums(s, existing, c.albums, "google", user.id if user else None)
        # the same photo is in this person's Google too -> they may see the NAS copy
        changed |= grant_access(s, existing, user.id if user else None)
        set_location(s, existing.id, "google", "present", match="exact", source_ref=rel, user_id=uid)
        if changed:
            touch_media(s, existing)
            if apply:
                write_sidecar(s, existing)
        item.media_id = existing.id
        item.result = "merged" if changed else "duplicate"
        return item

    similar = _find_similar(s, c, settings.similar_threshold)
    if similar is not None:
        # Google's compressed copy of something we already have in full quality.
        if apply:
            dst = aside_dir / "google-similar" / (user.name if user else "_") / rel
            if not dst.exists():
                copy_verified(c.path, dst, c.file_hash)
        _add_albums(s, similar, c.albums, "google", user.id if user else None)
        grant_access(s, similar, user.id if user else None)
        set_location(s, similar.id, "google", "present", match="similar", source_ref=rel, user_id=uid)
        touch_media(s, similar)
        if apply:
            write_sidecar(s, similar)
        item.media_id = similar.id
        item.result = "merged"
        item.message = "similar: storage-saver copy of existing item"
        return item

    fallback = file_mtime_utc(c.path)
    root, owner = takeout_destination(settings, user)
    dest_dir = library_dir(root, c.meta.taken_at, c.meta.tz_offset, fallback)
    plan = plan_destination(dest_dir, c.path.name, c.file_hash)
    if apply and not plan.already_present:
        copy_verified(c.path, plan.path, c.file_hash)
    m = new_media(c, plan.path, fallback, owner)
    s.add(m)
    s.flush()
    touch_media(s, m)
    set_location(s, m.id, "nas", "present", source_ref=str(plan.path))
    set_location(s, m.id, "google", "present", source_ref=rel, user_id=uid)
    _add_albums(s, m, c.albums, "google", user.id if user else None)
    if apply:
        write_sidecar(s, m)
    item.media_id = m.id
    item.result = "new"
    item.message = str(plan.path)
    return item
