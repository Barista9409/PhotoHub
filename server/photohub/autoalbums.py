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

AUTO_ORIGINS = ("digest", "trip", "folder")
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
        owners: list[int | None] = [u.id for u in s.execute(select(User)).scalars()] + [None]
        for owner in owners:
            q = select(Media).where(Media.status == "active", Media.taken_at.is_not(None),
                                    Media.owner_id.is_(None) if owner is None else Media.owner_id == owner)
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


def folders(db: Database, settings: Settings) -> int:
    """Hand-made folders of the shared space -> shared albums named after the folder."""
    n = 0
    root = settings.library
    with db.session() as s:
        by_folder: dict[str, list[int]] = defaultdict(list)
        for m in s.execute(select(Media).where(Media.status == "active", Media.owner_id.is_(None))).scalars():
            try:
                rel = Path(m.nas_path).parent.relative_to(root)
            except ValueError:
                continue
            parts = [p for p in rel.parts if not _DATE_FOLDER.match(p)]
            if not parts:
                continue
            by_folder["/".join(parts)].append(m.id)
        for key, ids in by_folder.items():
            if len(ids) < 3:
                continue
            if _upsert(s, "folder", None, key, key.replace("/", " / "), ids):
                n += 1
    return n


def run_all(db: Database, settings: Settings) -> dict:
    return {"digest": digest(db, settings), "trip": trips(db, settings), "folder": folders(db, settings)}
