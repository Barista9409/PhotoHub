"""Albums PhotoHub makes by itself, kept up to date every night:

- 「ふりかえり」 (origin "digest"): the best photos of each past month, for each person.
  Screenshots, the losers of similar-photo groups and very small pictures are left out; the rest is
  spread over the days of the month (a busy day does not fill the album), larger pictures first.
- 「旅行」 (origin "trip"): days in a row away from home (another prefecture than where most photos
  are taken), named after the month and the place.
- 「フォルダ」 (origin "folder"): every folder of the family's shared space that people made by hand
  (not the year / month folders PhotoHub or Synology make), as an album.

An automatic album is found again by its key (not its name), so people may rename it; deleting it
for good is remembered (it does not come back).
"""
from __future__ import annotations

import re
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import (Album, AlbumItem, Database, KV, Media, SimilarMember, User, kv_get, kv_set, next_rev, touch_media,
                 utcnow)

AUTO_ORIGINS = ("digest", "trip", "folder", "person", "place", "growth", "favorite")
PLACE_MIN_PHOTOS = 20
PLACE_MAX = 30
PERSON_MIN_PHOTOS = 5
PERSON_MAX = 100
DIGEST_MAX = 40
DIGEST_PER_DAY = 4
TRIP_MIN_PHOTOS = 10
TRIP_MAX_DAYS = 21
_SHOT = re.compile(r"(screenshot|スクリーンショット|screen_shot|scrn)", re.I)
_DATE_FOLDER = re.compile(r"^(\d{4}([-_年]\d{1,2}月?)?|\d{4}[-_]\d{2}[-_]\d{2}|MobileBackup|#recycle|@eaDir|Takeout.*)$", re.I)


def gone_key(origin: str, owner: int | None, key: str) -> str:
    import hashlib
    return f"autogone:{origin}:{owner}:{hashlib.sha1(key.encode()).hexdigest()[:16]}"


def _local(m: Media, tz_default: int) -> datetime:
    return m.taken_at + timedelta(minutes=m.tz_offset if m.tz_offset is not None else tz_default)


def _upsert(s, origin: str, owner: int | None, key: str, name: str, media_ids: list[int]) -> bool:  # noqa: ANN001
    """Make or refresh the album for `key`; False when it was deleted on purpose."""
    if kv_get(s, gone_key(origin, owner, key)):
        return False
    ref = f"auto:{origin}:{owner}:{key}"
    aid = kv_get(s, ref)
    a = s.get(Album, int(aid)) if aid else None
    if a is None:
        same = s.execute(select(Album).where(Album.name == name, Album.origin == origin,
                                             Album.owner_id.is_(None) if owner is None else Album.owner_id == owner)).scalars().first()
        a = same or Album(name=name, origin=origin, owner_id=owner)
        if same is None:
            s.add(a)
            s.flush()
        kv_set(s, ref, str(a.id))
        kv_set(s, f"autoref:{a.id}", f"{origin}|{owner}|{key}")   # deleting it later is remembered
    want = set(media_ids)
    have = {ai.media_id: ai for ai in s.execute(select(AlbumItem).where(AlbumItem.album_id == a.id)).scalars()}
    changed = False
    for mid in want - set(have):
        s.add(AlbumItem(album_id=a.id, media_id=mid))
        touch_media(s, s.get(Media, mid))
        changed = True
    for mid in set(have) - want:
        s.delete(have[mid])
        touch_media(s, s.get(Media, mid))
        changed = True
    if changed:
        a.rev = next_rev(s)
    return True


def _usable(m: Media) -> bool:
    name = Path(m.nas_path).name
    if _SHOT.search(name) or name.lower().endswith(".png"):
        return False
    return m.kind == "video" or (m.width or 0) * (m.height or 0) >= 640 * 480


