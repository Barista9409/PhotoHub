"""Tidying up the library from the app (spec: 写真の整理).

- Delete from anywhere: the photo goes to PhotoHub's trash (trash/bin/...) and disappears from every
  phone at the next sync. It can be restored for `trash_days` (30) days, then it is deleted for good.
- Move between "only me" (the person's Photos folder) and "family" (the shared photo folder).
- Exact duplicates: the same file found a second time somewhere in the library.
- Suggestions: blurry photos (sharpness measured on the thumbnail).
Shared photos may be deleted or moved by anyone in the family (they can be restored from the trash).
"""
from __future__ import annotations

import json
from collections import defaultdict
import re
import logging
import os
import threading
from datetime import timedelta
from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import KV, Database, ImportItem, Media, User, can_see, kv_get, kv_set, log_event, set_location, touch_media, utcnow
from .fileops import copy_verified, library_dir, move_verified, unique_in
from .xmp import sidecar_path


def _bin(settings: Settings) -> Path:
    return settings.trash / "bin"


def _move_with_sidecar(src: Path, dst: Path) -> None:
    move_verified(src, dst)
    sc = sidecar_path(src)
    if sc.exists():
        try:
            move_verified(sc, sidecar_path(dst))
        except Exception:  # noqa: BLE001 - a sidecar must not block the photo
            pass


def _may_change(s, user: User | None, m: Media) -> bool:  # noqa: ANN001
    """Own photos and the family's shared ones (not someone else's that is merely visible).
    user None = the NAS itself (command line, similar-photo groups resolved there)."""
    return user is None or m.owner_id is None or m.owner_id == user.id


def _quick_move(src: Path, dst: Path) -> bool:
    """A rename when the trash is on the same volume (instant); False when the file would have to be
    copied (another shared folder is another volume on a Synology), so it is moved in the background."""
    try:
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists():
            return False
        os.rename(src, dst)
    except OSError:
        return False
    sc = sidecar_path(src)
    if sc.exists():
        try:
            os.rename(sc, sidecar_path(dst))
        except OSError:
            pass
    return True


_MOVER = threading.Lock()


def trash(db: Database, settings: Settings, user: User | None, hashes: list[str]) -> int:
    """Gone at once for everyone (status), the file goes into PhotoHub's trash right away when that is a
    rename, otherwise a moment later in the background: the person never waits for a video to copy."""
    n = 0
    pending = False
    with db.session() as s:
        for m in s.execute(select(Media).where(Media.content_hash.in_(hashes[:5000]), Media.status == "active")).scalars():
            if not _may_change(s, user, m):
                continue
            src = Path(m.nas_path)
            info = {"from": str(src), "by": user.name if user else "PhotoHub", "owner": m.owner_id}
            if src.exists():
                dst = unique_in(_bin(settings) / f"{m.id}", src.name)
                if _quick_move(src, dst):
                    m.nas_path = str(dst)
                else:
                    kv_set(s, f"trashmove:{m.id}", "1")
                    pending = True
            m.status = "trashed"
            kv_set(s, f"trash:{m.id}", json.dumps({**info, "at": utcnow().isoformat()}))
            set_location(s, m.id, "nas", "removed")
            touch_media(s, m)
            n += 1
        if n:
            log_event(s, "info", "trash", f"{user.name if user else 'PhotoHub'}: {n} item(s) moved to the trash")
    if pending:
        threading.Thread(target=move_pending, args=(db, settings), daemon=True).start()
    return n


def move_pending(db: Database, settings: Settings) -> int:
    """Finish the trash moves left for the background (also run by the daemon, e.g. after a restart)."""
    if not _MOVER.acquire(blocking=False):
        return 0
    n = 0
    try:
        with db.session() as s:
            ids = [int(k.split(":", 1)[1]) for k in s.execute(select(KV.key).where(KV.key.like("trashmove:%"), KV.value == "1")).scalars()]
        for mid in ids:
            try:
                with db.session() as s:
                    m = s.get(Media, mid)
                    kv_set(s, f"trashmove:{mid}", "")
                    if m is None or m.status not in ("trashed", "deleted"):
                        continue   # restored meanwhile: the file is where it was
                    src = Path(m.nas_path)
                    if src.exists() and not src.is_relative_to(_bin(settings)):
                        dst = unique_in(_bin(settings) / f"{m.id}", src.name)
                        _move_with_sidecar(src, dst)
                        m.nas_path = str(dst)
                        if m.status == "deleted":   # emptied from the trash before the move finished
                            dst.unlink(missing_ok=True)
                            sidecar_path(dst).unlink(missing_ok=True)
                    n += 1
            except Exception:  # noqa: BLE001 - try again on the next run
                logging.getLogger(__name__).exception("trash move %s", mid)
                with db.session() as s:
                    kv_set(s, f"trashmove:{mid}", "1")
    finally:
        _MOVER.release()
    return n


