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
