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
    placed = list(Path(u.personal_root, "MobileBackup").rglob("PXL_1.jpg"))  # same place as Synology's backup
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


def test_shared_choice_applies_to_drive_but_phone_backup_stays_with_synology(settings, db, chie):
    """Phone backups always mirror Synology Photos' own backup folder (personal space);
    the "where photos go" choice applies to photos arriving via Google (Drive / Takeout)."""
    u, h, c = chie
    c.put("/api/me/settings", json={"takeout_target": "shared"}, headers=h)
    _up(c, h, make_image(9), "PXL_9.jpg")
    d = drive_dir(settings, u) / "PXL_10.jpg"
    d.parent.mkdir(parents=True, exist_ok=True)
    d.write_bytes(make_image(10))
    settings.stable_seconds = 0
    process_incoming(db, settings, apply=True)
    assert list(Path(u.personal_root, "MobileBackup").rglob("PXL_9.jpg"))
    assert list(settings.library.rglob("PXL_10.jpg"))


def test_user_folders_are_created(settings, db):
    add_user(db, settings, "mio")
    assert (settings.inbox / "mio").is_dir()
    assert (settings.inbox.parent / "drive" / "mio").is_dir()


def test_drive_folder_inside_existing_whole_drive_sync(settings, db):
    """Cloud Sync already mirrors the whole Drive into the person's home; read PhotoHub/ there."""
    from photohub.db import User
    u, _ = add_user(db, settings, "chie")
    Path(u.personal_root).mkdir(parents=True)
    whole = settings.homes_root / "chie" / "Google_Drive_chie" / "PhotoHub"
    whole.mkdir(parents=True)
    (whole / "PXL_7.jpg").write_bytes(make_image(7))
    with db.session() as s:
        s.get(User, u.id).drive_dir = str(whole)
    settings.stable_seconds = 0
    assert process_incoming(db, settings, apply=True).new == 1


def test_old_database_gets_new_columns(tmp_path):
    import sqlite3

    from photohub.db import Database
    p = tmp_path / "old.sqlite"
    con = sqlite3.connect(p)
    con.execute("CREATE TABLE app_user (id INTEGER PRIMARY KEY, name VARCHAR(64), is_admin BOOLEAN, token_hash VARCHAR(64))")
    con.execute("INSERT INTO app_user VALUES (1, 'reo', 1, 'x')")
    con.commit(); con.close()
    db = Database(p)
    cols = {r[1] for r in sqlite3.connect(p).execute("PRAGMA table_info(app_user)")}
    assert {"drive_dir", "takeout_target", "personal_root"} <= cols
    from photohub.users import by_name
    assert by_name(db, "reo").takeout_target == "personal"


def test_drive_folder_found_automatically(settings, db):
    from photohub.incoming import drive_dir
    u, _ = add_user(db, settings, "chie")
    whole = settings.homes_root / "chie" / "Google_Drive_chieaoki" / "PhotoHub"
    whole.mkdir(parents=True)
    assert drive_dir(settings, u) == whole


def test_photo_already_backed_up_by_synology_photos_is_not_stored_twice(settings, db, chie):
    """Synology Photos put the file in Photos/MobileBackup before the nightly scan ran."""
    u, h, c = chie
    img = make_image(42)
    mb = Path(u.personal_root) / "MobileBackup" / "Pixel" / "PXL_42.jpg"
    mb.parent.mkdir(parents=True)
    mb.write_bytes(img)
    _up(c, h, img, "PXL_42.jpg")
    rep = process_incoming(db, settings, apply=True)
    assert rep.new == 0 and rep.duplicate == 1
    copies = [p for p in Path(u.personal_root).rglob("*.jpg")]
    assert copies == [mb]


def _put_dated(p: Path, seed: int, when: str):
    import subprocess
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(make_image(seed))
    subprocess.run(["exiftool", "-q", "-overwrite_original", f"-DateTimeOriginal={when}", str(p)], check=True)


def _up_dev(c, h, data: bytes, name: str, device="motorola edge 60 pro", rel="DCIM/Camera/"):
    return c.post("/api/upload", content=data, headers={**h, "X-File-Hash": hashlib.sha256(data).hexdigest(),
                                                         "X-File-Name": name, "X-Device": device, "X-Rel-Path": rel})