def trashed(db: Database, settings: Settings, user: User) -> list[dict]:
    from collections import defaultdict
    from .db import Album, AlbumItem
    out = []
    with db.session() as s:
        # the albums each photo was in (album links stay while it is in the trash; it goes back to them)
        in_albums: dict[int, list[str]] = defaultdict(list)
        for mid, name in s.execute(select(AlbumItem.media_id, Album.name).join(Album, Album.id == AlbumItem.album_id)
                                   .join(Media, Media.id == AlbumItem.media_id).where(Media.status == "trashed")
                                   .order_by(Album.name)).all():
            in_albums[mid].append(name)
        for m in s.execute(select(Media).where(Media.status == "trashed").order_by(Media.updated_at.desc())).scalars():
            if not _may_change(s, user, m):
                continue
            info = json.loads(kv_get(s, f"trash:{m.id}") or "{}")
            at = info.get("at")
            days_left = settings.trash_days
            if at:
                from datetime import datetime
                days_left = max(0, settings.trash_days - (utcnow() - datetime.fromisoformat(at)).days)
            out.append({"hash": m.content_hash, "name": Path(info.get("from") or m.nas_path).name, "kind": m.kind,
                        "taken_at": int(m.taken_at.timestamp() * 1000) if m.taken_at else None,
                        "by": info.get("by"), "days_left": days_left, "space": "shared" if m.owner_id is None else "mine",
                        "albums": in_albums.get(m.id, []), "folder": Path(info.get("from") or m.nas_path).parent.name,
                        "tz": m.tz_offset})
    return out


def restore(db: Database, settings: Settings, user: User, hashes: list[str]) -> int:
    n = 0
    with db.session() as s:
        for m in s.execute(select(Media).where(Media.content_hash.in_(hashes[:5000]), Media.status == "trashed")).scalars():
            if not _may_change(s, user, m):
                continue
            info = json.loads(kv_get(s, f"trash:{m.id}") or "{}")
            back = Path(info.get("from") or "")
            src = Path(m.nas_path)
            if kv_get(s, f"trashmove:{m.id}") == "1" and src == back:
                kv_set(s, f"trashmove:{m.id}", "")   # never moved: it is already where it belongs
                set_location(s, m.id, "nas", "present", source_ref=str(src))
            elif src.exists() and back.name:
                dst = unique_in(back.parent, back.name)
                _move_with_sidecar(src, dst)
                m.nas_path = str(dst)
                set_location(s, m.id, "nas", "present", source_ref=str(dst))
            m.status = "active"
            kv_set(s, f"trash:{m.id}", "")
            touch_media(s, m)
            n += 1
    return n


def purge(db: Database, settings: Settings, user: User | None = None, hashes: list[str] | None = None,
          older_than_days: int | None = None) -> int:
    """Delete for good: chosen items, everything of `user`, or (daily job) all older than N days."""
    from datetime import datetime
    n = 0
    with db.session() as s:
        q = select(Media).where(Media.status == "trashed")
        if hashes is not None:
            q = q.where(Media.content_hash.in_(hashes[:5000]))
        for m in s.execute(q).scalars():
            if user is not None and not _may_change(s, user, m):
                continue
            if older_than_days is not None:
                info = json.loads(kv_get(s, f"trash:{m.id}") or "{}")
                at = info.get("at")
                if not at or utcnow() - datetime.fromisoformat(at) < timedelta(days=older_than_days):
                    continue
            p = Path(m.nas_path)
            if p.is_relative_to(_bin(settings)):  # never delete anything outside PhotoHub's trash
                p.unlink(missing_ok=True)
                sidecar_path(p).unlink(missing_ok=True)
                try:
                    p.parent.rmdir()
                except OSError:
                    pass
            m.status = "deleted"
            kv_set(s, f"trash:{m.id}", "")
            touch_media(s, m)
            n += 1
        if n:
            log_event(s, "info", "trash_purged", f"{n} item(s) deleted for good")
    return n


