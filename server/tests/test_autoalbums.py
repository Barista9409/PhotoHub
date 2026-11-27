"""Albums PhotoHub makes by itself: monthly digest, trips, shared folders; merge."""
from datetime import datetime, timedelta

from fastapi.testclient import TestClient
from sqlalchemy import select

from photohub import autoalbums
from photohub.api import create_app
from photohub.db import Album, AlbumItem, Media, kv_set, utcnow
from photohub.users import add_user


def _media(s, settings, i, when, folder="", owner=None, w=4000, h=3000, name=None):
    m = Media(content_hash=f"c{i}", file_hash=f"f{i}", kind="photo", bytes=1000, width=w, height=h,
              nas_path=str(settings.library / folder / (name or f"IMG_{i}.jpg")), taken_at=when, owner_id=owner,
              status="active")
    s.add(m)
    s.flush()
    return m


def _albums(db, origin):
    with db.session() as s:
        return {a.name: len(list(s.execute(select(AlbumItem).where(AlbumItem.album_id == a.id)).scalars()))
                for a in s.execute(select(Album).where(Album.origin == origin)).scalars()}


def test_digest_trip_folder_and_merge(settings, db):
    u, token = add_user(db, settings, "reo")
    now = utcnow()
    first = (now + timedelta(minutes=settings.default_tz_minutes)).replace(day=1)
    last_month = (first - timedelta(days=1)).replace(day=10, hour=3)
    with db.session() as s:
        # last month: 3 days with 10 photos each, plus a screenshot and a tiny picture (left out)
        for d in range(3):
            for k in range(10):
                _media(s, settings, 100 + d * 10 + k, last_month + timedelta(days=d, minutes=k), owner=u.id)
        _media(s, settings, 900, last_month, owner=u.id, name="Screenshot_1.png")
        _media(s, settings, 901, last_month, owner=u.id, w=100, h=100)
        # home is Tokyo; a three-day trip to Okinawa
        for i in range(30):
            m = _media(s, settings, 200 + i, datetime(2025, 3, 1) + timedelta(days=i % 20), owner=u.id)
            kv_set(s, f"place:{m.id}", "東京都 渋谷区")
        for i in range(12):
            m = _media(s, settings, 300 + i, datetime(2025, 8, 10) + timedelta(hours=5 * i), owner=u.id)
            kv_set(s, f"place:{m.id}", "沖縄県 那覇市")
        # shared space: a hand-made folder and a date folder (no album for that one)
        for i in range(4):
            _media(s, settings, 400 + i, datetime(2024, 5, 1), folder="2024/運動会")
            _media(s, settings, 500 + i, datetime(2024, 6, 1), folder="2024/2024-06")
    r = autoalbums.run_all(db, settings)
    assert r["digest"] >= 1 and r["trip"] == 1 and r["folder"] == 1
    digest = _albums(db, "digest")
    name = f"{last_month.year}年{last_month.month}月のふりかえり"
    assert 3 <= digest[name] <= autoalbums.DIGEST_MAX     # spread over the 3 days, no screenshot / tiny one
    assert _albums(db, "trip") == {"2025年8月 沖縄県 那覇市": 12}
    assert _albums(db, "folder") == {"運動会": 4}

    c = TestClient(create_app(settings, db))
    h = {"Authorization": f"Bearer {token}"}
    ids = {a["name"]: a["id"] for a in c.get("/api/sync", headers=h).json()["albums"]}
    # merging two albums: one album with both, the other gone; it is not remade at night
    r = c.post("/api/albums/merge", json={"ids": [ids["運動会"], ids["2025年8月 沖縄県 那覇市"]], "name": "思い出"}, headers=h).json()
    assert r["album_id"] == ids["運動会"]
    autoalbums.run_all(db, settings)
    with db.session() as s:
        left = {a.name: a.origin for a in s.execute(select(Album)).scalars()}
    assert left.get("思い出") == "manual" and "2025年8月 沖縄県 那覇市" not in left and "運動会" not in left
    assert _albums(db, "manual") == {"思い出": 16}