def test_phone_backup_goes_where_synology_photos_puts_it(settings, db, chie):
    """Synology Photos already backs this phone up into MobileBackup/<device>/<Y>/<M>/: follow it."""
    u, h, c = chie
    dev = Path(u.personal_root) / "MobileBackup" / "motorola edge 60 pro"
    for i, d in enumerate(["2026:10:03 10:00:00", "2026:11:05 10:00:00", "2026:11:06 10:00:00"]):
        y, m = d[:4], str(int(d[5:7]))
        _put_dated(dev / y / m / f"IMG_{i}.jpg", 100 + i, d)
    import subprocess
    new = Path(settings.root) / "x.jpg"
    _put_dated(new, 555, "2026:11:20 09:00:00")
    _up_dev(c, h, new.read_bytes(), "PXL_20261120.jpg")
    assert process_incoming(db, settings, apply=True).new == 1
    assert (dev / "2026" / "11" / "PXL_20261120.jpg").exists()
    assert not (Path(u.personal_root) / "PhotoHub").exists()


def test_both_backups_running_converge_to_one_copy(settings, db, chie):
    """PhotoHub placed a photo first; later Synology Photos' backup adds the same photo under
    another name. The nightly scan keeps Synology's file and moves PhotoHub's copy to trash."""
    from photohub.scanner import scan
    from photohub.users import scan_roots
    u, h, c = chie
    img = make_image(77)
    _up_dev(c, h, img, "PXL_77.jpg")
    process_incoming(db, settings, apply=True)
    ours = [p for p in Path(u.personal_root).rglob("*.jpg")]
    assert len(ours) == 1
    theirs = ours[0].with_name("PXL_77(1).jpg")
    theirs.write_bytes(img)
    rep = scan(db, settings, scan_roots(db, settings), apply=True)
    assert len(rep.resolved) == 1 and rep.duplicates == []
    left = [p for p in Path(u.personal_root).rglob("*.jpg")]
    assert left == [theirs]
    assert list((settings.trash).rglob("PXL_77.jpg"))  # moved, not deleted
    with db.session() as s:
        m = s.execute(select(Media)).scalar_one()
        assert m.nas_path == str(theirs) and not m.placed


def test_synology_photos_space_is_indexed_in_check_mode(settings, db, chie):
    """In check mode (PHOTOHUB_APPLY off) the index is still saved, so photos Synology Photos
    backed up appear in the app; only file moves wait."""
    from photohub.scanner import scan
    from photohub.users import scan_roots
    u, h, c = chie
    d = Path(u.personal_root) / "MobileBackup" / "edge 60 pro" / "DCIM" / "Camera"
    d.mkdir(parents=True)
    (d / "PXL_1.jpg").write_bytes(make_image(1))
    (d / "PXL_2.jpg").write_bytes(make_image(2))
    (d / "PXL_2 copy.jpg").write_bytes(make_image(2))
    rep = scan(db, settings, scan_roots(db, settings), apply=True, move_files=False)
    assert rep.new == 2
    with db.session() as s:
        assert {m.owner_id for m in s.execute(select(Media)).scalars()} == {u.id}
    assert (d / "PXL_2 copy.jpg").exists()  # nothing moved
    r = c.get("/api/sync", headers=h).json()
    assert len(r["items"]) == 2
    assert {i["folder"] for i in r["items"]} == {"MobileBackup/edge 60 pro/DCIM/Camera"}


def test_takeout_in_google_drive_is_picked_up_from_cloud_sync(settings, db):
    """Takeout delivered to Drive: whole-Drive Cloud Sync puts it in /homes/<name>/<folder>/Takeout.
    Finished ZIPs are copied into the person's inbox once; unfinished ones wait."""
    import os
    import time
    from photohub.takeout import collect_from_drive_sync
    u, _ = add_user(db, settings, "chie")
    t = settings.homes_root / "chie" / "Google_Drive_chie" / "Takeout"
    t.mkdir(parents=True)
    done = t / "takeout-20261121T000000Z-001.zip"
    done.write_bytes(b"zip-1")
    old = time.time() - 3600
    os.utime(done, (old, old))
    (t / "takeout-20261121T000000Z-002.zip").write_bytes(b"still downloading")
    assert collect_from_drive_sync(db, settings, u) == 1
    assert (settings.inbox / "chie" / done.name).read_bytes() == b"zip-1"
    assert not (settings.inbox / "chie" / "takeout-20261121T000000Z-002.zip").exists()
    assert done.exists()  # check mode: the Drive copy is left alone
    (settings.inbox / "chie" / done.name).unlink()  # imported and deleted
    assert collect_from_drive_sync(db, settings, u) == 0  # not copied again


