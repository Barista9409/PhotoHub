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
from datetime import timedelta
from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import Database, ImportItem, Media, User, can_see, kv_get, kv_set, log_event, set_location, touch_media, utcnow
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


def trash(db: Database, settings: Settings, user: User | None, hashes: list[str]) -> int:
    n = 0
    with db.session() as s:
        for m in s.execute(select(Media).where(Media.content_hash.in_(hashes[:5000]), Media.status == "active")).scalars():
            if not _may_change(s, user, m):
                continue
            src = Path(m.nas_path)
            info = {"from": str(src), "by": user.name if user else "PhotoHub", "owner": m.owner_id}
            if src.exists():
                dst = unique_in(_bin(settings) / f"{m.id}", src.name)
                _move_with_sidecar(src, dst)
                m.nas_path = str(dst)
            m.status = "trashed"
            kv_set(s, f"trash:{m.id}", json.dumps({**info, "at": utcnow().isoformat()}))
            set_location(s, m.id, "nas", "removed")
            touch_media(s, m)
            n += 1
        if n:
            log_event(s, "info", "trash", f"{user.name if user else 'PhotoHub'}: {n} item(s) moved to the trash")
    return n


def trashed(db: Database, settings: Settings, user: User) -> list[dict]:
    out = []
    with db.session() as s:
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
                        "by": info.get("by"), "days_left": days_left, "space": "shared" if m.owner_id is None else "mine"})
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
            if src.exists() and back.name:
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


def duplicate_files(db: Database, settings: Settings, user: User) -> list[dict]:
    """Files that are an exact second copy of a photo in the library (found by the folder checks)."""
    out, seen = [], set()
    with db.session() as s:
        rows = s.execute(select(ImportItem, Media).join(Media, Media.id == ImportItem.media_id)
                         .where(ImportItem.result == "duplicate", Media.status == "active")
                         .order_by(ImportItem.id.desc())).all()
        for item, m in rows:
            p = Path(item.original_path)
            if str(p) in seen or not p.is_absolute() or not p.exists() or str(p) == m.nas_path:
                continue
            if kv_get(s, _copy_key(p)):
                continue   # a copy someone made on purpose (家族に共有 / マイフォトにコピー)
            seen.add(str(p))
            if not can_see(s, user, m):
                continue
            mine = user.personal_root and p.is_relative_to(Path(user.personal_root))
            if not (mine or p.is_relative_to(settings.library)):
                continue  # only places this person may tidy
            out.append({"path": str(p), "hash": m.content_hash, "keep": m.nas_path, "bytes": p.stat().st_size})
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


def blurry(db: Database, settings: Settings, user: User, limit: int = 300, threshold: float = 60.0) -> list[dict]:
    """Photos whose thumbnail is very soft (cached per photo in kv)."""
    from . import derivatives
    from .db import visible_clause
    out = []
    with db.session() as s:
        for m in s.execute(select(Media).where(Media.status == "active", Media.kind == "photo", visible_clause(user))
                           .order_by(Media.taken_at.desc())).scalars():
            key = f"sharp:{m.id}"
            v = kv_get(s, key)
            if v is None:
                t = derivatives.path_for(settings, "thumb", m.content_hash)
                if not t.exists():
                    continue
                val = sharpness(t)
                kv_set(s, key, "" if val is None else f"{val:.1f}")
                v = "" if val is None else f"{val:.1f}"
            if v and float(v) < threshold:
                out.append({"hash": m.content_hash, "sharpness": float(v)})
                if len(out) >= limit:
                    break
    return out


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
