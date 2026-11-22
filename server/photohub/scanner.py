"""P2 / F3: index photos already in the library (including Synology Photos mobile backups)
and report duplicates. Never moves or edits library files.

Resumable: each file's (size, mtime) is stored in scan_state; unchanged files are skipped,
and progress is committed every batch, so a stopped scan continues where it left off.
"""
from __future__ import annotations

import threading

import os
from dataclasses import dataclass, field
from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import Database, ImportItem, ImportJob, Media, ScanState, grant_access, kv_set, log_event, set_location, touch_media, utcnow
from .hashing import kind_of
from . import xmp
from .ingest import _add_albums, analyse, merge_into, new_media
from .xmp import sidecar_path
from .metadata import file_mtime_utc

SKIP_DIRS = {"@eaDir", "#recycle", "#snapshot", ".photohub", "@tmp"}


@dataclass
class ScanReport:
    dry_run: bool
    scanned: int = 0
    skipped_unchanged: int = 0
    new: int = 0
    duplicates: list[tuple[str, str]] = field(default_factory=list)  # (dup path, kept path)
    resolved: list[tuple[str, str]] = field(default_factory=list)    # (PhotoHub copy trashed, kept)
    errors: list[str] = field(default_factory=list)
    missing: int = 0

    def summary(self) -> str:
        mode = "DRY-RUN" if self.dry_run else "APPLIED"
        return (f"[{mode}] scanned={self.scanned} unchanged={self.skipped_unchanged} new={self.new} "
                f"duplicates={len(self.duplicates)} resolved={len(self.resolved)} missing={self.missing} "
                f"errors={len(self.errors)}")


def walk(root: Path):  # noqa: ANN201
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS and not d.startswith("."))
        for fn in sorted(filenames):
            if fn.startswith(".") or fn.endswith(".xmp") or fn.upper().startswith(("SYNOFILE_THUMB", "SYNOPHOTO_THUMB")):
                continue
            p = Path(dirpath) / fn
            if kind_of(p):
                yield p


_SCAN_LOCK = threading.Lock()


def scan(db: Database, settings: Settings, roots: list, *, apply: bool,
         batch: int = 200, limit: int | None = None, move_files: bool | None = None) -> ScanReport:
    """See _scan. apply=True records what was found in the index (reading files only);
    move_files (default: apply) also lets it move PhotoHub's own duplicate copies to trash.
    Only one scan runs at a time."""
    with _SCAN_LOCK:
        return _scan(db, settings, roots, apply=apply, batch=batch, limit=limit,
                     move_files=apply if move_files is None else move_files)