def move_space(db: Database, settings: Settings, user: User, hashes: list[str], to: str) -> int:
    """to="shared": own photo -> the family photo folder (YYYY/YYYY-MM). to="mine": shared photo ->
    the person's Photos/PhotoHub/YYYY/YYYY-MM. The file moves; everyone's view follows at the next sync."""
    if to not in ("shared", "mine"):
        raise ValueError("to must be shared or mine")
    if to == "mine" and not (user.personal_root and Path(user.personal_root).is_dir()):
        raise ValueError("your Photos folder was not found")
    n = 0
    with db.session() as s:
        for m in s.execute(select(Media).where(Media.content_hash.in_(hashes[:5000]), Media.status == "active")).scalars():
            if to == "shared" and m.owner_id != user.id:
                continue
            if to == "mine" and m.owner_id is not None:
                continue
            src = Path(m.nas_path)
            if not src.exists():
                continue
            base = settings.library if to == "shared" else Path(user.personal_root) / "PhotoHub"
            dst = unique_in(library_dir(base, m.taken_at, m.tz_offset, utcnow()), src.name)
            _move_with_sidecar(src, dst)
            m.nas_path = str(dst)
            m.owner_id = None if to == "shared" else user.id
            m.placed = False  # a person put it there on purpose
            set_location(s, m.id, "nas", "present", source_ref=str(dst))
            touch_media(s, m)
            n += 1
        if n:
            log_event(s, "info", "moved", f"{user.name}: {n} item(s) moved to {to}")
    return n


def _copy_key(path: Path) -> str:
    import hashlib
    return "keepcopy:" + hashlib.sha1(str(path).encode()).hexdigest()[:24]


def copy_space(db: Database, settings: Settings, user: User, hashes: list[str], to: str) -> int:
    """Copy photos between the person's own photos and the family's shared space; both copies stay.
    to="shared": an own photo gets a copy in the family folder (photo/YYYY/YYYY-MM) and becomes a
    family photo in PhotoHub (everyone sees it; the person's own copy stays in their Photos).
    to="mine": a family photo gets a copy in the person's Photos/PhotoHub/YYYY/YYYY-MM (kept even if
    the family one is deleted later). Copies made on purpose are not offered as 「同じ写真が2か所に」."""
    if to not in ("shared", "mine"):
        raise ValueError("to must be shared or mine")
    if to == "mine" and not (user.personal_root and Path(user.personal_root).is_dir()):
        raise ValueError("your Photos folder was not found")
    n = 0
    with db.session() as s:
        for m in s.execute(select(Media).where(Media.content_hash.in_(hashes[:5000]), Media.status == "active")).scalars():
            if to == "shared" and m.owner_id != user.id:
                continue   # already the family's, or someone else's
            if to == "mine" and (m.owner_id is not None or not can_see(s, user, m)):
                continue
            src = Path(m.nas_path)
            if not src.exists():
                continue
            base = settings.library if to == "shared" else Path(user.personal_root) / "PhotoHub"
            dst = unique_in(library_dir(base, m.taken_at, m.tz_offset, utcnow()), src.name)
            copy_verified(src, dst, m.file_hash)
            sc = sidecar_path(src)
            if sc.exists():
                try:
                    copy_verified(sc, sidecar_path(dst))
                except Exception:  # noqa: BLE001 - the sidecar is optional
                    pass
            if to == "shared":
                kv_set(s, _copy_key(src), "1")     # the person's own copy stays, on purpose
                m.nas_path = str(dst)
                m.owner_id = None
                m.placed = False
                set_location(s, m.id, "nas", "present", source_ref=str(dst))
            else:
                kv_set(s, _copy_key(dst), "1")     # the copy in their Photos, on purpose
            touch_media(s, m)
            n += 1
        if n:
            log_event(s, "info", "copied", f"{user.name}: {n} item(s) copied to {to}")
    return n


_DUPS = threading.Lock()


_GENERIC_DIR = {"photos", "photo", "photohub", "mobilebackup", "dcim", "camera", "camera roll", "iphone", "android",
                "pictures", "picture", "images", "download", "downloads", "写真", "画像", "カメラ", "takeout"}
_DATE_DIR = re.compile(r"^\d{1,4}([-_年]\d{1,2}月?([-_]\d{1,2})?)?$")
_COPY_MARK = re.compile(r"(\(\d+\)|[ _-]\d$| copy| コピー)$", re.I)


