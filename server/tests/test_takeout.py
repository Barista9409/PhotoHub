"""P1 acceptance: ~100-photo sample Takeout; dates/GPS match the originals; importing the
same ZIP twice adds nothing; resume after interruption; filename collisions."""
from datetime import datetime
from pathlib import Path

import pytest
from sqlalchemy import func, select

from photohub import fileops, ingest
from photohub.db import Album, AlbumItem, ImportJob, Location, Media
from photohub.sample import SampleItem, default_items, write_takeout_zip
from photohub.takeout import import_inbox

from .conftest import needs_exiftool, needs_gpth

pytestmark = [needs_gpth, needs_exiftool]


def _zip(settings, name, items, **kw):
    return write_takeout_zip(settings.inbox / name, items, **kw)


def _count(db, model=Media):
    with db.session() as s:
        return s.scalar(select(func.count()).select_from(model))


def _library_files(settings):
    return sorted(p for p in settings.library.rglob("*") if p.is_file() and not p.name.endswith(".xmp"))


def test_dry_run_changes_nothing(settings, db):
    _zip(settings, "takeout-20260101T000000Z-001.zip", default_items(20))
    reps = import_inbox(db, settings, apply=False)
    assert reps[0].new == 20 and reps[0].dry_run
    assert _library_files(settings) == []
    assert _count(db) == 0
    assert list(settings.inbox.glob("*.zip"))  # ZIP still in the inbox
    with db.session() as s:
        assert s.scalar(select(func.count()).select_from(ImportJob).where(ImportJob.state == "dry_run")) == 1


def test_import_100_matches_metadata_and_is_idempotent(settings, db):
    items = default_items(100)
    items[3].in_year_folder = False
    items[3].albums = ["Album only"]
    _zip(settings, "takeout-20260101T000000Z-001.zip", items[:50])
    _zip(settings, "takeout-20260101T000000Z-002.zip", items[50:])
    kept = {z.name: z.read_bytes() for z in settings.inbox.rglob("*.zip")}
    rep = import_inbox(db, settings, apply=True)[0]
    assert (rep.new, rep.error) == (100, 0), rep.errors
    assert len(_library_files(settings)) == 100
    assert not list(settings.inbox.rglob("*.zip"))  # imported without errors -> deleted

    by_name = {i.name: i for i in items}
    with db.session() as s:
        for m in s.execute(select(Media)).scalars():
            it = by_name[Path(m.nas_path).name]
            assert m.taken_at == it.taken_utc, m.nas_path
            if it.lat is None:
                assert m.lat is None
            else:
                assert abs(m.lat - it.lat) < 1e-6 and abs(m.lon - it.lon) < 1e-6
            assert (m.description or "") == it.description
            # placed under YYYY/YYYY-MM in local time (+09:00)
            local = it.taken_utc.timestamp() + 9 * 3600
            assert Path(m.nas_path).parent.name == datetime.utcfromtimestamp(local).strftime("%Y-%m")
            assert s.execute(select(Location).where(Location.media_id == m.id, Location.store == "google")).scalar_one()
        albums = dict(s.execute(select(Album.name, func.count()).join(AlbumItem).group_by(Album.name)).all())
    assert albums == {"Trip 2023": 10, "Family": 7, "Album only": 1}

    # the very same ZIPs again -> nothing new
    for name, data in kept.items():
        (settings.inbox / name).write_bytes(data)
    rep2 = import_inbox(db, settings, apply=True)[0]
    assert rep2.skipped_job
    assert len(_library_files(settings)) == 100 and _count(db) == 100


def test_incremental_export_with_overlap(settings, db):
    items = default_items(30)
    _zip(settings, "takeout-20260101T000000Z-001.zip", items[:20])
    import_inbox(db, settings, apply=True)
    # next export: 5 old (e.g. re-exported) + 10 new; different archive name
    _zip(settings, "takeout-20260301T000000Z-001.zip", items[15:30])
    rep = import_inbox(db, settings, apply=True)[0]
    assert (rep.new, rep.duplicate + rep.merged, rep.error) == (10, 5, 0)
    assert _count(db) == 30 and len(_library_files(settings)) == 30