def test_takeout_from_drive_is_deleted_after_import(settings, db):
    """After an error-free real import, the inbox ZIP and the Drive original are both deleted."""
    import os
    import time
    from photohub.takeout import ArchiveGroup, _archive_parts, collect_from_drive_sync
    u, _ = add_user(db, settings, "chie")
    t = settings.homes_root / "chie" / "Google_Drive_chie" / "Takeout"
    t.mkdir(parents=True)
    z = t / "takeout-x-001.zip"
    z.write_bytes(b"zip")
    old = time.time() - 3600
    os.utime(z, (old, old))
    collect_from_drive_sync(db, settings, u)
    copy = settings.inbox / "chie" / z.name
    _archive_parts(db, settings, ArchiveGroup("takeout-x", [copy], "chie"))
    assert not copy.exists() and not z.exists()


def test_person_chooses_where_phone_photos_go(settings, db, chie):
    """Synology Photos' phone backup can be pointed elsewhere; each person picks the same folder."""
    u, h, c = chie
    own = Path(u.personal_root) / "スマホ" / "2026"
    own.mkdir(parents=True)
    f = c.get("/api/me/folders", headers=h).json()
    assert {"path": "スマホ", "depth": 1} in f["folders"] and f["backup_dir"] == ""
    assert c.put("/api/me/settings", json={"backup_dir": "../.."}, headers=h).status_code == 400
    assert c.put("/api/me/settings", json={"backup_dir": "スマホ"}, headers=h).json()["backup_dir"] == "スマホ"
    assert c.get("/api/me", headers=h).json()["takeout_target"] == "personal"  # untouched
    _up_dev(c, h, make_image(91), "PXL_91.jpg")
    process_incoming(db, settings, apply=True)
    placed = list((Path(u.personal_root) / "スマホ").rglob("PXL_91.jpg"))
    assert len(placed) == 1
    assert not (Path(u.personal_root) / "MobileBackup").exists()


def test_person_chooses_their_google_drive_folder(settings, db, chie):
    """Cloud Sync puts the Drive wherever the person chose; PhotoHub reads PhotoHub/ and Takeout/ there."""
    import os
    import time
    from photohub.takeout import collect_from_drive_sync
    u, h, c = chie
    drive = settings.homes_root / "chie" / "クラウド" / "Googleドライブ"
    (drive / "PhotoHub").mkdir(parents=True)
    (drive / "Takeout").mkdir()
    z = drive / "Takeout" / "takeout-1.zip"
    z.write_bytes(b"z")
    old = time.time() - 3600
    os.utime(z, (old, old))
    f = c.get("/api/me/folders", headers=h).json()
    assert {"path": "クラウド/Googleドライブ", "depth": 2} in f["home_folders"]
    assert all(not x["path"].startswith("Photos") for x in f["home_folders"])
    assert c.put("/api/me/settings", json={"drive_root": "クラウド/Googleドライブ"}, headers=h).status_code == 200
    assert c.get("/api/me/folders", headers=h).json()["drive_detected"] == "クラウド/Googleドライブ"
    (drive / "PhotoHub" / "PXL_5.jpg").write_bytes(make_image(5))
    assert process_incoming(db, settings, apply=True).new == 1
    from photohub.users import by_name
    assert collect_from_drive_sync(db, settings, by_name(db, "chie")) == 1


def test_drive_removed_deletes_the_synology_copy_of_the_drive_folder(settings, db, chie):
    u, h, c = chie
    whole = settings.homes_root / "chie" / "Google_Drive_chie" / "PhotoHub"
    whole.mkdir(parents=True)
    img = make_image(55)
    (whole / "PXL_55.jpg").write_bytes(img)
    fh = hashlib.sha256(img).hexdigest()
    assert c.post("/api/drive-removed", json={"hashes": [fh]}, headers=h).json()["deleted"] == 0  # not filed yet
    process_incoming(db, settings, apply=True)
    assert c.post("/api/drive-removed", json={"hashes": [fh]}, headers=h).json()["deleted"] == 1
    assert not (whole / "PXL_55.jpg").exists()
    assert _count(db) == 1  # the library copy stays