def _handmade(p: Path, settings: Settings) -> bool:
    """In a folder someone made and named (「パリ編」), not a date / backup / camera folder."""
    parts = [x for x in p.parent.parts if x not in ("/", "")]
    if any(x.lower() == "mobilebackup" for x in parts):
        return False   # Synology Photos' phone backup: date folders under a device name
    tail = []
    for x in reversed(parts):   # the folders below the share / the person's Photos
        if x.lower() in ("photo", "photos", "homes", "volume1"):
            break
        tail.append(x)
    return any(x.lower() not in _GENERIC_DIR and not _DATE_DIR.match(x) for x in tail)


def _keep_score(p: Path, settings: Settings) -> tuple[int, int]:
    """Which copy to keep: one in a named folder, then one without 「(1)」「(2)」 in its name."""
    return (2 if _handmade(p, settings) else 0) + (0 if _COPY_MARK.search(p.stem) else 1), 0


def refresh_duplicates(db: Database, settings: Settings) -> int:
    """Ahead of time (daemon, and after a removal): the files that are an exact second copy of a photo
    in the library (same picture / same video streams; names and metadata may differ), kept in kv
    dup_files. Of each pair the copy in a named folder (and without 「(1)」 in its name) is kept; when
    both are in named folders, both stay (someone filed the photo in two places on purpose)."""
    if not _DUPS.acquire(blocking=False):
        return -1
    try:
        found = []
        with db.session() as s:
            rows = s.execute(select(ImportItem.original_path, Media.id, Media.content_hash, Media.nas_path, Media.owner_id)
                             .join(Media, Media.id == ImportItem.media_id)
                             .where(ImportItem.result == "duplicate", Media.status == "active")
                             .order_by(ImportItem.id.desc())).all()
            # every copy of each photo: the file PhotoHub knows it by, and the ones found again
            copies: dict[int, dict] = {}
            for path, mid, h, keep, owner in rows:
                c = copies.setdefault(mid, {"hash": h, "owner": owner, "known": keep, "paths": {keep}})
                p = Path(path)
                if p.is_absolute() and path not in c["paths"] and p.exists() and not kv_get(s, _copy_key(p)):
                    c["paths"].add(path)   # (a copy someone made on purpose - 家族に共有 / マイフォトにコピー - stays out)
            for mid, c in copies.items():
                paths = [Path(x) for x in c["paths"] if Path(x).exists()]
                if len(paths) < 2:
                    continue
                # keep the best one (named folder, no 「(1)」, then the one PhotoHub knows); copies filed in
                # named folders all stay (someone put the photo there on purpose)
                stay = max(paths, key=lambda x: (_keep_score(x, settings), str(x) == c["known"]))
                for x in paths:
                    if x == stay or _handmade(x, settings):
                        continue
                    found.append({"path": str(x), "media_id": mid, "hash": c["hash"], "keep": str(stay), "owner": c["owner"],
                                  "swap": str(x) == c["known"], "bytes": x.stat().st_size})
            kv_set(s, "dup_files2", json.dumps({"at": utcnow().isoformat(), "items": found}))
        return len(found)
    finally:
        _DUPS.release()


def duplicates_pending(db: Database) -> bool:
    with db.session() as s:
        return kv_get(s, "dup_files2") is None


def duplicate_files(db: Database, settings: Settings, user: User) -> list[dict]:
    """Files that are an exact second copy of a photo in the library, from the list made ahead of time
    (the first time ever, the list is started in the background and comes back empty)."""
    with db.session() as s:
        raw = kv_get(s, "dup_files2")
        if raw is None:
            threading.Thread(target=refresh_duplicates, args=(db, settings), daemon=True).start()
            return []
        out = []
        for d in json.loads(raw).get("items", []):
            p = Path(d["path"])
            m = s.get(Media, d["media_id"])
            if m is None or m.status != "active" or not can_see(s, user, m):
                continue
            mine = user.personal_root and p.is_relative_to(Path(user.personal_root))
            if not (mine or p.is_relative_to(settings.library)):
                continue  # only places this person may tidy
            if not p.exists():
                continue   # removed since the list was made (few files: cheap to check)
            out.append({"path": d["path"], "hash": d["hash"], "keep": d.get("keep") or m.nas_path, "bytes": d["bytes"]})
        return out