def test_filename_collision_gets_hash_suffix(settings, db):
    t = datetime(2023, 5, 1, 1, 0, 0)
    a = SampleItem(name="IMG_0001.jpg", taken_utc=t, lat=None, lon=None, seed=100)
    b = SampleItem(name="IMG_0001.jpg", taken_utc=t.replace(day=2), lat=None, lon=None, seed=200)
    _zip(settings, "takeout-a-001.zip", [a])
    import_inbox(db, settings, apply=True)
    _zip(settings, "takeout-b-001.zip", [b])
    import_inbox(db, settings, apply=True)
    names = sorted(p.name for p in _library_files(settings))
    assert len(names) == 2 and names[0] == "IMG_0001.jpg"
    assert names[1].startswith("IMG_0001_") and len(names[1]) == len("IMG_0001_12345678.jpg")


def test_resume_after_interruption(settings, db, monkeypatch):
    _zip(settings, "takeout-c-001.zip", default_items(25))
    calls = {"n": 0}
    real = fileops.copy_verified

    def flaky(src, dst, expected_hash=None):
        calls["n"] += 1
        if calls["n"] == 11:
            raise KeyboardInterrupt("simulated power loss")
        return real(src, dst, expected_hash)

    monkeypatch.setattr(ingest, "copy_verified", flaky)
    with pytest.raises(KeyboardInterrupt):
        import_inbox(db, settings, apply=True)
    assert len(_library_files(settings)) == 10
    monkeypatch.setattr(ingest, "copy_verified", real)
    rep = import_inbox(db, settings, apply=True)[0]
    assert rep.error == 0
    assert len(_library_files(settings)) == 25 and _count(db) == 25
    assert not any(p.name.endswith(".photohub-tmp") for p in settings.library.rglob("*"))


def test_trash_set_aside_not_imported(settings, db):
    from photohub.sample import make_image
    _zip(settings, "takeout-d-001.zip", default_items(3), extra_files={"Trash/PXL_T.jpg": make_image(9)})
    rep = import_inbox(db, settings, apply=True)[0]
    assert rep.new == 3 and rep.set_aside == {"google-trash": 1}
    assert len(list((settings.root / "google-trash").rglob("*.jpg"))) == 1
    assert not any(p.name == "PXL_T.jpg" for p in _library_files(settings))


def test_storage_saver_copy_is_linked_not_duplicated(settings, db):
    """D4: Google's recompressed copy of a photo we already hold in full quality."""
    from PIL import Image

    from photohub.scanner import scan
    t = datetime(2022, 3, 3, 3, 3, 3)
    it = SampleItem(name="PXL_X.jpg", taken_utc=t, lat=35.0, lon=139.0, seed=4242)
    # the full-quality original reached the NAS via phone backup
    orig_dir = settings.library / "2022" / "2022-03"
    orig_dir.mkdir(parents=True)
    _zip(settings, "takeout-full-001.zip", [it])
    import_inbox(db, settings, apply=True)
    assert _count(db) == 1
    # simulate storage saver: same moment, smaller recompressed file
    import io
    import zipfile
    src = _library_files(settings)[0]
    with Image.open(src) as img:
        buf = io.BytesIO()
        img.resize((480, 360)).save(buf, "JPEG", quality=50, exif=img.getexif())
    small = SampleItem(name="PXL_X.jpg", taken_utc=t, lat=35.0, lon=139.0, seed=4242)
    z = _zip(settings, "takeout-saver-001.zip", [small])
    # replace the image bytes inside that zip with the recompressed version
    tmp = z.with_suffix(".tmp")
    with zipfile.ZipFile(z) as zin, zipfile.ZipFile(tmp, "w") as zout:
        for info in zin.infolist():
            data = buf.getvalue() if info.filename.endswith("PXL_X.jpg") else zin.read(info)
            zout.writestr(info, data)
    tmp.replace(z)
    rep = import_inbox(db, settings, apply=True)[0]
    assert rep.new == 0 and rep.merged == 1, rep.summary()
    assert _count(db) == 1 and len(_library_files(settings)) == 1
    with db.session() as s:
        g = s.execute(select(Location).where(Location.store == "google")).scalar_one()
        assert g.match == "exact"  # the first (exact) import wins; never downgraded
    assert list((settings.root / "google-similar").rglob("PXL_X.jpg"))
    assert scan(db, settings, [settings.library], apply=True).new == 0


