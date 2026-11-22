"""Photos that arrive from phones, either directly or through Google Drive.

  phone -> POST /api/upload          -> <root>/incoming/<user>/<sha256>.<ext>   (verified, then queued)
  phone -> Google Drive -> Cloud Sync -> <root>/inbox/drive/<user>/...         (one-way download)

`process_incoming` places them like a Takeout import: duplicates (same pixels) are merged,
new ones go to the person's chosen space (personal by default, or shared), YYYY/YYYY-MM.
It respects PHOTOHUB_APPLY: in dry-run nothing is placed and uploads wait in incoming/.

Phone uploads are our own temporary copies: they are removed once the library copy is verified.
Files in the Drive folder belong to Cloud Sync and are left untouched (only remembered).
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import (Database, ImportItem, ImportJob, Media, Upload, User, grant_access, kv_set, log_event,
                 set_location, touch_media, utcnow)
from .fileops import copy_verified, library_dir, plan_destination
from .hashing import file_sha256, kind_of
from .ingest import analyse, merge_into, new_media, takeout_destination, write_sidecar
from .metadata import file_mtime_utc
from .users import all_users


def incoming_dir(settings: Settings, user: User) -> Path:
    return settings.root / "incoming" / user.name


def is_system_thumb(p: Path) -> bool:
    """Synology's own thumbnails (SYNOFILE_THUMB_*.jpg, SYNOPHOTO_THUMB_*.jpg) are not photos."""
    return "@eaDir" in p.parts or p.name.upper().startswith(("SYNOFILE_THUMB", "SYNOPHOTO_THUMB"))


def drive_dir(settings: Settings, user: User) -> Path:
    """Where the person's Drive folder "PhotoHub" shows up on the NAS (Cloud Sync):
      1. set explicitly (photohub user set NAME --drive-dir ...) or chosen in the app (drive_root)
      2. in their home folder, next to Takeout (the recommended place):
         /volume1/homes/<name>/PhotoHub or /volume1/homes/<name>/<folder>/PhotoHub
      3. older setups: <root>/inbox/drive/<name> (or its PhotoHub subfolder)"""
    if user.drive_dir:
        return Path(user.drive_dir)
    home = settings.homes_root / user.name
    if user.drive_photos_dir:
        return home / user.drive_photos_dir
    if user.drive_root:
        return home / user.drive_root / "PhotoHub"
    try:
        found = ([home / "PhotoHub"] if (home / "PhotoHub").is_dir() else []) + \
            sorted(p for p in home.glob("*/PhotoHub") if p.is_dir() and p.parent.name != "Photos")
    except OSError:
        found = []
    if found:
        return found[0]
    old = settings.inbox.parent / "drive" / user.name
    if (old / "PhotoHub").is_dir():
        return old / "PhotoHub"
    return old


class UploadRejected(ValueError):
    pass


def store_upload(db: Database, settings: Settings, user: User, src: Path, file_hash: str, name: str,
                 device: str | None = None, rel_path: str | None = None) -> dict:
    """Keep a received file (already written to `src`) if its hash matches; queue it."""
    file_hash = file_hash.lower()
    if file_sha256(src) != file_hash:
        src.unlink(missing_ok=True)
        raise UploadRejected("hash mismatch (transfer damaged); please retry")
    ext = Path(name).suffix.lower()
    if not kind_of(Path("x" + ext)):
        src.unlink(missing_ok=True)
        raise UploadRejected(f"not a photo or video: {name}")
    with db.session() as s:
        seen = s.execute(select(Upload).where(Upload.user_id == user.id, Upload.file_hash == file_hash)).scalars().first()
        if seen is not None:
            src.unlink(missing_ok=True)
            return {"status": "already", "processed": seen.processed_at is not None}
    dest = incoming_dir(settings, user) / f"{file_hash}{ext}"
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        src.unlink()
    else:
        os.replace(src, dest)
    with db.session() as s:
        s.add(Upload(user_id=user.id, source="phone", file_hash=file_hash, path=str(dest),
                     size=dest.stat().st_size, mtime=dest.stat().st_mtime, name=Path(name).name[:255],
                     device=(device or "")[:128] or None, rel_path=(rel_path or "")[:500] or None))
    return {"status": "stored"}