def digest(db: Database, settings: Settings, months_back: int = 3) -> int:
    """「YYYY年M月のふりかえり」 for the last `months_back` finished months, per person."""
    n = 0
    tz = settings.default_tz_minutes
    today = utcnow() + timedelta(minutes=tz)
    first = today.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    months = []
    y, mo = first.year, first.month
    for _ in range(months_back):
        mo -= 1
        if mo == 0:
            y, mo = y - 1, 12
        months.append((y, mo))
    with db.session() as s:
        losers = set(s.execute(select(SimilarMember.media_id).where(SimilarMember.keep.is_(False))).scalars())
        owners = [u.id for u in s.execute(select(User)).scalars()]
        for owner in owners:
            # the person's own photos and the family's shared ones
            rows = list(s.execute(select(Media).where(Media.status == "active", Media.taken_at.is_not(None),
                                                      (Media.owner_id == owner) | Media.owner_id.is_(None))).scalars())
            by_month: dict[tuple[int, int], list[Media]] = defaultdict(list)
            for m in rows:
                t = _local(m, tz)
                if (t.year, t.month) in months and m.id not in losers and _usable(m):
                    by_month[(t.year, t.month)].append(m)
            for (yy, mm), ms in by_month.items():
                if len(ms) < 5:
                    continue
                days: dict[int, list[Media]] = defaultdict(list)
                for m in ms:
                    days[_local(m, tz).day].append(m)
                per_day = max(1, min(DIGEST_PER_DAY, DIGEST_MAX // max(1, len(days)) + 1))
                picked: list[Media] = []
                for d in sorted(days):
                    best = sorted(days[d], key=lambda m: -((m.width or 0) * (m.height or 0)))
                    picked += best[:per_day]
                picked = sorted(picked, key=lambda m: m.taken_at)[:DIGEST_MAX]
                if _upsert(s, "digest", owner, f"{yy}-{mm:02d}", f"{yy}年{mm}月のふりかえり", [m.id for m in picked]):
                    n += 1
    return n


def _pref(place: str | None) -> str:
    return (place or "").split(" ")[0]


def trips(db: Database, settings: Settings) -> int:
    """Days in a row in another prefecture than home -> 「YYYY年M月 <place>」, per owner (and the shared space)."""
    n = 0
    tz = settings.default_tz_minutes
    with db.session() as s:
        places = {int(k.key[6:]): k.value for k in s.execute(select(KV).where(KV.key.like("place:%"))).scalars()
                  if k.key[6:].isdigit()}
        # one album per person, from their own photos and the family's shared ones (a trip the
        # family put in the shared space must not show up twice)
        owners = [u.id for u in s.execute(select(User)).scalars()]
        for owner in owners:
            q = select(Media).where(Media.status == "active", Media.taken_at.is_not(None),
                                    (Media.owner_id == owner) | Media.owner_id.is_(None))
            ms = [m for m in s.execute(q).scalars() if m.id in places]
            if not ms:
                continue
            home = Counter(_pref(places[m.id]) for m in ms).most_common(1)[0][0]
            away = sorted((m for m in ms if _pref(places[m.id]) and _pref(places[m.id]) != home), key=lambda m: m.taken_at)
            run: list[Media] = []

            def flush(run: list[Media]) -> None:
                nonlocal n
                if len(run) < TRIP_MIN_PHOTOS:
                    return
                start, end = _local(run[0], tz), _local(run[-1], tz)
                if (end - start).days > TRIP_MAX_DAYS:
                    return
                where = Counter(places[m.id] for m in run).most_common(1)[0][0]
                pref = Counter(_pref(places[m.id]) for m in run).most_common(1)[0][0]
                name = f"{start.year}年{start.month}月 {where if len(set(_pref(places[m.id]) for m in run)) == 1 else pref}"
                # all photos of those days (also the ones without a position)
                lo, hi = run[0].taken_at - timedelta(hours=2), run[-1].taken_at + timedelta(hours=2)
                ids = [m.id for m in s.execute(q.where(Media.taken_at.between(lo, hi))).scalars()]
                if _upsert(s, "trip", owner, f"{start:%Y-%m-%d}", name, ids):
                    n += 1

            for m in away:
                if run and (m.taken_at - run[-1].taken_at) > timedelta(hours=36):
                    flush(run)
                    run = []
                run.append(m)
            flush(run)
    return n


_GENERIC_FOLDER = {"写真", "画像", "photo", "photos", "picture", "pictures", "image", "images", "dcim", "camera",
                   "100apple", "my pictures", "マイピクチャ", "ピクチャ", "動画", "video", "videos", "movies"}


def folders(db: Database, settings: Settings) -> int:
    """Hand-made folders of the shared space -> shared albums named after the folder. One album per
    folder people made (「2026_ヨーロッパ旅行」), with the photos of its subfolders too; date folders and
    plain containers (「写真」「DCIM」...) are looked through."""
    n = 0
    root = settings.library
    with db.session() as s:
        by_folder: dict[str, list[int]] = defaultdict(list)
        for m in s.execute(select(Media).where(Media.status == "active", Media.owner_id.is_(None))).scalars():
            try:
                rel = Path(m.nas_path).parent.relative_to(root)
            except ValueError:
                continue
            parts = [p for p in rel.parts if not _DATE_FOLDER.match(p) and p.strip().lower() not in _GENERIC_FOLDER]
            if not parts:
                continue
            by_folder[parts[0]].append(m.id)
        made = set()
        for key, ids in by_folder.items():
            if len(ids) < 3:
                continue
            made.add(key)
            if _upsert(s, "folder", None, key, key, ids):
                n += 1
        # folder albums an older version made per subfolder: gone (their photos are in the parent's album)
        from .db import KV
        for k in list(s.execute(select(KV).where(KV.key.like("auto:folder:None:%"))).scalars()):
            key = k.key[len("auto:folder:None:"):]
            if key in made or not k.value.isdigit():
                continue
            a = s.get(Album, int(k.value))
            if a is not None and a.origin == "folder":
                for ai in list(s.execute(select(AlbumItem).where(AlbumItem.album_id == a.id)).scalars()):
                    touch_media(s, s.get(Media, ai.media_id))
                    s.delete(ai)
                s.flush()
                ref = s.get(KV, f"autoref:{a.id}")
                if ref is not None:
                    s.delete(ref)
                s.delete(a)
            s.delete(k)
    return n


def people(db: Database, settings: Settings) -> dict:
    """「人物」 albums from Synology Photos' own face grouping (its 人物 feature), for everyone who
    linked their Synology Photos login. Photos are matched by file name and size, like album pulls.
    Synology Photos' name for the person is used when there is one (else 「人物 1」...)."""
    from .albums import _list_all
    from .db import visible_clause
    from .photos_link import PhotosError, Session
    made, notes = 0, []
    with db.session() as s:
        users = [u for u in s.execute(select(User)).scalars() if u.dsm_secret]
        for u in users:
            s.expunge(u)
    def one_space(ses, u: User, list_api: str, item_api: str, owner: int | None, prefix: str, label: str) -> None:  # noqa: ANN001
        nonlocal made
        persons, last = None, None
        for params in ({"show_more": True, "show_hidden": False}, {}):
            try:
                persons = _list_all(ses, list_api, "list", **params)
                break
            except PhotosError as e:
                last = e
        if persons is None:
            notes.append(f"{u.name}（{label}）: 人物の一覧を読めません（Synology Photosの顔認識がオフかもしれません）: {last}")
            return
        persons = sorted(persons, key=lambda p: -int(p.get("item_count") or 0))[:PERSON_MAX]
        with db.session() as s:
            index = {(Path(m.nas_path).name.lower(), m.bytes): m.id for m in
                     s.execute(select(Media).where(Media.status == "active", visible_clause(u))).scalars()}
        for n, p in enumerate(persons, 1):
            pid = p.get("id")
            if pid is None or int(p.get("item_count") or 0) < PERSON_MIN_PHOTOS:
                continue
            items = _list_all(ses, item_api, "list", person_id=pid)
            ids = [index[k] for k in (((it.get("filename") or "").lower(), int(it.get("filesize") or 0)) for it in items)
                   if k in index]
            if len(ids) < PERSON_MIN_PHOTOS:
                continue
            name = (p.get("name") or "").strip()
            key = f"{prefix}:{pid}"
            with db.session() as s:
                if _upsert(s, "person", owner, key, name or f"人物 {n}" + ("（家族）" if owner is None else ""), ids):
                    made += 1
                    # Synology Photos gave the person a name later: use it (unless renamed in PhotoHub)
                    a = s.get(Album, int(kv_get(s, f"auto:person:{owner}:{key}") or 0))
                    if a is not None and name and a.name.startswith("人物 ") and a.name != name:
                        a.name = name
                        a.rev = next_rev(s)

    team_done = False
    # admins first: the shared space's people are read once, through the first admin who linked
    for u in sorted(users, key=lambda x: not x.is_admin):
        try:
            with Session(settings, u) as ses:
                one_space(ses, u, "SYNO.Foto.Browse.Person", "SYNO.Foto.Browse.Item", u.id, "syno", "個人スペース")
                if not team_done:
                    try:
                        one_space(ses, u, "SYNO.FotoTeam.Browse.Person", "SYNO.FotoTeam.Browse.Item", None, "team", "共有スペース")
                        team_done = True
                    except Exception as e:  # noqa: BLE001
                        notes.append(f"{u.name}（共有スペース）: {e}")
        except Exception as e:  # noqa: BLE001 - undocumented API: never stop the other albums
            notes.append(f"{u.name}: {e}")
    if notes:
        from .db import log_event
        with db.session() as s:
            for t in notes:
                log_event(s, "warn", "people_albums", t[:500])
    return {"made": made, "notes": notes}


def places(db: Database, settings: Settings) -> int:
    """「場所」: the towns where the most photos were taken (each ≥ 20 photos, 30 towns at most),
    per owner and for the shared space - every visit, across the years."""
    n = 0
    with db.session() as s:
        where = {int(k.key[6:]): k.value for k in s.execute(select(KV).where(KV.key.like("place:%"))).scalars()
                 if k.key[6:].isdigit() and k.value}
        owners = [u.id for u in s.execute(select(User)).scalars()]
        for owner in owners:   # as for trips: one album per person, own + shared photos
            q = select(Media.id).where(Media.status == "active", (Media.owner_id == owner) | Media.owner_id.is_(None))
            by_place: dict[str, list[int]] = defaultdict(list)
            for mid in s.execute(q).scalars():
                if mid in where:
                    by_place[where[mid]].append(mid)
            top = sorted(by_place.items(), key=lambda kv: -len(kv[1]))[:PLACE_MAX]
            for name, ids in top:
                if len(ids) >= PLACE_MIN_PHOTOS and _upsert(s, "place", owner, name, name, ids):
                    n += 1
    return n


def growth(db: Database, settings: Settings) -> int:
    """「はると 1歳」: for a person album with a birthday (set in the app), one album per year of age."""
    from .db import kv_get
    n = 0
    tz = settings.default_tz_minutes
    with db.session() as s:
        for a in s.execute(select(Album).where(Album.origin == "person")).scalars():
            bd = kv_get(s, f"birthday:{a.id}")
            if not bd:
                continue
            try:
                born = datetime.strptime(bd, "%Y-%m-%d")
            except ValueError:
                continue
            ages: dict[int, list[int]] = defaultdict(list)
            for m in s.execute(select(Media).join(AlbumItem, AlbumItem.media_id == Media.id)
                               .where(AlbumItem.album_id == a.id, Media.status == "active")).scalars():
                if m.taken_at is None:
                    continue
                t = _local(m, tz)
                age = t.year - born.year - ((t.month, t.day) < (born.month, born.day))
                if age >= 0:
                    ages[age].append(m.id)
            for age, ids in ages.items():
                if _upsert(s, "growth", a.owner_id, f"{a.id}:{age}", f"{a.name} {age}歳", ids):
                    n += 1
    return n


def favorites(db: Database, settings: Settings) -> int:
    """「お気に入り」 for each person (their ♡), and 「家族のお気に入り」 from the shared space."""
    from .db import Favorite
    n = 0
    with _FAV, db.session() as s:
        favs = list(s.execute(select(Favorite.user_id, Favorite.media_id, Media.owner_id)
                              .join(Media, Media.id == Favorite.media_id).where(Media.status == "active")).all())
        mine: dict[int, list[int]] = defaultdict(list)
        for uid in s.execute(select(User.id)).scalars():
            mine[uid]   # a person who took every ♡ off gets an empty album, not a stale one
        family: set[int] = set()
        shared_ok = {mid for (mid,) in s.execute(select(Media.id).where(Media.family_shared.is_(True)))} if favs else set()
        for uid, mid, owner in favs:
            mine[uid].append(mid)
            if owner is None or mid in shared_ok:
                family.add(mid)   # only the shared space: a personal photo stays personal
        for uid, ids in mine.items():
            if not ids and not kv_get(s, f"auto:favorite:{uid}:mine"):
                continue   # never had one: do not make an empty album
            if _upsert(s, "favorite", uid, "mine", "お気に入り", ids):
                n += 1
        if (family or kv_get(s, "auto:favorite:None:family")) and _upsert(s, "favorite", None, "family", "家族のお気に入り", sorted(family)):
            n += 1
    return n


def tags(db: Database, settings: Settings) -> dict:
    """What is in the photos ("犬", "海", ...) from Synology Photos' recognition, for searching.
    Read as each linked person (their space); the shared space through the first linked admin."""
    from .albums import _list_all
    from .db import MediaTag, visible_clause
    from .photos_link import PhotosError, Session
    stored, notes = 0, []
    with db.session() as s:
        users = [u for u in s.execute(select(User)).scalars() if u.dsm_secret]
        for u in users:
            s.expunge(u)
    for u in users:
        try:
            with Session(settings, u) as ses:
                with db.session() as s:
                    index = {(Path(m.nas_path).name.lower(), m.bytes): m.id for m in
                             s.execute(select(Media).where(Media.status == "active", visible_clause(u))).scalars()}
                for api, key, item_api in (("SYNO.Foto.Browse.Concept", "concept_id", "SYNO.Foto.Browse.Item"),
                                           ("SYNO.Foto.Browse.GeneralTag", "general_tag_id", "SYNO.Foto.Browse.Item"),
                                           ("SYNO.FotoTeam.Browse.Concept", "concept_id", "SYNO.FotoTeam.Browse.Item"),
                                           ("SYNO.FotoTeam.Browse.GeneralTag", "general_tag_id", "SYNO.FotoTeam.Browse.Item")):
                    try:
                        found = _list_all(ses, api, "list")
                    except PhotosError as e:
                        notes.append(f"{u.name}: {api}: {e}")
                        continue
                    for t in found[:300]:
                        name = (t.get("name") or "").strip()
                        if not name or t.get("id") is None:
                            continue
                        items = _list_all(ses, item_api, "list", **{key: t["id"]})
                        ids = {index[k] for k in (((it.get("filename") or "").lower(), int(it.get("filesize") or 0))
                                                  for it in items) if k in index}
                        with db.session() as s:
                            have = set(s.execute(select(MediaTag.media_id).where(MediaTag.tag == name)).scalars())
                            for mid in ids - have:
                                s.add(MediaTag(media_id=mid, tag=name[:80]))
                                stored += 1
        except Exception as e:  # noqa: BLE001 - undocumented API
            notes.append(f"{u.name}: {e}")
    if notes:
        from .db import log_event
        with db.session() as s:
            for t in notes[:20]:
                log_event(s, "warn", "photo_tags", t[:500])
    return {"stored": stored, "notes": notes}


import threading

_FAV = threading.Lock()   # ♡ refreshes the favorite albums at once; never two at a time
_RUN = threading.Lock()   # the nightly run, the run after start and 「今すぐ作る」 never overlap


def drop_shared_trip_place(db: Database) -> int:
    """Trip / place albums made for the shared space by an older version (each person now has one
    with the shared photos in it): removed, so nothing shows twice."""
    from .db import KV
    n = 0
    with db.session() as s:
        for a in list(s.execute(select(Album).where(Album.origin.in_(["trip", "place"]), Album.owner_id.is_(None))).scalars()):
            for ai in list(s.execute(select(AlbumItem).where(AlbumItem.album_id == a.id)).scalars()):
                touch_media(s, s.get(Media, ai.media_id))
                s.delete(ai)
            s.flush()
            for k in list(s.execute(select(KV).where((KV.key == f"autoref:{a.id}") |
                                                    (KV.key.like("auto:%") & (KV.value == str(a.id))))).scalars()):
                s.delete(k)
            s.delete(a)
            n += 1
    return n


def dedupe(db: Database) -> int:
    """Automatic albums made twice (two runs at the same moment): keep one, move the photos into it,
    delete the rest."""
    from .db import KV
    n = 0
    with db.session() as s:
        groups: dict[tuple, list[Album]] = defaultdict(list)
        for a in s.execute(select(Album).where(Album.origin.in_(AUTO_ORIGINS)).order_by(Album.id)).scalars():
            groups[(a.origin, a.owner_id, a.name)].append(a)
        for (origin, owner, _name), albums in groups.items():
            if len(albums) < 2:
                continue
            refd = {int(k.value) for k in s.execute(select(KV).where(KV.key.like(f"auto:{origin}:{owner}:%"))).scalars()
                    if k.value.isdigit()}
            keep = next((a for a in albums if a.id in refd), albums[0])
            have = set(s.execute(select(AlbumItem.media_id).where(AlbumItem.album_id == keep.id)).scalars())
            for a in albums:
                if a.id == keep.id:
                    continue
                for ai in list(s.execute(select(AlbumItem).where(AlbumItem.album_id == a.id)).scalars()):
                    if ai.media_id not in have:
                        s.add(AlbumItem(album_id=keep.id, media_id=ai.media_id))
                        have.add(ai.media_id)
                    touch_media(s, s.get(Media, ai.media_id))
                    s.delete(ai)
                s.flush()
                old = s.get(KV, f"autoref:{a.id}")
                if old is not None:
                    s.delete(old)
                for k in s.execute(select(KV).where(KV.value == str(a.id), KV.key.like("auto:%"))).scalars():
                    k.value = str(keep.id)   # its key now points at the one that is kept
                s.delete(a)
                n += 1
            keep.rev = next_rev(s)
    return n


def run_all(db: Database, settings: Settings) -> dict:
    with _RUN:
        return _run_all(db, settings)


def _run_all(db: Database, settings: Settings) -> dict:
    out = {"merged_duplicates": dedupe(db) + drop_shared_trip_place(db), "digest": digest(db, settings), "trip": trips(db, settings), "folder": folders(db, settings),
           "place": places(db, settings), "favorite": favorites(db, settings)}
    p = people(db, settings)
    out["person"] = p["made"]
    if p["notes"]:
        out["person_note"] = p["notes"][0]
    out["growth"] = growth(db, settings)   # after the person albums
    t = tags(db, settings)
    out["tags"] = t["stored"]
    return out
