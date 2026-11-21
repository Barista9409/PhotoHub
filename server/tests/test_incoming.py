"""Phone backups straight to the NAS and through Google Drive (Cloud Sync folder)."""
import hashlib
import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select

from photohub.api import create_app
from photohub.db import Location, Media
from photohub.incoming import drive_dir, process_incoming
from photohub.sample import make_image
from photohub.users import add_user


@pytest.fixture
def chie(settings, db):
    u, token = add_user(db, settings, "chie")
    Path(u.personal_root).mkdir(parents=True, exist_ok=True)
    return u, {"Authorization": f"Bearer {token}"}, TestClient(create_app(settings, db))


def _up(c, h, data: bytes, name: str, fh: str | None = None):
    return c.post("/api/upload", content=data,
                  headers={**h, "X-File-Hash": fh or hashlib.sha256(data).hexdigest(), "X-File-Name": name})


def _count(db):
    with db.session() as s:
        return s.scalar(select(func.count()).select_from(Media))


def test_upload_dry_run_then_apply(settings, db, chie):
    u, h, c = chie
    img = make_image(1)
    fh = hashlib.sha256(img).hexdigest()
    assert _up(c, h, img, "PXL_1.jpg").json() == {"status": "stored"}
    assert _up(c, h, img, "PXL_1.jpg").json()["status"] == "already"
    assert c.post("/api/have", json={"hashes": [fh]}, headers=h).json() == {fh: "queued"}
    # dry run: nothing placed, the upload waits
    rep = process_incoming(db, settings, apply=False)
    assert rep.new == 1 and _count(db) == 0
    assert list((settings.root / "incoming" / "chie").glob("*.jpg"))
    rep = process_incoming(db, settings, apply=True)
    assert rep.new == 1 and _count(db) == 1
    placed = list(Path(u.personal_root, "PhotoHub").rglob("PXL_1.jpg"))
    assert len(placed) == 1 and placed[0].read_bytes() == img
    assert not list((settings.root / "incoming" / "chie").glob("*.jpg"))  # temp copy removed after verify
    assert c.post("/api/have", json={"hashes": [fh]}, headers=h).json() == {fh: "nas"}


def test_upload_rejects_damaged_transfer(settings, db, chie):
    _, h, c = chie
    r = _up(c, h, make_image(2), "a.jpg", fh="0" * 64)
    assert r.status_code == 400 and "mismatch" in r.json()["detail"]
    assert _up(c, h, b"hello", "notes.txt").status_code == 400


def test_same_photo_from_phone_drive_and_takeout_is_one_item(settings, db, chie):
    u, h, c = chie
    img = make_image(3)
    _up(c, h, img, "PXL_3.jpg")
    d = drive_dir(settings, u) / "2026" / "PXL_3.jpg"
    d.parent.mkdir(parents=True)
    d.write_bytes(img)
    other = drive_dir(settings, u) / "2026" / "PXL_4.jpg"
    other.write_bytes(make_image(4))
    settings.stable_seconds = 0
    rep = process_incoming(db, settings, apply=True)
    assert (rep.new, rep.duplicate) == (2, 1)
    assert _count(db) == 2
    assert d.exists() and other.exists()  # Cloud Sync's files are left alone
    with db.session() as s:
        stores = sorted(s.execute(select(Location.store).where(Location.store == "drive")).scalars())
    assert stores == ["drive", "drive"]
    # nothing new on the next run
    assert process_incoming(db, settings, apply=True).new == 0


def test_shared_choice_places_in_shared_space(settings, db, chie):
    u, h, c = chie
    c.put("/api/me/settings", json={"takeout_target": "shared"}, headers=h)
    _up(c, h, make_image(9), "PXL_9.jpg")
    process_incoming(db, settings, apply=True)
    assert list(settings.library.rglob("PXL_9.jpg"))


def test_user_folders_are_created(settings, db):
    add_user(db, settings, "mio")
    assert (settings.inbox / "mio").is_dir()
    assert (settings.inbox.parent / "drive" / "mio").is_dir()