def test_drive_synced_into_photohub_inbox(settings, db):
    """Cloud Sync task with local folder photohub/inbox/drive/<name>: shown, and Takeout read there."""
    import os
    import time
    from fastapi.testclient import TestClient
    from photohub.api import create_app
    from photohub.takeout import collect_from_drive_sync
    u, token = add_user(db, settings, "reo")
    Path(u.personal_root).mkdir(parents=True)
    d = settings.inbox.parent / "drive" / "reo"
    (d / "PhotoHub").mkdir(parents=True)
    (d / "Takeout").mkdir()
    z = d / "Takeout" / "takeout-1-001.zip"
    z.write_bytes(b"z")
    old = time.time() - 3600
    os.utime(z, (old, old))
    c = TestClient(create_app(settings, db))
    f = c.get("/api/me/folders", headers={"Authorization": f"Bearer {token}"}).json()
    assert f["drive_detected"].endswith("inbox/drive/reo")
    assert collect_from_drive_sync(db, settings, u) == 1


def test_deep_folders_for_drive_photos_and_takeout(settings, db, chie):
    """Someone's Cloud Sync lives deep in their home: both folders can be chosen at any depth."""
    import os
    import time
    from photohub.takeout import collect_from_drive_sync
    from photohub.users import by_name
    u, h, c = chie
    deep = settings.homes_root / "chie" / "a" / "b" / "c"
    (deep / "写真").mkdir(parents=True)
    (deep / "TO").mkdir()
    z = deep / "TO" / "takeout-9-001.zip"
    z.write_bytes(b"z")
    old = time.time() - 3600
    os.utime(z, (old, old))
    b = c.get("/api/me/browse", params={"base": "home", "path": "a/b/c"}, headers=h).json()
    assert set(b["folders"]) == {"写真", "TO"}
    assert c.get("/api/me/browse", params={"path": "../.."}, headers=h).status_code == 404
    r = c.put("/api/me/settings", json={"drive_photos_dir": "a/b/c/写真", "takeout_dir": "a/b/c/TO"}, headers=h).json()
    assert r["drive_photos_dir"] == "a/b/c/写真" and r["takeout_dir"] == "a/b/c/TO"
    (deep / "写真" / "PXL_8.jpg").write_bytes(make_image(8))
    assert process_incoming(db, settings, apply=True).new == 1
    assert collect_from_drive_sync(db, settings, by_name(db, "chie")) == 1


def test_takeout_leaves_drive_once_copied(settings, db):
    """Live mode: as soon as the copy on the Synology is complete, the ZIP leaves the Drive folder."""
    import os
    import time
    from photohub.takeout import collect_from_drive_sync
    settings.apply = True
    u, _ = add_user(db, settings, "chie")
    t = settings.homes_root / "chie" / "Takeout"
    t.mkdir(parents=True)
    z = t / "takeout-5-001.zip"
    z.write_bytes(b"zip")
    old = time.time() - 3600
    os.utime(z, (old, old))
    assert collect_from_drive_sync(db, settings, u) == 1
    assert not z.exists() and (settings.inbox / "chie" / z.name).read_bytes() == b"zip"


def test_dates_from_the_phone(settings, db, chie):
    """A PNG without a date inside: the phone's date is used (upload header), and old wrong dates get fixed."""
    import io
    from datetime import datetime
    from PIL import Image
    u, h, c = chie
    buf = io.BytesIO()
    Image.new("RGB", (40, 30), (200, 10, 10)).save(buf, "PNG")
    data = buf.getvalue()
    when = int(datetime(2021, 5, 3, 12, 0).timestamp() * 1000)
    r = c.post("/api/upload", content=data, headers={**h, "X-File-Hash": hashlib.sha256(data).hexdigest(),
                                                     "X-File-Name": "Screenshot_1.png", "X-Taken-At": str(when)})
    assert r.status_code == 200
    process_incoming(db, settings, apply=True)
    item = c.get("/api/sync", headers=h).json()["items"][0]
    assert abs(item["taken_at"] - when) < 2000
    # an older upload without the header got "today": the phone fixes it later
    buf2 = io.BytesIO()
    Image.new("RGB", (40, 30), (10, 200, 10)).save(buf2, "PNG")
    d2 = buf2.getvalue()
    c.post("/api/upload", content=d2, headers={**h, "X-File-Hash": hashlib.sha256(d2).hexdigest(), "X-File-Name": "b.png"})
    process_incoming(db, settings, apply=True)
    fh2 = hashlib.sha256(d2).hexdigest()
    old = int(datetime(2019, 1, 2, 9, 0).timestamp() * 1000)
    assert c.post("/api/media/dates", json={"items": [{"file_hash": fh2, "taken_at": old}]}, headers=h).json()["fixed"] == 1
    items = {i["name"]: i for i in c.get("/api/sync", headers=h).json()["items"]}
    assert abs(items["b.png"]["taken_at"] - old) < 2000
    # a real (older) date is never moved
    assert c.post("/api/media/dates", json={"items": [{"file_hash": fh2, "taken_at": old - 86400000 * 400}]}, headers=h).json()["fixed"] == 0


