"""P2 (scan/resume/duplicate report), F2 similar groups, P4 storage rules, F7 API."""
import os
import shutil
import struct
from datetime import datetime, timedelta
from pathlib import Path

from fastapi.testclient import TestClient
from PIL import Image
from sqlalchemy import func, select

from photohub import similar, storage
from photohub.api import create_app
from photohub.db import Location, Media, SimilarGroup
from photohub.sample import make_image
from photohub.scanner import scan

from .conftest import needs_exiftool


def _put(settings, rel, seed, when=None, **kw):
    p = settings.library / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(make_image(seed, **kw))
    if when and shutil.which("exiftool"):
        import subprocess
        subprocess.run(["exiftool", "-q", "-overwrite_original",
                        f"-DateTimeOriginal={when:%Y:%m:%d %H:%M:%S}", str(p)], check=True)
    return p


@needs_exiftool
def test_scan_resume_and_duplicates(settings, db):
    for i in range(30):
        _put(settings, f"2021/2021-0{1 + i % 9}/IMG_{i:03d}.jpg", seed=i, when=datetime(2021, 1 + i % 9, 1, 12))
    dup = settings.library / "copies" / "IMG_000_copy.jpg"
    dup.parent.mkdir()
    shutil.copy(settings.library / "2021/2021-01/IMG_000.jpg", dup)

    dry = scan(db, settings, [settings.library], apply=False)
    assert dry.new == 30
    with db.session() as s:
        assert s.scalar(select(func.count()).select_from(Media)) == 0

    part = scan(db, settings, [settings.library], apply=True, limit=12, batch=5)
    assert part.new + len(part.duplicates) == 12
    rest = scan(db, settings, [settings.library], apply=True, batch=5)
    assert rest.skipped_unchanged == 12  # resumed, nothing re-hashed
    with db.session() as s:
        assert s.scalar(select(func.count()).select_from(Media)) == 30
    total_dups = len(part.duplicates) + len(rest.duplicates)
    assert total_dups == 1
    again = scan(db, settings, [settings.library], apply=True)
    assert again.new == 0 and again.skipped_unchanged == 31


def test_similar_group_and_resolve_moves_to_trash(settings, db):
    a = _put(settings, "2020/2020-01/A.jpg", seed=10, quality=95)
    b = settings.library / "2020/2020-01/A_small.jpg"
    with Image.open(a) as img:
        img.resize((320, 240)).save(b, "JPEG", quality=60)
    _put(settings, "2020/2020-01/other.jpg", seed=99)
    scan(db, settings, [settings.library], apply=True)
    assert similar.refresh(db, settings) == 1
    with db.session() as s:
        g = s.execute(select(SimilarGroup)).scalar_one()
        keep = s.execute(select(Media.id).where(Media.nas_path == str(a))).scalar_one()
    dry = similar.resolve(db, settings, g.id, {keep}, apply=False)
    assert len(dry) == 1 and b.exists()
    similar.resolve(db, settings, g.id, {keep}, apply=True)
    assert a.exists() and not b.exists()
    assert len(list(settings.trash.rglob("A_small.jpg"))) == 1
    with db.session() as s:
        assert s.execute(select(Media.status).where(Media.nas_path.like("%A_small.jpg"))).scalar_one() == "trashed"


@needs_exiftool
def test_google_candidates_require_verified_nas_copy_and_amazon(settings, db):
    old = datetime.now() - timedelta(days=500)
    p1 = _put(settings, "old/photo1.jpg", seed=1, when=old)
    p2 = _put(settings, "old/photo2.jpg", seed=2, when=old)
    _put(settings, "new/photo3.jpg", seed=3, when=datetime.now() - timedelta(days=10))
    scan(db, settings, [settings.library], apply=True)
    with db.session() as s:
        for m in s.execute(select(Media)).scalars():
            storage.set_location(s, m.id, "google", "present")

    assert storage.google_candidates(db, settings) == []  # not verified, not on Amazon
    storage.fill_outbox(db, settings, apply=True)
    assert (settings.outbox / "shared" / "old" / "photo1.jpg").exists()
    assert os.stat(settings.outbox / "shared" / "old" / "photo1.jpg").st_ino == os.stat(p1).st_ino  # hard link
    storage.verify_nas(db, settings)
    assert storage.google_candidates(db, settings) == []  # Amazon app has not run yet

    # PC heartbeat after placement -> amazon assumed
    with db.session() as s:
        for loc in s.execute(select(Location).where(Location.store == "amazon")).scalars():
            loc.seen_at = datetime.utcnow() - timedelta(hours=3)
    storage.record_heartbeat(db)
    storage.fill_outbox(db, settings, apply=True)
    # a NAS file that no longer matches its hash must never be a candidate
    p2.write_bytes(make_image(555))
    with db.session() as s:
        for loc in s.execute(select(Location).where(Location.store == "nas")).scalars():
            loc.verified_at = None
    storage.verify_nas(db, settings)
    rows = storage.google_candidates(db, settings)
    assert len(rows) == 1 and rows[0]["photos"] == 1 and rows[0]["match"] == "exact"
    assert storage.confirm_cleanup(db, settings, rows[0]["id"]) == 1
    with db.session() as s:
        states = sorted(s.execute(select(Location.state).where(Location.store == "google")).scalars())
    assert states == ["present", "present", "removed"]