def have(db: Database, user: User, hashes: list[str]) -> dict[str, str]:
    """For each file hash: 'nas' (in the library and visible to the user), 'queued' (received,
    waiting), or absent. Phones delete from Drive only what is 'nas'."""
    hashes = [h.lower() for h in hashes][:5000]
    out: dict[str, str] = {}
    from .db import visible_clause
    with db.session() as s:
        for (fh,) in s.execute(select(Media.file_hash).where(Media.file_hash.in_(hashes), Media.status == "active",
                                                             visible_clause(user))).all():
            out[fh] = "nas"
        # deleted on purpose (in the trash or gone for good): the phone must not send it again
        for (fh,) in s.execute(select(Media.file_hash).where(Media.file_hash.in_(hashes),
                                                             Media.status.in_(["trashed", "deleted"]),
                                                             visible_clause(user))).all():
            out.setdefault(fh, "deleted")
        for u in s.execute(select(Upload).where(Upload.user_id == user.id, Upload.file_hash.in_(hashes))).scalars():
            if u.file_hash in out:
                continue
            if u.media_id is not None:
                out[u.file_hash] = "nas"  # merged into an item the user can see (access granted)
            else:
                out[u.file_hash] = "queued"
    return out


def _scan_drive_folder(db: Database, settings: Settings, user: User) -> int:
    """Register new/changed files that Cloud Sync brought from the person's Drive."""
    root = drive_dir(settings, user)
    if not root.exists():
        return 0
    n = 0
    with db.session() as s:
        known = {u.path: u for u in s.execute(select(Upload).where(Upload.user_id == user.id,
                                                                   Upload.source == "drive")).scalars()}
        from .scanner import walk  # skips @eaDir (Synology's own thumbnails), #recycle, hidden files
        for p in walk(root):
            if is_system_thumb(p):
                continue
            st = p.stat()
            k = known.get(str(p))
            if k is not None and k.size == st.st_size and abs(k.mtime - st.st_mtime) < 1e-6:
                continue
            if settings.stable_seconds and (utcnow().timestamp() - st.st_mtime) < settings.stable_seconds:
                continue  # Cloud Sync may still be writing
            fh = file_sha256(p)
            if k is None:
                s.add(Upload(user_id=user.id, source="drive", file_hash=fh, path=str(p), size=st.st_size,
                             mtime=st.st_mtime, name=p.name))
            else:
                k.file_hash, k.size, k.mtime, k.processed_at, k.media_id = fh, st.st_size, st.st_mtime, None, None
            n += 1
    return n


def drive_removed(db: Database, settings: Settings, user: User, hashes: list[str]) -> int:
    """The phone removed these files from Google Drive (outside the window): delete the copies that
    Cloud Sync left in the Synology's Drive folder - but only ones already filed in the library."""
    from .db import Location
    hashes = [h.lower() for h in hashes][:5000]
    root = drive_dir(settings, user).resolve()
    n = 0
    with db.session() as s:
        for up in s.execute(select(Upload).where(Upload.user_id == user.id, Upload.source == "drive",
                                                 Upload.file_hash.in_(hashes))).scalars():
            if up.processed_at is None or up.media_id is None:
                continue  # not in the library yet (or check mode): keep it
            safe = s.execute(select(Location).where(Location.media_id == up.media_id, Location.store == "nas",
                                                    Location.state == "present")).scalars().first()
            p = Path(up.path)
            if safe is None or not p.exists() or not p.resolve().is_relative_to(root):
                continue
            if Path(safe.source_ref or "").resolve() == p.resolve():
                continue  # this file IS the library copy
            p.unlink()
            set_location(s, up.media_id, "drive", "removed", user_id=user.id)
            n += 1
        if n:
            log_event(s, "info", "drive_cleanup", f"{user.name}: {n} file(s) removed from Google Drive, Synology copy of the Drive folder deleted")
    return n


@dataclass
class IncomingReport:
    dry_run: bool
    new: int = 0
    duplicate: int = 0
    errors: list = field(default_factory=list)

    def summary(self) -> str:
        return (f"[{'DRY-RUN' if self.dry_run else 'APPLIED'}] phone/Drive uploads: new={self.new} "
                f"duplicate={self.duplicate} error={len(self.errors)}")