def _remove_files(db: Database, settings: Settings, user: User, todo: list[str]) -> tuple[int, int, str]:
    """Move each file to the trash folder; one file that fails (gone, no permission...) never stops the rest."""
    import logging
    day = f"{utcnow():%Y-%m-%d}"
    n = failed = 0
    first_error = ""
    with db.session() as s:
        swaps = {d["path"]: d for d in json.loads(kv_get(s, "dup_files2") or "{}").get("items", []) if d.get("swap")}
    done: list[str] = []
    for i, p in enumerate(todo):
        src = Path(p)
        try:
            if not src.exists():
                done.append(p)   # already gone: nothing to move
                continue
            item = swaps.get(p)
            if item is not None:
                with db.session() as s:
                    m = s.get(Media, item["media_id"])
                    if m is None or not Path(item["keep"]).exists():
                        raise FileNotFoundError(item["keep"])
                    if m.nas_path == p:   # the photo now lives in the copy that stays
                        m.nas_path = item["keep"]
                        set_location(s, m.id, "nas", "present", source_ref=item["keep"])
                        touch_media(s, m)
            dst = unique_in(settings.trash / day / "duplicates", src.name)
            if not _quick_move(src, dst):
                _move_with_sidecar(src, dst)
            done.append(p)
            n += 1
        except Exception as e:  # noqa: BLE001
            failed += 1
            if not first_error:
                first_error = f"{src.name}: {e}"
                logging.getLogger(__name__).exception("duplicate %s", p)
        if i % 200 == 199 or i == len(todo) - 1:
            with db.session() as s:   # progress, and the list without what is done
                kv_set(s, "dup_remove", json.dumps({"running": i < len(todo) - 1, "total": len(todo), "done": i + 1,
                                                    "moved": n, "failed": failed, "error": first_error}))
                raw = json.loads(kv_get(s, "dup_files2") or "{}")
                gone = set(done)
                raw["items"] = [d for d in raw.get("items", []) if d["path"] not in gone]
                kv_set(s, "dup_files2", json.dumps(raw))
    with db.session() as s:
        log_event(s, "info" if not failed else "warn", "duplicates_removed",
                  f"{user.name}: {n} duplicate file(s) moved to the trash folder" + (f", {failed} failed ({first_error})" if failed else ""))
    return n, failed, first_error


def remove_duplicates(db: Database, settings: Settings, user: User, paths: list[str]) -> dict:
    """A few files: at once. Many (thousands, tens of GB): in the background, with progress in kv dup_remove,
    so the request never runs into a time limit."""
    allowed = {d["path"] for d in duplicate_files(db, settings, user)}
    todo = [p for p in dict.fromkeys(paths) if p in allowed]
    if len(todo) <= 50:
        n, failed, err = _remove_files(db, settings, user, todo)
        return {"removed": n, "failed": failed, "error": err}
    with db.session() as s:
        st = json.loads(kv_get(s, "dup_remove") or "{}")
        if st.get("running"):
            return {"removed": 0, "started": 0, "running": True}
        kv_set(s, "dup_remove", json.dumps({"running": True, "total": len(todo), "done": 0, "moved": 0, "failed": 0, "error": ""}))
    threading.Thread(target=_remove_files, args=(db, settings, user, todo), daemon=True).start()
    return {"removed": 0, "started": len(todo)}


def restore_removed_duplicates(db: Database, settings: Settings) -> int:
    """Once, after the keep rule changed: every file the older rule moved to trash/<day>/duplicates goes
    back where it was (found by its name among the duplicates PhotoHub recorded, and checked to be the
    same picture), so the new rule can choose again - copies in named folders were moved by the old one."""
    from .hashing import content_hash, file_sha256, kind_of
    with db.session() as s:
        if kv_get(s, "dup_restored_v1"):
            return 0
        wanted: dict[str, list[tuple[str, str]]] = defaultdict(list)   # file name -> (original path, content hash)
        for path, h in s.execute(select(ImportItem.original_path, Media.content_hash).join(Media, Media.id == ImportItem.media_id)
                                 .where(ImportItem.result == "duplicate")).all():
            wanted[Path(path).name].append((path, h))
    n = 0
    for f in sorted(settings.trash.glob("*/duplicates/*")):
        if not f.is_file() or f.suffix.lower() == ".xmp":
            continue
        name = re.sub(r"\.\d+(\.[^.]+)$", r"\1", f.name)   # unique_in's 「a.1.jpg」 -> a.jpg
        cands = [(p, h) for p, h in wanted.get(name, []) if not Path(p).exists()]
        if not cands:
            continue
        try:
            kind = kind_of(f) or "photo"
            ch = content_hash(f, kind, file_sha256(f), settings.ffmpeg_bin)
        except Exception:  # noqa: BLE001
            continue
        for p, h in cands:
            if h == ch:
                try:
                    _move_with_sidecar(f, Path(p))
                    n += 1
                except Exception:  # noqa: BLE001
                    pass
                break
    with db.session() as s:
        kv_set(s, "dup_restored_v1", str(n))
        old = s.get(KV, "dup_files2")
        if old is not None:
            s.delete(old)   # look again with the new rule
        if n:
            log_event(s, "info", "duplicates_restored", f"{n} file(s) moved by the old duplicate rule put back")
    return n