def test_scheduled_runs_do_not_repeat_dry_run(settings, db):
    _zip(settings, "takeout-e-001.zip", default_items(3))
    assert len(import_inbox(db, settings, apply=False, wait_stable=True)) == 1
    assert import_inbox(db, settings, apply=False, wait_stable=True) == []  # not redone
    assert import_inbox(db, settings, apply=False, wait_stable=False)[0].new == 3  # manual run still works
    rep = import_inbox(db, settings, apply=True, wait_stable=True)[0]  # switching to apply imports it
    assert rep.new == 3


def test_takeout_status_for_the_app(settings, db):
    """The app's migration screen: ZIPs found, waiting, live progress and results."""
    from fastapi.testclient import TestClient
    from photohub.api import create_app
    from photohub.users import add_user
    u, token = add_user(db, settings, "reo")
    h = {"Authorization": f"Bearer {token}"}
    src = settings.homes_root / "reo" / "Takeout"
    src.mkdir(parents=True)
    (src / "takeout-1-001.zip").write_bytes(b"x" * 10)
    items = default_items(5)
    (settings.inbox / "reo").mkdir(parents=True, exist_ok=True)
    _zip(settings, "reo/takeout-20260101T000000Z-001.zip", items)
    c = TestClient(create_app(settings, db))
    st = c.get("/api/me/takeout", headers=h).json()
    assert [z["name"] for z in st["sources"]] == ["takeout-1-001.zip"] and st["sources"][0]["bytes"] == 10
    assert [z["name"] for z in st["waiting"]] == ["takeout-20260101T000000Z-001.zip"]
    import_inbox(db, settings, apply=False)
    st = c.get("/api/me/takeout", headers=h).json()
    assert st["current"]["finished"] and st["current"]["done"] == 5 and st["current"]["dry_run"]
    assert st["jobs"][0]["state"] == "dry_run" and st["jobs"][0]["new"] == 5


def test_takeout_leftovers_can_be_deleted(settings, db):
    """A ZIP that was imported without errors but is still in the Drive folder: listed and deletable."""
    from fastapi.testclient import TestClient
    from photohub.api import create_app
    from photohub.db import ImportJob
    from photohub.users import add_user
    u, token = add_user(db, settings, "reo")
    h = {"Authorization": f"Bearer {token}"}
    src = settings.homes_root / "reo" / "Takeout"
    src.mkdir(parents=True)
    (src / "takeout-9-001.zip").write_bytes(b"x")
    (src / "takeout-9-002.zip").write_bytes(b"y")
    with db.session() as s:
        s.add(ImportJob(source="takeout", archive_name="takeout-9-001.zip", user_id=u.id, state="done"))
    c = TestClient(create_app(settings, db))
    st = c.get("/api/me/takeout", headers=h).json()
    assert [z["name"] for z in st["leftover"]] == ["takeout-9-001.zip"]
    assert c.post("/api/me/takeout/cleanup", headers=h).json()["deleted"] == 1
    st = c.get("/api/me/takeout", headers=h).json()
    assert [d["name"] for d in st["deleted"]] == ["takeout-9-001.zip"] and st["leftover"] == []
    assert (src / "takeout-9-002.zip").exists()