def process_incoming(db: Database, settings: Settings, *, apply: bool) -> IncomingReport:
    rep = IncomingReport(dry_run=not apply)
    for user in all_users(db):
        _scan_drive_folder(db, settings, user)
        with db.session() as s:
            pending = list(s.execute(select(Upload).where(Upload.user_id == user.id,
                                                          Upload.processed_at.is_(None))).scalars())
        pending = [u for u in pending if Path(u.path).exists()]
        if not pending:
            continue
        if not (user.personal_root and Path(user.personal_root).is_dir()):
            # their Synology Photos folder is not known yet (e.g. logged in with an e-mail address):
            # keep the files waiting rather than put them in the wrong place
            rep.errors.append(f"{user.name}: Synology Photos folder not found ({user.personal_root}); waiting")
            continue
        if apply:
            # Photos Synology Photos (or anything else) already put in the person's space must be
            # known before deciding what is new, or the same photo would be stored twice.
            from .prescan import refresh_index
            refresh_index(db, settings, user)
        cands = {c.path: c for c in analyse([Path(u.path) for u in pending], settings)}
        s = db.new_session()
        try:
            job = ImportJob(source="phone", archive_name=f"{user.name}: phone/Drive", user_id=user.id)
            s.add(job)
            s.flush()
            root, owner = takeout_destination(settings, user)
            layouts: dict = {}
            for up in pending:
                up = s.get(Upload, up.id)
                c = cands[Path(up.path)]
                if c.error:
                    rep.errors.append(f"{up.name}: {c.error}")
                    s.add(ImportItem(job_id=job.id, original_path=up.path, result="error", message=c.error))
                    continue
                existing = s.execute(select(Media).where(Media.content_hash == c.content_hash)).scalar_one_or_none()
                if existing is not None:
                    changed = merge_into(s, existing, c) | grant_access(s, existing, user.id)
                    if changed:
                        touch_media(s, existing)
                    m, result = existing, "duplicate"
                    rep.duplicate += 1
                else:
                    when = file_mtime_utc(c.path)
                    from . import mobile_backup as mb
                    dev = mb.device_dir(user, up.device) if up.source == "phone" else None
                    if dev is not None:
                        # same folder and layout as Synology Photos' own backup of this phone
                        if dev not in layouts:
                            layouts[dev] = mb.infer_layout(dev, mb.samples_for(dev, settings))
                        target = mb.target_path(dev, layouts[dev], up.name or c.path.name,
                                                mb.local_time(c.meta.taken_at, c.meta.tz_offset, when), up.rel_path)
                        plan = plan_destination(target.parent, target.name, c.file_hash)
                        own = user.id
                    else:
                        dest_dir = library_dir(root, c.meta.taken_at, c.meta.tz_offset, when)
                        plan = plan_destination(dest_dir, up.name or c.path.name, c.file_hash)
                        own = owner
                    if apply and not plan.already_present:
                        copy_verified(c.path, plan.path, c.file_hash)
                    m = new_media(c, plan.path, when, own)
                    m.placed = not plan.already_present
                    s.add(m)
                    s.flush()
                    touch_media(s, m)
                    set_location(s, m.id, "nas", "present", source_ref=str(plan.path))
                    if apply:
                        write_sidecar(s, m)
                    result = "new"
                    rep.new += 1
                if up.source == "drive":
                    set_location(s, m.id, "drive", "present", user_id=user.id, source_ref=up.path)
                s.add(ImportItem(job_id=job.id, original_path=up.path, media_id=m.id, result=result))
                if apply:
                    up.processed_at, up.media_id = utcnow(), m.id
            job.new_count, job.dup_count, job.error_count = rep.new, rep.duplicate, len(rep.errors)
            job.finished_at = utcnow()
            job.message = rep.summary()
            if apply:
                job.state = "done" if not rep.errors else "failed"
                kv_set(s, f"last_upload_at:{user.id}", utcnow().isoformat())
                s.commit()
                # our temporary phone copies are no longer needed once the library copy is verified
                for up in pending:
                    if up.source == "phone" and Path(up.path).exists():
                        Path(up.path).unlink()
            else:
                s.rollback()
                s.add(ImportJob(source="phone", archive_name=f"{user.name}: phone/Drive", user_id=user.id,
                                state="dry_run", new_count=rep.new, dup_count=rep.duplicate,
                                error_count=len(rep.errors), finished_at=utcnow(), message=rep.summary()))
                log_event(s, "info", "upload_dry_run", rep.summary())
                s.commit()
        finally:
            s.close()
    return rep
