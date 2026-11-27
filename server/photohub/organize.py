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


def refresh_duplicates(db: Database, settings: Settings) -> int:
    """Ahead of time (daemon, and after a removal): the files that are an exact second copy of a photo
    in the library, kept in kv dup_files. Looking at every past import's duplicate on disk takes long
    on a big library, so 「まったく同じ写真」 reads this list instead of looking while people wait."""
    if not _DUPS.acquire(blocking=False):
        return -1
    try:
        found, seen = [], set()
        with db.session() as s:
            rows = s.execute(select(ImportItem.original_path, Media.id, Media.content_hash, Media.nas_path, Media.owner_id)
                             .join(Media, Media.id == ImportItem.media_id)
                             .where(ImportItem.result == "duplicate", Media.status == "active")
                             .order_by(ImportItem.id.desc())).all()
            for path, mid, h, keep, owner in rows:
                p = Path(path)
                if path in seen or not p.is_absolute() or path == keep:
                    continue
                seen.add(path)
                if not p.exists() or kv_get(s, _copy_key(p)):
                    continue   # gone already, or a copy someone made on purpose (家族に共有 / マイフォトにコピー)
                found.append({"path": path, "media_id": mid, "hash": h, "keep": keep, "owner": owner,
                              "bytes": p.stat().st_size})
            kv_set(s, "dup_files", json.dumps({"at": utcnow().isoformat(), "items": found}))
        return len(found)
    finally:
        _DUPS.release()


def duplicates_pending(db: Database) -> bool:
    with db.session() as s:
        return kv_get(s, "dup_files") is None


def duplicate_files(db: Database, settings: Settings, user: User) -> list[dict]:
    """Files that are an exact second copy of a photo in the library, from the list made ahead of time
    (the first time ever, the list is started in the background and comes back empty)."""
    with db.session() as s:
        raw = kv_get(s, "dup_files")
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
            out.append({"path": d["path"], "hash": d["hash"], "keep": m.nas_path, "bytes": d["bytes"]})
        return out


def remove_duplicates(db: Database, settings: Settings, user: User, paths: list[str]) -> int:
    allowed = {d["path"] for d in duplicate_files(db, settings, user)}
    n = 0
    day = f"{utcnow():%Y-%m-%d}"
    for p in paths:
        if p not in allowed:
            continue
        src = Path(p)
        _move_with_sidecar(src, unique_in(settings.trash / day / "duplicates", src.name))
        n += 1
    if n:
        with db.session() as s:
            log_event(s, "info", "duplicates_removed", f"{user.name}: {n} duplicate file(s) moved to the trash folder")
            raw = json.loads(kv_get(s, "dup_files") or "{}")
            gone = set(paths)
            raw["items"] = [d for d in raw.get("items", []) if d["path"] not in gone]
            kv_set(s, "dup_files", json.dumps(raw))
    return n


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