def test_people_albums_from_synology_photos(settings, db, monkeypatch):
    import httpx
    from urllib.parse import parse_qs
    from photohub.db import User
    from photohub.vault import seal
    u, _ = add_user(db, settings, "reo")
    with db.session() as s:
        s.execute(select(User)).scalar_one().dsm_secret = seal(settings, {"account": "reo", "password": "x"})
        for i in range(8):
            _media(s, settings, 600 + i, datetime(2025, 1, 1), owner=u.id)
    persons = [{"id": 1, "name": "はると", "item_count": 6}, {"id": 2, "name": "", "item_count": 5},
               {"id": 3, "name": "", "item_count": 2}]
    items = {1: [{"filename": f"IMG_{600 + i}.jpg", "filesize": 1000} for i in range(6)],
             2: [{"filename": f"IMG_{602 + i}.jpg", "filesize": 1000} for i in range(5)]}

    def handler(req: httpx.Request) -> httpx.Response:
        f = {k: v[0] for k, v in parse_qs(req.content.decode()).items()}
        if f["api"] == "SYNO.API.Auth":
            return httpx.Response(200, json={"success": True, "data": {"sid": "S"}})
        if f["api"] == "SYNO.FotoTeam.Browse.Person":   # the shared space: nobody yet
            return httpx.Response(200, json={"success": True, "data": {"list": []}})
        if f["api"] == "SYNO.Foto.Browse.Person":
            return httpx.Response(200, json={"success": True, "data": {"list": persons if f.get("offset") == "0" else []}})
        if f["api"] == "SYNO.Foto.Browse.Item":
            return httpx.Response(200, json={"success": True, "data": {"list": items[int(f["person_id"])] if f.get("offset") == "0" else []}})
        return httpx.Response(200, json={"success": False, "error": {"code": 102}})
    real = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kw: real(transport=httpx.MockTransport(handler), **kw))
    r = autoalbums.people(db, settings)
    assert r["made"] == 2 and not r["notes"]
    assert r["status"] == ["個人スペース：人物 3 人 → アルバム 2 個",
                           "共有スペース：Synology Photos がまだ顔を見分けていません（写真が多いと数日かかります。"
                           "Synology Photos の「人物」に顔が並んだら、ここにも出ます）"]
    assert _albums(db, "person") == {"はると": 6, "人物 2": 5}
    persons[1]["name"] = "ゆい"   # named later in Synology Photos
    autoalbums.people(db, settings)
    assert _albums(db, "person") == {"はると": 6, "ゆい": 5}


def test_places_favorites_growth_tags_memo_report(settings, db):
    from photohub.db import MediaTag
    u, token = add_user(db, settings, "reo")
    c = TestClient(create_app(settings, db))
    h = {"Authorization": f"Bearer {token}"}
    with db.session() as s:
        ms = []
        for i in range(25):
            m = _media(s, settings, 700 + i, datetime(2020 + i % 5, 6, 1), owner=u.id)
            kv_set(s, f"place:{m.id}", "新潟県 糸魚川市")
            ms.append(m)
        s.add(MediaTag(media_id=ms[0].id, tag="犬"))
        s.add(MediaTag(media_id=ms[1].id, tag="犬"))
        person = Album(name="はると", origin="person", owner_id=u.id)
        s.add(person)
        s.flush()
        for m in ms[:10]:
            s.add(AlbumItem(album_id=person.id, media_id=m.id))
        pid = person.id
        hashes = [m.content_hash for m in ms]
    # place album
    assert autoalbums.places(db, settings) == 1
    assert _albums(db, "place") == {"新潟県 糸魚川市": 25}
    # ♡ on the Synology -> お気に入り album
    assert c.post("/api/favorites", json={"hashes": hashes[:3]}, headers=h).json()["changed"] == 3
    assert sorted(c.get("/api/favorites", headers=h).json()["hashes"]) == sorted(hashes[:3])
    assert _albums(db, "favorite") == {"お気に入り": 3}   # at once, no night run needed
    c.post("/api/favorites", json={"hashes": hashes[:3], "on": False}, headers=h)
    assert _albums(db, "favorite") == {"お気に入り": 0}   # every ♡ off: the album empties, not stale
    c.post("/api/favorites", json={"hashes": hashes[:3]}, headers=h)
    autoalbums.favorites(db, settings)
    assert _albums(db, "favorite") == {"お気に入り": 3}
    # birthday -> growth albums (photos from 2020..2024, born 2020-01-01)
    r = c.put(f"/api/albums/{pid}/birthday", json={"date": "2020-01-01"}, headers=h).json()
    assert r["albums"] >= 1
    assert set(_albums(db, "growth")) <= {f"はると {a}歳" for a in range(5)}
    # search by what is in the photo
    t = c.get("/api/search/tags?q=犬", headers=h).json()
    assert t["tags"][0] == {"name": "犬", "count": 2} and len(t["hashes"]) == 2
    # memo, weekly report
    assert c.post(f"/api/media/{hashes[0]}/memo", json={"text": "初めて歩いた日"}, headers=h).json()["ok"]
    rep = c.get("/api/me/report", headers=h).json()
    assert rep["photos"] == 25 and "free_gb" in rep


def test_duplicate_automatic_albums_are_merged(settings, db):
    with db.session() as s:
        ms = [_media(s, settings, 800 + i, datetime(2014, 9, 1)) for i in range(4)]
        a1 = Album(name="2014年9月 愛知県", origin="trip", owner_id=None)
        a2 = Album(name="2014年9月 愛知県", origin="trip", owner_id=None)
        s.add_all([a1, a2])
        s.flush()
        for m in ms[:3]:
            s.add(AlbumItem(album_id=a1.id, media_id=m.id))
        for m in ms[1:]:
            s.add(AlbumItem(album_id=a2.id, media_id=m.id))
    assert autoalbums.dedupe(db) == 1
    assert _albums(db, "trip") == {"2014年9月 愛知県": 4}