def removing_duplicates(db: Database) -> dict | None:
    with db.session() as s:
        st = json.loads(kv_get(s, "dup_remove") or "{}")
    return st or None


def sharpness(thumb: Path) -> float | None:
    """Variance of the Laplacian on the grey thumbnail: low = blurry."""
    try:
        import numpy as np
        from PIL import Image
        with Image.open(thumb) as im:
            g = np.asarray(im.convert("L"), dtype=np.float32)
    except Exception:  # noqa: BLE001
        return None
    if g.shape[0] < 3 or g.shape[1] < 3:
        return None
    lap = (g[1:-1, :-2] + g[1:-1, 2:] + g[:-2, 1:-1] + g[2:, 1:-1] - 4 * g[1:-1, 1:-1])
    return float(lap.var())


def measure_sharpness(db: Database, settings: Settings, seconds: float = 300) -> int:
    """Ahead of time (daemon, after the thumbnails): measure the photos not measured yet, newest first,
    for at most `seconds`, so 「ぼやけた写真」 opens at once instead of measuring while the person waits."""
    import time
    from . import derivatives
    end = time.monotonic() + seconds
    n = 0
    while time.monotonic() < end:
        with db.session() as s:
            todo = list(s.execute(
                select(Media.id, Media.content_hash).outerjoin(KV, KV.key == _sharp_key())
                .where(Media.status == "active", Media.kind == "photo", KV.key.is_(None))
                .order_by(Media.taken_at.desc()).limit(200)).all())
        if not todo:
            break
        done_any = False
        with db.session() as s:
            for mid, h in todo:
                t = derivatives.path_for(settings, "thumb", h)
                if not t.exists():
                    continue   # no thumbnail yet: measured on a later run, once it is made
                val = sharpness(t)
                kv_set(s, f"sharp:{mid}", "" if val is None else f"{val:.1f}")
                n += 1
                done_any = True
                if time.monotonic() >= end:
                    break
        if not done_any:
            break
    return n


def _sharp_key():  # noqa: ANN202 - the kv key of a photo's sharpness, as SQL
    from sqlalchemy import String, cast
    return "sharp:" + cast(Media.id, String)


def blurry(db: Database, settings: Settings, user: User, limit: int = 300, threshold: float = 60.0) -> list[dict]:
    """Photos whose thumbnail is very soft: one query over the values measured ahead of time."""
    from sqlalchemy import Float, cast
    from .db import visible_clause
    with db.session() as s:
        rows = s.execute(select(Media.content_hash, KV.value).join(KV, KV.key == _sharp_key())
                         .where(Media.status == "active", Media.kind == "photo", visible_clause(user),
                                KV.value != "", cast(KV.value, Float) < threshold)
                         .order_by(Media.taken_at.desc()).limit(limit)).all()
    return [{"hash": h, "sharpness": float(v)} for h, v in rows]


def remove_system_thumbs(db: Database, settings: Settings) -> int:
    """Synology's own thumbnails that older versions imported as photos (from @eaDir in the Drive
    folder): move them to the trash (restorable for 30 days) and stop them coming back."""
    from .db import Upload
    from .incoming import is_system_thumb
    with db.session() as s:
        junk = [m.content_hash for m in s.execute(select(Media).where(Media.status == "active")).scalars()
                if is_system_thumb(Path(m.nas_path))]
        for up in s.execute(select(Upload).where(Upload.processed_at.is_(None))).scalars():
            if is_system_thumb(Path(up.path)):
                up.processed_at = utcnow()
    n = trash(db, settings, None, junk) if junk else 0
    if n:
        with db.session() as s:
            log_event(s, "info", "system_thumbs_removed", f"{n} Synology thumbnail file(s) were not photos: moved to the trash")
    return n
