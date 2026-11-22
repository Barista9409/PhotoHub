"""Albums managed from the app, kept the same as Synology Photos for people who linked their login.

PhotoHub's own album table stays the source the phones read. For linked people:
- pull: their Synology Photos albums are read every 15 minutes (and on demand) and mirrored here
  (photos are matched by file name + size); albums shared with others become family albums.
- push: creating / renaming / deleting an album and adding / removing photos in the app is sent to
  Synology Photos right away when the album came from there or the person is linked.
The Synology Photos API is undocumented: every call is best-effort and failures only get logged.
"""
from __future__ import annotations

from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import Album, AlbumItem, Database, Media, User, can_see, kv_get, kv_set, log_event, next_rev, touch_media

LIST = 100


class AlbumError(ValueError):
    pass


def _may_edit(user: User, a: Album) -> bool:
    return a.owner_id is None or a.owner_id == user.id


def _bump(s, a: Album) -> None:  # noqa: ANN001
    a.rev = next_rev(s)


def _media(s, user: User, hashes: list[str]) -> list[Media]:  # noqa: ANN001
    ms = s.execute(select(Media).where(Media.content_hash.in_(hashes[:5000]), Media.status == "active")).scalars()
    return [m for m in ms if can_see(s, user, m)]


# ---- app actions -------------------------------------------------------------------------------
def create(db: Database, settings: Settings, user: User, name: str, hashes: list[str], shared: bool) -> int:
    name = name.strip()[:100]
    if not name:
        raise AlbumError("name is empty")
    with db.session() as s:
        a = Album(name=name, origin="manual", owner_id=None if shared else user.id)
        s.add(a)
        s.flush()
        for m in _media(s, user, hashes):
            s.add(AlbumItem(album_id=a.id, media_id=m.id))
            touch_media(s, m)
        _bump(s, a)
        aid = a.id
    _push(db, settings, user, aid, add=hashes, create=True)
    return aid


def rename(db: Database, settings: Settings, user: User, album_id: int, name: str) -> None:
    with db.session() as s:
        a = s.get(Album, album_id)
        if a is None or not _may_edit(user, a):
            raise AlbumError("album not found")
        a.name = name.strip()[:100] or a.name
        _bump(s, a)
        for ai in s.execute(select(AlbumItem).where(AlbumItem.album_id == a.id)).scalars():
            touch_media(s, s.get(Media, ai.media_id))
    _push(db, settings, user, album_id, rename=True)


def delete(db: Database, settings: Settings, user: User, album_id: int) -> None:
    with db.session() as s:
        a = s.get(Album, album_id)
        if a is None or not _may_edit(user, a):
            raise AlbumError("album not found")
        syno = a.synology_album_id
        for ai in list(s.execute(select(AlbumItem).where(AlbumItem.album_id == a.id)).scalars()):
            m = s.get(Media, ai.media_id)
            s.delete(ai)
            if m is not None:
                touch_media(s, m)
        s.flush()  # items first (foreign key)
        s.delete(a)
    if syno:
        _synology(db, settings, user, lambda ses: ses.call("SYNO.Foto.Browse.Album", "delete", 1, id=[syno]))


def change(db: Database, settings: Settings, user: User, album_id: int, add: list[str], remove: list[str]) -> int:
    n = 0
    with db.session() as s:
        a = s.get(Album, album_id)
        if a is None or not _may_edit(user, a):
            raise AlbumError("album not found")
        for m in _media(s, user, add):
            if s.get(AlbumItem, (a.id, m.id)) is None:
                s.add(AlbumItem(album_id=a.id, media_id=m.id))
                touch_media(s, m)
                n += 1
        for m in _media(s, user, remove):
            ai = s.get(AlbumItem, (a.id, m.id))
            if ai is not None:
                s.delete(ai)
                touch_media(s, m)
                n += 1
        _bump(s, a)
    _push(db, settings, user, album_id, add=add, remove=remove)
    return n


# ---- Synology Photos ---------------------------------------------------------------------------
def _synology(db: Database, settings: Settings, user: User, fn):  # noqa: ANN001, ANN202
    """Run fn(session) as this person in Synology Photos; None when not linked or it failed."""
    if not user.dsm_secret:
        return None
    from .photos_link import Session
    try:
        with Session(settings, user) as ses:
            return fn(ses)
    except Exception as e:  # noqa: BLE001 - undocumented API: never break the app action
        import logging
        logging.getLogger(__name__).warning("synology album: %r", e, exc_info=True)
        with db.session() as s:
            log_event(s, "warn", "synology_album", f"{user.name}: {e}")
        return None


def _list_all(ses, api: str, method: str, **params) -> list[dict]:  # noqa: ANN001, ANN003
    out, offset = [], 0
    while True:
        d = ses.call(api, method, 1, offset=offset, limit=LIST, **params)
        page = d.get("list") or []
        out += page
        if len(page) < LIST:
            return out
        offset += LIST