def test_trips_kept_apart_between_my_photos_and_the_family(settings, db):
    """マイフォト and ファミリー apart: the person's trip from their own photos, the family's trip from the
    shared ones (a new family member does not get the family's photos as their own albums)."""
    u, _ = add_user(db, settings, "reo")
    with db.session() as s:
        for i in range(30):   # home: Tokyo (own photos)
            m = _media(s, settings, 900 + i, datetime(2015, 3, 1) + timedelta(days=i % 20), owner=u.id)
            kv_set(s, f"place:{m.id}", "東京都 渋谷区")
        for i in range(24):   # the trip: half the photos put in the shared space
            m = _media(s, settings, 950 + i, datetime(2015, 1, 10) + timedelta(hours=2 * i), owner=u.id if i % 2 else None)
            kv_set(s, f"place:{m.id}", "愛知県 豊川市")
        for i in range(30):   # the family's home: Tokyo too
            m = _media(s, settings, 1100 + i, datetime(2016, 3, 1) + timedelta(days=i % 20))
            kv_set(s, f"place:{m.id}", "東京都 渋谷区")
    new, _ = add_user(db, settings, "chie")   # joins later, has no photos yet
    autoalbums.run_all(db, settings)
    with db.session() as s:
        trips = sorted((a.name, a.owner_id or 0) for a in s.execute(select(Album).where(Album.origin == "trip")).scalars())
        assert trips == [("2015年1月 愛知県 豊川市", 0), ("2015年1月 愛知県 豊川市", u.id)]
        assert s.execute(select(Album).where(Album.owner_id == new.id)).first() is None   # chie: nothing of the family's
    assert sorted(_albums(db, "trip").values()) == [12]


def test_folder_albums_one_per_hand_made_folder(settings, db):
    with db.session() as s:
        for i, sub in enumerate(["2026_ヨーロッパ旅行/London", "2026_ヨーロッパ旅行/Athens", "2026_ヨーロッパ旅行", "写真/iPhoto", "写真/iPhoto/2010"]):
            for k in range(3):
                _media(s, settings, 1000 + i * 10 + k, datetime(2026, 5, 1), folder=sub)
    autoalbums.folders(db, settings)
    assert _albums(db, "folder") == {"2026_ヨーロッパ旅行": 9, "iPhoto": 6}


def test_every_photo_in_an_album_without_others(settings, db):
    import json
    from photohub.db import kv_get
    u, _ = add_user(db, settings, "reo")
    with db.session() as s:
        # an outing: 6 photos on Sat 2024-08-10 in 糸魚川 (UTC times; JST = +9h)
        for i in range(6):
            m = _media(s, settings, 900 + i, datetime(2024, 8, 10, 1, i), owner=u.id)
            kv_set(s, f"place:{m.id}", "新潟県 糸魚川市")
        # a few on other days of the month -> the month's 日常
        _media(s, settings, 910, datetime(2024, 8, 3, 3), owner=u.id)
        _media(s, settings, 911, datetime(2024, 8, 20, 3), owner=u.id)
        # an outing without a place: named after the weekday (2024-08-15 is a Thursday)
        for i in range(5):
            _media(s, settings, 920 + i, datetime(2024, 8, 15, 2, i), owner=u.id)
        # a screenshot: its own album, not in the memories
        _media(s, settings, 930, datetime(2024, 8, 10, 2), owner=u.id, name="Screenshot_20240810-110000.png")
    r = autoalbums.cover(db, settings)
    assert r == {"event": 2, "daily": 1, "kind": 1}
    assert _albums(db, "event") == {"2024年8月10日 糸魚川市": 6, "2024年8月15日（木）": 5}
    assert _albums(db, "daily") == {"2024年8月の日常": 2}
    assert _albums(db, "kind") == {"スクリーンショット": 1}
    with db.session() as s:
        assert json.loads(kv_get(s, "album_coverage"))[str(u.id)] == [14, 14]   # every photo, no 「その他」
    # more photos on the 3rd: it becomes an outing, and the month's 日常 keeps only the 20th
    with db.session() as s:
        for i in range(5):
            _media(s, settings, 940 + i, datetime(2024, 8, 3, 4, i), owner=u.id)
    autoalbums.cover(db, settings)
    assert _albums(db, "daily") == {"2024年8月の日常": 1}
    assert "2024年8月3日（土）" in _albums(db, "event")


def test_date_from_the_photos_next_to_it(settings, db):
    from photohub import dates
    u, _ = add_user(db, settings, "reo")
    with db.session() as s:
        a = _media(s, settings, 1230, datetime(2019, 5, 4, 1, 0), folder="old")
        b = _media(s, settings, 1240, datetime(2019, 5, 4, 3, 0), folder="old")
        c = _media(s, settings, 1235, None, folder="old")   # no clue of its own
        far = _media(s, settings, 1500, None, folder="old")  # too far from any numbered neighbour
        for m in (a, b):
            m.created_at = datetime(2024, 1, 1)
        ids = (c.id, far.id)
    with db.session() as s:
        todo = [s.get(Media, i) for i in ids]
        assert dates.from_neighbors(s, todo) == 1
        assert s.get(Media, ids[0]).taken_at == datetime(2019, 5, 4, 2, 0)   # halfway between its neighbours
        assert s.get(Media, ids[1]).taken_at is None
