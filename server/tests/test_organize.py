"""写真の整理: trash / restore / purge, move between spaces, duplicates, blur."""
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import select

from photohub.api import create_app
from photohub.db import Media
from photohub.scanner import scan
from photohub.users import add_user, scan_roots

from photohub.sample import make_image


def _setup(settings, db):
    reo, tr = add_user(db, settings, "reo")
    chie, tc = add_user(db, settings, "chie")
    for u in (reo, chie):
        Path(u.personal_root).mkdir(parents=True)
    mine = Path(reo.personal_root) / "2024" / "a.jpg"
    mine.parent.mkdir(parents=True)
    mine.write_bytes(make_image(1))
    shared = settings.library / "trip" / "b.jpg"
    shared.parent.mkdir(parents=True)
    shared.write_bytes(make_image(2))
    theirs = Path(chie.personal_root) / "c.jpg"
    theirs.write_bytes(make_image(3))
    scan(db, settings, scan_roots(db, settings), apply=True)
    c = TestClient(create_app(settings, db))
    with db.session() as s:
        h = {Path(m.nas_path).name: m.content_hash for m in s.execute(select(Media)).scalars()}
    return c, {"Authorization": f"Bearer {tr}"}, {"Authorization": f"Bearer {tc}"}, h, mine, shared, theirs


def test_trash_restore_and_purge(settings, db):
    c, hr, hc, h, mine, shared, theirs = _setup(settings, db)
    # reo may delete own and shared photos, not chie's
    r = c.post("/api/media/delete", json={"hashes": [h["a.jpg"], h["b.jpg"], h["c.jpg"]]}, headers=hr).json()
    assert r["trashed"] == 2 and r["days"] == 30
    assert not mine.exists() and not shared.exists() and theirs.exists()
    # gone from every phone at the next sync, and the phone will not send it again
    items = {i["name"]: i for i in c.get("/api/sync", headers=hr).json()["items"]}
    assert items["a.jpg"]["deleted"]
    fh = __import__("hashlib").sha256(make_image(1)).hexdigest()
    assert c.post("/api/have", json={"hashes": [fh]}, headers=hr).json()[fh] == "deleted"
    # anyone in the family sees the shared one in the trash
    t = c.get("/api/trash", headers=hc).json()
    assert [i["name"] for i in t["items"]] == ["b.jpg"] and t["items"][0]["by"] == "reo"
    assert c.post("/api/trash/restore", json={"hashes": [h["b.jpg"]]}, headers=hc).json()["restored"] == 1
    assert shared.exists()
    assert c.post("/api/trash/empty", json={"hashes": [h["a.jpg"]]}, headers=hr).json()["deleted"] == 1
    assert not list((settings.trash / "bin").rglob("a.jpg"))
    assert c.get("/api/trash", headers=hr).json()["items"] == []


def test_move_between_mine_and_shared(settings, db):
    c, hr, hc, h, mine, shared, theirs = _setup(settings, db)
    assert c.post("/api/media/move", json={"hashes": [h["a.jpg"]], "to": "shared"}, headers=hr).json()["moved"] == 1
    assert not mine.exists() and list(settings.library.rglob("a.jpg"))
    # now chie sees it as a shared photo
    names = {i["name"]: i["space"] for i in c.get("/api/sync", headers=hc).json()["items"] if not i["deleted"]}
    assert names["a.jpg"] == "shared"
    assert c.post("/api/media/move", json={"hashes": [h["b.jpg"]], "to": "mine"}, headers=hc).json()["moved"] == 1
    assert list(Path(settings.homes_root / "chie" / "Photos" / "PhotoHub").rglob("b.jpg"))


def test_exact_duplicates_and_deleted_in_synology(settings, db):
    c, hr, hc, h, mine, shared, theirs = _setup(settings, db)
    copy = Path(settings.homes_root / "reo" / "Photos" / "old" / "a copy.jpg")
    copy.parent.mkdir(parents=True)
    copy.write_bytes(mine.read_bytes())
    scan(db, settings, scan_roots(db, settings), apply=True)
    d = c.get("/api/duplicates", headers=hr).json()["items"]
    assert [x["path"] for x in d] == [str(copy)]
    assert c.get("/api/duplicates", headers=hc).json()["items"] == []  # not chie's to tidy
    assert c.post("/api/duplicates/remove", json={"paths": [str(copy)]}, headers=hr).json()["removed"] == 1
    assert not copy.exists() and mine.exists()
    # a photo deleted in Synology Photos disappears from the app too
    theirs.unlink()
    scan(db, settings, scan_roots(db, settings), apply=True)
    assert {i["name"]: i["deleted"] for i in c.get("/api/sync", headers=hc).json()["items"]}["c.jpg"]


def test_sharpness_is_low_for_flat_images(tmp_path):
    from PIL import Image, ImageFilter
    from photohub.organize import sharpness
    import numpy as np
    sharp = tmp_path / "s.png"
    Image.fromarray((np.indices((64, 64)).sum(0) % 2 * 255).astype("uint8")).save(sharp)
    soft = tmp_path / "b.png"
    Image.open(sharp).filter(ImageFilter.GaussianBlur(6)).save(soft)
    assert sharpness(sharp) > 1000 > 60 > sharpness(soft)