def test_mts_video_made_playable(settings, db, chie):
    """An AVCHD-style .MTS (MPEG-TS, H.264 + AC-3) gets a phone-friendly MP4 on first play."""
    import shutil
    import subprocess
    import pytest
    if not shutil.which("ffmpeg"):
        pytest.skip("ffmpeg not installed")
    u, h, c = chie
    src = Path(u.personal_root) / "AVCHD" / "00013.MTS"
    src.parent.mkdir(parents=True)
    r = subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-f", "lavfi",
                        "-i", "sine=frequency=440", "-t", "1", "-c:v", "libx264", "-c:a", "ac3", "-f", "mpegts", str(src)])
    if r.returncode != 0:
        pytest.skip("ffmpeg without libx264/ac3")
    from photohub.scanner import scan
    from photohub.users import scan_roots
    scan(db, settings, scan_roots(db, settings), apply=True)
    item = c.get("/api/sync", headers=h).json()["items"][0]
    assert c.get(f"/api/playable/{item['hash']}", headers=h).status_code == 404
    assert c.post(f"/api/playable/{item['hash']}", headers=h).json()["ok"]
    r = c.get(f"/api/playable/{item['hash']}", headers=h)
    assert r.status_code == 200 and r.content[4:8] == b"ftyp"


def test_synology_thumbnails_are_not_photos(settings, db, chie):
    """@eaDir/SYNOFILE_THUMB_*.jpg next to Drive files are Synology's own thumbnails: skipped, and old ones removed."""
    from photohub.organize import remove_system_thumbs
    from photohub.db import Media
    u, h, c = chie
    drive = settings.homes_root / "chie" / "PhotoHub"
    (drive / "@eaDir" / "a.jpg").mkdir(parents=True)
    (drive / "a.jpg").write_bytes(make_image(70))
    (drive / "@eaDir" / "a.jpg" / "SYNOFILE_THUMB_M.jpg").write_bytes(make_image(71))
    assert process_incoming(db, settings, apply=True).new == 1
    # one that an older version imported
    junk = Path(u.personal_root) / "MobileBackup" / "x" / "SYNOFILE_THUMB_XL.jpg"
    junk.parent.mkdir(parents=True)
    junk.write_bytes(make_image(72))
    from photohub.scanner import scan as _scan
    with db.session() as s:
        from photohub.ingest import analyse, new_media
        cand = analyse([junk], settings)[0]
        s.add(new_media(cand, junk, junk.stat().st_mtime and __import__("datetime").datetime.utcnow(), u.id))
    assert remove_system_thumbs(db, settings) == 1
    assert not junk.exists()
    with db.session() as s:
        assert {Path(m.nas_path).name for m in s.execute(select(Media).where(Media.status == "active")).scalars()} == {"a.jpg"}


def test_aliases_link_the_drive_original_to_the_synology_copy(settings, db, chie):
    """Google Photos rewrites files: the Synology keeps the Takeout copy, the phone's original
    (other bytes, same picture) in Drive is merged into it. The phone learns both are one photo."""
    u, h, c = chie
    img = make_image(7)
    takeout = Path(u.personal_root) / "GooglePhotos" / "PXL_7.jpg"
    takeout.parent.mkdir(parents=True)
    takeout.write_bytes(img + b"google")          # same pixels, other bytes
    from photohub.scanner import scan
    from photohub.users import scan_roots
    scan(db, settings, scan_roots(db, settings), apply=True)
    d = drive_dir(settings, u) / "2026" / "PXL_7.jpg"
    d.parent.mkdir(parents=True)
    d.write_bytes(img)
    settings.stable_seconds = 0
    process_incoming(db, settings, apply=True)
    assert _count(db) == 1
    with db.session() as s:
        m = s.execute(select(Media)).scalar_one()
    assert c.get("/api/aliases", headers=h).json()["items"] == [[hashlib.sha256(img).hexdigest(), m.content_hash]]