def test_api_auth_sync_and_thumb_pack(settings, db):
    for i in range(7):
        _put(settings, f"x/{i}.jpg", seed=i)
    scan(db, settings, [settings.library], apply=True)
    from photohub.users import add_user
    _, token = add_user(db, settings, "reo", admin=True)
    c = TestClient(create_app(settings, db))
    assert c.get("/api/health").status_code == 200
    assert c.get("/api/status").status_code == 401
    assert c.get("/api/status", headers={"Authorization": "Bearer nope"}).status_code == 401
    h = {"Authorization": f"Bearer {token}"}
    st = c.get("/api/status", headers=h).json()
    assert st["photos"] == 7

    seen, cursor = [], 0
    while True:
        r = c.get(f"/api/sync?cursor={cursor}&limit=3", headers=h).json()
        seen += r["items"]
        cursor = r["cursor"]
        if not r["has_more"]:
            break
    assert len({x["hash"] for x in seen}) == 7
    assert c.get(f"/api/sync?cursor={cursor}", headers=h).json()["items"] == []

    hashes = [x["hash"] for x in seen[:3]] + ["missing"]
    body = c.post("/api/thumbs", json={"hashes": hashes}, headers=h).content
    assert body[:4] == b"PHP1" and struct.unpack(">I", body[4:8])[0] == 4
    off, sizes = 8, []
    for _ in range(4):
        (hl,) = struct.unpack(">H", body[off:off + 2]); off += 2 + hl
        (dl,) = struct.unpack(">I", body[off:off + 4]); off += 4
        sizes.append(dl)
        if dl:
            assert body[off:off + 4] == b"RIFF"
        off += dl
    assert sizes[-1] == 0 and all(sizes[:3])
    prev = c.get(f"/api/preview/{seen[0]['hash']}", headers=h)
    assert prev.status_code == 200 and prev.headers["content-type"] == "image/webp"
    with Image.open(__import__("io").BytesIO(prev.content)) as im:
        assert max(im.size) <= 1600
    orig = c.get(f"/api/original/{seen[0]['hash']}", headers={**h, "Range": "bytes=0-9"})
    assert orig.status_code == 206 and len(orig.content) == 10


def test_raid_status_parsing(tmp_path: Path):
    from photohub.notify import raid_status
    f = tmp_path / "mdstat"
    f.write_text("Personalities : [raid1]\nmd2 : active raid1 sda3[0] sdb3[1]\n      100 blocks super 1.2 [2/2] [UU]\n"
                 "md3 : active raid1 sdc3[0]\n      100 blocks super 1.2 [2/1] [U_]\n")
    assert raid_status(f) == ["md3"]


def test_health_takeout_overdue_and_schedule_end(settings, db):
    from datetime import date

    from photohub.notify import health_check
    settings.min_free_gb = 0
    settings.takeout_schedule_start = date(2026, 1, 1)
    raised = health_check(db, settings, today=date(2026, 12, 15))
    assert "takeout_schedule_ending" in raised and "takeout_overdue" in raised
    assert health_check(db, settings, today=date(2026, 12, 15)) == []  # rate-limited



@needs_exiftool
def test_candidates_without_amazon(settings, db):
    import subprocess
    settings.amazon_backup = False
    old = datetime.now() - timedelta(days=500)
    p = _put(settings, "old/a.jpg", seed=8)
    subprocess.run(["exiftool", "-q", "-overwrite_original", f"-DateTimeOriginal={old:%Y:%m:%d %H:%M:%S}", str(p)], check=True)
    scan(db, settings, [settings.library], apply=True)
    with db.session() as s:
        m = s.execute(select(Media)).scalar_one()
        storage.set_location(s, m.id, "google", "present")
    assert storage.google_candidates(db, settings) == []  # still needs a verified NAS copy
    storage.verify_nas(db, settings)
    rows = storage.google_candidates(db, settings)
    assert len(rows) == 1 and rows[0]["photos"] == 1
