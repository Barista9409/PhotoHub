"""F1: Takeout import.

inbox/takeout/*.zip  --(stable for N s)-->  work/<key>/extracted  --GPTH-->  work/<key>/gpth-out
  --> analyse + dedupe + place (ingest.py)  --> ZIPs moved to processed/ (never deleted)

Split archives (takeout-...-001.zip, -002.zip) are grouped by their common prefix.
Re-running on the same ZIPs changes nothing (job key + content hashes).
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import Database, ImportItem, ImportJob, User, kv_get, kv_set, log_event, utcnow
from .fileops import copy_verified, move_verified, unique_in
from .ingest import analyse, collect_media, ingest_takeout_candidate
from .users import all_users

PART_RE = re.compile(r"^(?P<prefix>.+?)-(?P<part>\d{3})\.zip$", re.I)
SKIP_SPECIAL = {"trash": "google-trash", "locked folder": "google-locked"}


@dataclass
class ArchiveGroup:
    prefix: str
    parts: list[Path]
    user: str | None = None  # whose Takeout (inbox/takeout/<user>/)

    @property
    def key(self) -> str:
        h = hashlib.sha256()
        h.update(f"user:{self.user}\n".encode())
        for p in sorted(self.parts):
            h.update(f"{p.name}:{p.stat().st_size}\n".encode())
        return h.hexdigest()[:32]


@dataclass
class ImportReport:
    archive: str
    dry_run: bool
    new: int = 0
    duplicate: int = 0
    merged: int = 0
    error: int = 0
    set_aside: dict = field(default_factory=dict)
    errors: list = field(default_factory=list)
    skipped_job: bool = False

    def summary(self) -> str:
        mode = "DRY-RUN" if self.dry_run else "APPLIED"
        aside = ", ".join(f"{k}={v}" for k, v in self.set_aside.items()) or "none"
        return (f"[{mode}] {self.archive}: new={self.new} duplicate={self.duplicate} "
                f"merged={self.merged} error={self.error} set-aside: {aside}")


def find_groups(inbox: Path, user: str | None = None) -> list[ArchiveGroup]:
    groups: dict[str, list[Path]] = {}
    for z in sorted(inbox.glob("*.zip")):
        m = PART_RE.match(z.name)
        prefix = m.group("prefix") if m else z.stem
        groups.setdefault(prefix, []).append(z)
    return [ArchiveGroup(k, v, user) for k, v in groups.items()]


def is_stable(group: ArchiveGroup, seconds: int) -> bool:
    """Cloud Sync may still be writing: wait until sizes/mtimes stop changing."""
    now = time.time()
    for p in group.parts:
        st = p.stat()
        if now - st.st_mtime < seconds:
            return False
        if not zipfile.is_zipfile(p):
            return False
    return True


def _extract(group: ArchiveGroup, dest: Path) -> None:
    # markers live next to (not inside) the extracted tree, so GPTH never sees them
    done = dest.with_name(dest.name + ".done")
    if done.exists():
        return
    dest.mkdir(parents=True, exist_ok=True)
    sevenzip = shutil.which("7z")
    for part in sorted(group.parts):
        marker = dest.with_name(f"{dest.name}.{part.stem}.done")
        if marker.exists():
            continue
        if sevenzip:
            subprocess.run([sevenzip, "x", "-y", "-bd", f"-o{dest}", str(part)],
                           check=True, capture_output=True)
        else:
            with zipfile.ZipFile(part) as z:
                for info in z.infolist():
                    target = (dest / info.filename).resolve()
                    if not str(target).startswith(str(dest.resolve())):
                        raise RuntimeError(f"unsafe path in zip: {info.filename}")
                z.extractall(dest)
        marker.touch()
    done.touch()


def run_gpth(settings: Settings, src: Path, out: Path) -> None:
    """GPTH Neo (research §2.2). Input is our extracted copy; --keep-input protects it too."""
    if (out / ".gpth-done").exists():
        return
    cmd = [settings.gpth_bin, "--input", str(src), "--output", str(out), "--albums", "json",
           "--keep-input", "--keep-duplicates", "--write-exif", "--transform-pixel-mp", "still",
           "--save-log"]
    env = dict(os.environ, TZ="UTC")  # GPTH writes local time + offset; force UTC (+00:00)
    res = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, env=env, timeout=7 * 24 * 3600)
    (out.parent / "gpth.log").write_bytes(res.stdout + b"\n--- stderr ---\n" + res.stderr)
    if res.returncode != 0:
        raise RuntimeError(f"gpth failed with code {res.returncode}; see {out.parent / 'gpth.log'}")
    # GPTH --keep-input leaves a sibling "<input>_tmp" copy; it is ours to remove.
    tmp = src.with_name(src.name + "_tmp")
    if tmp.exists():
        shutil.rmtree(tmp, ignore_errors=True)
    (out / ".gpth-done").touch()


def album_map(out: Path) -> dict[str, set[str]]:
    """targetPath (relative to GPTH output) -> album names, from albums-info.json."""
    f = out / "albums-info.json"
    res: dict[str, set[str]] = {}
    if not f.exists():
        return res
    data = json.loads(f.read_text(encoding="utf-8"))
    for name, entries in (data.get("albums") or {}).items():
        for e in entries:
            tgt = e.get("targetPath") or e.get("filePath")
            if tgt:
                res.setdefault(tgt.replace("\\", "/"), set()).add(name)
    return res


def import_group(db: Database, settings: Settings, group: ArchiveGroup, *, apply: bool,
                 user: User | None = None) -> ImportReport:
    rep = ImportReport(archive=(f"{user.name}: " if user else "") + group.prefix, dry_run=not apply)
    key = group.key
    with db.session() as s:
        done = s.execute(select(ImportJob).where(ImportJob.archive_key == key,
                                                 ImportJob.state == "done")).scalar_one_or_none()
    if done is not None:
        rep.skipped_job = True
        if apply:
            _archive_parts(settings, group)
        return rep

    workdir = settings.work / key
    extracted, out = workdir / "extracted", workdir / "gpth-out"
    _extract(group, extracted)
    run_gpth(settings, extracted, out)

    albums = album_map(out)
    main_files, aside_files = [], []
    for p in collect_media(out):
        rel = p.relative_to(out).as_posix()
        parts = [x.lower() for x in p.relative_to(out).parts]
        if parts[0] == "special folders" and len(parts) > 1 and parts[1] in SKIP_SPECIAL:
            aside_files.append((p, rel, SKIP_SPECIAL[parts[1]]))
        elif "_duplicates" in parts:
            main_files.append(p)  # GPTH's duplicates: let F2 decide (they will dedupe)
        else:
            main_files.append(p)

    cands = analyse(main_files, settings)
    for c in cands:
        c.albums = set(albums.get(c.path.relative_to(out).as_posix(), set()))
        parts = [x.lower() for x in c.path.relative_to(out).parts]
        if parts[0] == "special folders" and len(parts) > 1 and parts[1] == "archive":
            c.albums.add("Google: Archive")

    # dry-run: run everything inside one transaction and roll it back
    s = db.new_session()
    try:
        job = s.execute(select(ImportJob).where(ImportJob.archive_key == key,
                                                ImportJob.state.in_(["running", "failed"]))).scalar_one_or_none()
        if job is None:
            job = ImportJob(source="takeout", archive_name=", ".join(p.name for p in group.parts),
                            archive_key=key, user_id=user.id if user else None)
            s.add(job)
            s.flush()
        job.state = "running"
        # identical photos inside one archive -> process in a stable order
        cands.sort(key=lambda c: (c.path.relative_to(out).parts[0].lower() == "special folders",
                                  "_duplicates" in [x.lower() for x in c.path.parts], str(c.path)))
        for c in cands:
            rel = c.path.relative_to(out).as_posix()
            try:
                with s.begin_nested():  # savepoint: a failing file leaves no half-written rows
                    item = ingest_takeout_candidate(s, settings, job, c, rel, apply, settings.root, user)
            except Exception as e:  # one bad file must not stop the archive
                item = s.execute(select(ImportItem).where(ImportItem.job_id == job.id,
                                                          ImportItem.original_path == rel)).scalar_one_or_none()
                if item is None:
                    item = ImportItem(job_id=job.id, original_path=rel, result="error")
                    s.add(item)
                item.result, item.message = "error", f"{type(e).__name__}: {e}"
            if item.result == "error":
                rep.errors.append(f"{rel}: {item.message}")
            bucket = item.result if item.result in ("new", "duplicate", "merged") else "error"
            setattr(rep, bucket, getattr(rep, bucket) + 1)
            if apply:
                s.commit()  # progress survives an interruption
        for p, rel, dest_name in aside_files:
            rep.set_aside[dest_name] = rep.set_aside.get(dest_name, 0) + 1
            if apply:
                dst = settings.root / dest_name / (user.name if user else "_") / key / rel
                if not dst.exists():
                    copy_verified(p, dst)
        job.new_count, job.dup_count, job.error_count = rep.new, rep.duplicate + rep.merged, rep.error
        job.finished_at = utcnow()
        if not apply:
            s.rollback()
            s.add(ImportJob(source="takeout", archive_name=job.archive_name, archive_key=key,
                            user_id=user.id if user else None, state="dry_run", new_count=rep.new, dup_count=rep.duplicate + rep.merged,
                            error_count=rep.error, finished_at=utcnow(), message=rep.summary()))
            log_event(s, "info", "import_dry_run", rep.summary())
        else:
            job.state = "done" if rep.error == 0 else "failed"
            job.message = rep.summary()
            log_event(s, "info" if rep.error == 0 else "error",
                      "import_done" if rep.error == 0 else "import_failed", rep.summary())
            kv_set(s, "last_import_at", utcnow().isoformat())
            kv_set(s, f"last_import_at:{user.id if user else 0}", utcnow().isoformat())
        s.commit()
    except BaseException:
        s.rollback()
        raise
    finally:
        s.close()

    if apply and rep.error == 0:
        _archive_parts(settings, group)
        shutil.rmtree(workdir, ignore_errors=True)  # our temporary copies only
    return rep


def _recently_tried(db: Database, key: str, apply: bool) -> bool:
    from datetime import timedelta
    with db.session() as s:
        jobs = list(s.execute(select(ImportJob).where(ImportJob.archive_key == key)).scalars())
    if not apply and any(j.state == "dry_run" for j in jobs):
        return True
    return any(j.state == "failed" and j.finished_at and j.finished_at > utcnow() - timedelta(hours=24) for j in jobs)


def _archive_parts(settings: Settings, group: ArchiveGroup) -> None:
    """All good -> move the ZIPs to processed/<user>/ (copy-verify-move; never delete)."""
    dest = settings.processed / group.user if group.user else settings.processed
    dest.mkdir(parents=True, exist_ok=True)
    for p in group.parts:
        if p.exists():
            move_verified(p, unique_in(dest, p.name))


def import_inbox(db: Database, settings: Settings, *, apply: bool, wait_stable: bool = True) -> list[ImportReport]:
    settings.ensure_dirs()
    reports = []
    users = all_users(db)
    work: list[tuple[ArchiveGroup, User | None]] = []
    for u in users:
        (settings.inbox / u.name).mkdir(parents=True, exist_ok=True)
        work += [(g, u) for g in find_groups(settings.inbox / u.name, u.name)]
    loose = find_groups(settings.inbox)
    if loose and users:
        # with family members configured, every Takeout must say whose it is
        with db.session() as s:
            if not kv_get(s, "warned:loose_zip"):
                kv_set(s, "warned:loose_zip", "1")
                log_event(s, "warn", "import_failed",
                          "ZIP directly in inbox/takeout/ is ignored: put it in inbox/takeout/<user>/")
    elif loose:
        work += [(g, None) for g in loose]  # single-person setup without users: shared space
    for g, u in work:
        if wait_stable and not is_stable(g, settings.stable_seconds):
            continue
        if wait_stable and _recently_tried(db, g.key, apply):
            continue  # scheduled runs: don't redo the same dry run / failing archive every 10 min
        try:
            reports.append(import_group(db, settings, g, apply=apply, user=u))
        except Exception as e:
            with db.session() as s:
                log_event(s, "error", "import_failed", f"{g.prefix}: {e}")
            r = ImportReport(archive=g.prefix, dry_run=not apply, error=1, errors=[str(e)])
            reports.append(r)
    return reports