def _scan(db: Database, settings: Settings, roots: list, *, apply: bool,
          batch: int, limit: int | None, move_files: bool) -> ScanReport:
    """roots: folders, or (folder, owner user id | None) pairs. A personal space's owner is the
    only one who sees its photos (plus anyone who has the same photo, see media_access)."""
    roots = [r if isinstance(r, tuple) else (Path(r), None) for r in roots]
    rep = ScanReport(dry_run=not apply)
    s = db.new_session()
    try:
        job = ImportJob(source="phone_scan", archive_name=", ".join(str(r) for r, _ in roots))
        s.add(job)
        s.flush()
        pending: list[Path] = []
        owner_of: dict[Path, int | None] = {}

        def flush_batch() -> None:
            cands = analyse(pending, settings)
            for c in cands:
                if not c.path.exists():
                    continue  # moved away earlier in this batch (e.g. PhotoHub's own duplicate)
                owner = owner_of.get(c.path)
                rep.scanned += 1
                key = str(c.path)
                st = c.path.stat()
                if c.error:
                    rep.errors.append(f"{key}: {c.error}")
                    s.add(ImportItem(job_id=job.id, original_path=key, result="error", message=c.error))
                    continue
                m = s.execute(select(Media).where(Media.content_hash == c.content_hash)).scalar_one_or_none()
                if m is None:
                    m = new_media(c, c.path, file_mtime_utc(c.path), owner)
                    s.add(m)
                    s.flush()
                    _restore_from_xmp(s, m, c.path)
                    touch_media(s, m)
                    set_location(s, m.id, "nas", "present", source_ref=key)
                    rep.new += 1
                    result = "new"
                elif m.nas_path != key and Path(m.nas_path).exists():
                    changed = merge_into(s, m, c)
                    changed |= grant_access(s, m, owner)  # they hold a copy, so they may see it
                    if m.placed and owner == m.owner_id:
                        # The same photo now exists where someone else (e.g. Synology Photos' backup)
                        # put it: keep theirs, move PhotoHub's own copy to trash (never deleted).
                        rep.resolved.append((m.nas_path, key))
                        if apply and move_files:
                            _trash_own_copy(s, settings, m, Path(key))
                        changed = True
                    else:
                        rep.duplicates.append((key, m.nas_path))
                    if changed:
                        touch_media(s, m)
                    result = "duplicate"
                else:
                    if m.nas_path != key:  # the indexed file moved; follow it
                        m.nas_path = key
                        touch_media(s, m)
                    set_location(s, m.id, "nas", "present", source_ref=key)
                    result = "merged"
                s.add(ImportItem(job_id=job.id, original_path=key, media_id=m.id, result=result,
                                 message=None if result != "duplicate" else f"same as {m.nas_path}"))
                ss = s.get(ScanState, key) or ScanState(path=key, size=0, mtime=0)
                ss.size, ss.mtime, ss.content_hash, ss.media_id, ss.seen_at = (
                    st.st_size, st.st_mtime, c.content_hash, m.id, utcnow())
                s.merge(ss)
            pending.clear()
            if apply:
                kv_set(s, "scan_progress", f"{rep.scanned}")
                # counts so far, so the import log shows progress instead of 0 until the end
                job.new_count, job.dup_count, job.error_count = rep.new, len(rep.duplicates), len(rep.errors)
                s.commit()

        seen = 0
        for root, owner in roots:
            for p in walk(root):
                if limit is not None and seen >= limit:
                    break
                seen += 1
                st = p.stat()
                ss = s.get(ScanState, str(p))
                if ss and ss.size == st.st_size and abs(ss.mtime - st.st_mtime) < 1e-6:
                    rep.skipped_unchanged += 1
                    continue
                pending.append(p)
                owner_of[p] = owner
                if len(pending) >= batch:
                    flush_batch()
        if pending:
            flush_batch()

        # library files that disappeared (moved away by a person, for example)
        for m in s.execute(select(Media).where(Media.status == "active")).scalars():
            if any(str(m.nas_path).startswith(str(r)) for r, _ in roots) and not Path(m.nas_path).exists():
                rep.missing += 1
                set_location(s, m.id, "nas", "removed")
                m.status = "removed"  # deleted in Synology Photos / File Station: gone from the app too
                touch_media(s, m)

        if apply:
            kv_set(s, "scan_progress", "")
        job.finished_at = utcnow()
        job.new_count, job.dup_count, job.error_count = rep.new, len(rep.duplicates), len(rep.errors)
        job.message = rep.summary()
        if apply:
            job.state = "done"
            kv_set(s, "last_scan_at", utcnow().isoformat())
            log_event(s, "info", "scan_done", rep.summary())
            s.commit()
        else:
            s.rollback()
            s.add(ImportJob(source="phone_scan", archive_name=", ".join(str(r) for r, _ in roots), state="dry_run",
                            new_count=rep.new, dup_count=len(rep.duplicates), error_count=len(rep.errors),
                            finished_at=utcnow(), message=rep.summary()))
            s.commit()
    finally:
        s.close()
    return rep


def _trash_own_copy(s, settings: Settings, m: Media, keep: Path) -> None:  # noqa: ANN001
    from .db import log_event
    from .fileops import move_verified, unique_in
    from .xmp import sidecar_path as sc
    src = Path(m.nas_path)
    day = f"{utcnow():%Y-%m-%d}"
    dst = unique_in(settings.trash / day / "duplicates", src.name)
    move_verified(src, dst, m.file_hash)
    if sc(src).exists():
        move_verified(sc(src), dst.with_name(dst.name + ".xmp"))
    m.nas_path, m.placed = str(keep), False
    set_location(s, m.id, "nas", "present", source_ref=str(keep))
    log_event(s, "info", "trash_move", f"duplicate of {keep}: PhotoHub's copy {src} -> {dst}")


def _restore_from_xmp(s, m: Media, path: Path) -> None:  # noqa: ANN001
    """Rebuilding the DB from the library: albums/description come back from our sidecars."""
    sc = sidecar_path(path)
    if not (sc.exists() and xmp.is_ours(sc)):
        return
    try:
        data = xmp.read(sc)
    except Exception:
        return
    if data.get("content_hash") not in (None, m.content_hash):
        return
    if data.get("description") and not m.description:
        m.description = data["description"]
    _add_albums(s, m, set(data.get("albums") or []), "google", m.owner_id)


def write_duplicate_report(rep: ScanReport, dest: Path) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with open(dest, "w", encoding="utf-8") as f:
        f.write("duplicate_path\tkept_path\n")
        for a, b in rep.duplicates:
            f.write(f"{a}\t{b}\n")
    return dest