def _item_ids(db: Database, settings: Settings, user: User, ses, media: list[Media]) -> dict[int, int]:  # noqa: ANN001
    """Synology Photos item id per media id: remembered from pulls, else looked up by folder."""
    out: dict[int, int] = {}
    by_folder: dict[tuple[bool, str], list[Media]] = {}
    with db.session() as s:
        for m in media:
            v = kv_get(s, f"synoitem:{user.id}:{m.id}")
            if v:
                out[m.id] = int(v)
                continue
            p = Path(m.nas_path)
            team = m.owner_id is None
            root = settings.library if team else Path(user.personal_root or "/nonexistent")
            try:
                rel = "/" + p.parent.relative_to(root).as_posix()
            except ValueError:
                continue
            by_folder.setdefault((team, rel.rstrip("/.") or "/"), []).append(m)
    for (team, rel), ms in by_folder.items():
        pre = "SYNO.FotoTeam" if team else "SYNO.Foto"
        try:
            f = (ses.call(f"{pre}.Browse.Folder", "get", 1, name=rel).get("folder") or {}).get("id")
            names = {it.get("filename"): it.get("id") for it in _list_all(ses, f"{pre}.Browse.Item", "list", folder_id=f)} if f else {}
        except Exception:  # noqa: BLE001
            names = {}
        with db.session() as s:
            for m in ms:
                iid = names.get(Path(m.nas_path).name)
                if iid:
                    out[m.id] = iid
                    kv_set(s, f"synoitem:{user.id}:{m.id}", str(iid))
    return out


def _push(db: Database, settings: Settings, user: User, album_id: int, add: list[str] = (), remove: list[str] = (),
          create: bool = False, rename: bool = False) -> None:
    if not user.dsm_secret:
        return
    with db.session() as s:
        a = s.get(Album, album_id)
        if a is None or a.origin not in ("manual", "synology"):
            return
        name, syno = a.name, a.synology_album_id
        adds = _media(s, user, list(add))
        rems = _media(s, user, list(remove))
        for x in adds + rems:
            s.expunge(x)

    def run(ses):  # noqa: ANN001, ANN202
        nonlocal syno
        ids_add = list(_item_ids(db, settings, user, ses, adds).values())
        ids_rem = list(_item_ids(db, settings, user, ses, rems).values())
        if syno is None:
            if not (create or ids_add):
                return
            syno = (ses.call("SYNO.Foto.Browse.NormalAlbum", "create", 1, name=name, item=ids_add).get("album") or {}).get("id")
            with db.session() as s:
                s.get(Album, album_id).synology_album_id = syno
            return
        if rename:
            ses.call("SYNO.Foto.Browse.Album", "set_name", 1, id=syno, name=name)
        if ids_add:
            ses.call("SYNO.Foto.Browse.NormalAlbum", "add_item", 1, id=syno, item=ids_add)
        if ids_rem:
            ses.call("SYNO.Foto.Browse.NormalAlbum", "remove_item", 1, id=syno, item=ids_rem)
    _synology(db, settings, user, run)


def pull(db: Database, settings: Settings, user: User) -> dict:
    """Mirror this person's Synology Photos albums into PhotoHub. Returns counts."""
    stats = {"albums": 0, "matched": 0, "unmatched": 0}

    def run(ses):  # noqa: ANN001, ANN202
        albums = _list_all(ses, "SYNO.Foto.Browse.Album", "list")
        with db.session() as s:
            index: dict[tuple[str, int], Media] = {}
            for m in s.execute(select(Media).where(Media.status == "active")).scalars():
                if can_see(s, user, m):
                    index[(Path(m.nas_path).name.lower(), m.bytes)] = m
            seen_ids = set()
            for alb in albums:
                aid = alb.get("id")
                if aid is None:
                    continue
                seen_ids.add(aid)
                shared = bool(alb.get("shared") or alb.get("is_shared") or alb.get("sharing_info"))
                a = s.execute(select(Album).where(Album.synology_album_id == aid,
                                                  Album.owner_id.in_([user.id]) | Album.owner_id.is_(None))).scalars().first()
                if a is None:
                    a = Album(name=alb.get("name") or f"アルバム {aid}", origin="synology",
                              owner_id=None if shared else user.id, synology_album_id=aid)
                    s.add(a)
                    s.flush()
                a.name = alb.get("name") or a.name
                want: set[int] = set()
                for it in _list_all(ses, "SYNO.Foto.Browse.Item", "list", album_id=aid):
                    m = index.get(((it.get("filename") or "").lower(), int(it.get("filesize") or 0)))
                    if m is None:
                        stats["unmatched"] += 1
                        continue
                    want.add(m.id)
                    kv_set(s, f"synoitem:{user.id}:{m.id}", str(it.get("id")))
                have = {ai.media_id: ai for ai in s.execute(select(AlbumItem).where(AlbumItem.album_id == a.id)).scalars()}
                for mid in want - set(have):
                    s.add(AlbumItem(album_id=a.id, media_id=mid, synced=True))
                    touch_media(s, s.get(Media, mid))
                for mid in set(have) - want:
                    s.delete(have[mid])
                    touch_media(s, s.get(Media, mid))
                _bump(s, a)
                stats["albums"] += 1
                stats["matched"] += len(want)
            # albums deleted in Synology Photos
            for a in s.execute(select(Album).where(Album.origin == "synology", Album.owner_id == user.id)).scalars():
                if a.synology_album_id not in seen_ids:
                    for ai in list(s.execute(select(AlbumItem).where(AlbumItem.album_id == a.id)).scalars()):
                        touch_media(s, s.get(Media, ai.media_id))
                        s.delete(ai)
                    s.flush()  # items first (foreign key)
                    s.delete(a)
        return stats

    r = _synology(db, settings, user, run)
    if r is None and user.dsm_secret:
        stats["error"] = "Synology Photosのアルバムを読めませんでした（記録を確認）"
    return stats


def pull_all(db: Database, settings: Settings) -> None:
    from .users import all_users
    for u in all_users(db):
        if u.dsm_secret:
            pull(db, settings, u)
