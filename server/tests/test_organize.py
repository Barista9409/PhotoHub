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
    assert t["items"][0]["albums"] == [] and t["items"][0]["folder"] == "trip"
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
    copy = Path(settings.homes_root / "reo" / "Photos" / "2023" / "a copy.jpg")
    copy.parent.mkdir(parents=True)
    copy.write_bytes(mine.read_bytes())
    scan(db, settings, scan_roots(db, settings), apply=True)
    from photohub import organize
    # looked for ahead of time (the daemon); the list just reads what was found
    assert organize.refresh_duplicates(db, settings) == 1
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


def test_copy_between_mine_and_family(settings, db):
    from pathlib import Path
    from fastapi.testclient import TestClient
    from sqlalchemy import select
    from photohub.api import create_app
    from photohub.db import Album, Media
    from photohub.sample import make_image
    from photohub.scanner import scan
    from photohub.users import add_user, scan_roots
    u, token = add_user(db, settings, "reo")
    own = Path(u.personal_root) / "a.jpg"
    own.parent.mkdir(parents=True, exist_ok=True)
    own.write_bytes(make_image(71))
    fam = settings.library / "b.jpg"
    fam.parent.mkdir(parents=True, exist_ok=True)
    fam.write_bytes(make_image(72))
    scan(db, settings, scan_roots(db, settings), apply=True)
    c = TestClient(create_app(settings, db))
    h = {"Authorization": f"Bearer {token}"}
    with db.session() as s:
        ha = s.execute(select(Media.content_hash).where(Media.nas_path == str(own))).scalar_one()
        hb = s.execute(select(Media.content_hash).where(Media.nas_path == str(fam))).scalar_one()
    # mine -> family: both files stay, the photo is now the family's
    assert c.post("/api/media/copy", json={"hashes": [ha], "to": "shared"}, headers=h).json()["copied"] == 1
    assert own.exists()
    with db.session() as s:
        m = s.execute(select(Media).where(Media.content_hash == ha)).scalar_one()
        assert m.owner_id is None and Path(m.nas_path).is_relative_to(settings.library) and Path(m.nas_path).exists()
    # family -> mine: a copy in their Photos, the family one stays
    assert c.post("/api/media/copy", json={"hashes": [hb], "to": "mine"}, headers=h).json()["copied"] == 1
    assert fam.exists() and list((Path(u.personal_root) / "PhotoHub").rglob("b*.jpg"))
    # the copies made on purpose are not offered as duplicates
    scan(db, settings, scan_roots(db, settings), apply=True)
    from photohub import organize
    organize.refresh_duplicates(db, settings)
    assert c.get("/api/duplicates", headers=h).json()["items"] == []
    # an album to the family
    aid = c.post("/api/albums", json={"name": "旅行", "hashes": [hb]}, headers=h).json()["id"]
    r = c.post(f"/api/albums/{aid}/copy", json={"to": "shared"}, headers=h).json()
    with db.session() as s:
        assert s.get(Album, r["album_id"]).owner_id is None


def test_family_share_without_copy(settings, db):
    from pathlib import Path
    from fastapi.testclient import TestClient
    from sqlalchemy import select
    from photohub.api import create_app
    from photohub.db import Media
    from photohub.sample import make_image
    from photohub.scanner import scan
    from photohub.users import add_user, scan_roots
    u, tok_u = add_user(db, settings, "reo")
    v, tok_v = add_user(db, settings, "chie")
    own = Path(u.personal_root) / "x.jpg"
    own.parent.mkdir(parents=True, exist_ok=True)
    own.write_bytes(make_image(81))
    scan(db, settings, scan_roots(db, settings), apply=True)
    c = TestClient(create_app(settings, db))
    hu, hv = {"Authorization": f"Bearer {tok_u}"}, {"Authorization": f"Bearer {tok_v}"}
    with db.session() as s:
        hx = s.execute(select(Media.content_hash)).scalar_one()
    assert not c.get("/api/sync", headers=hv).json()["items"]          # chie cannot see reo's photo
    assert c.post("/api/media/family", json={"hashes": [hx]}, headers=hu).json()["changed"] == 1
    it = c.get("/api/sync", headers=hv).json()["items"]
    assert [i["space"] for i in it] == ["family"]                       # now she can
    assert own.exists() and len(list(settings.library.rglob("x*.jpg"))) == 0   # no copy was made
    c.post("/api/media/family", json={"hashes": [hx], "on": False}, headers=hu)
    assert [i["deleted"] or i["space"] for i in c.get("/api/sync", headers=hv).json()["items"]] in ([], [True])


def test_trash_on_another_volume_moves_in_background(settings, db, monkeypatch):
    from photohub import organize
    c, hr, hc, h, mine, shared, theirs = _setup(settings, db)
    monkeypatch.setattr(organize, "_quick_move", lambda src, dst: False)   # as if the trash were another volume
    monkeypatch.setattr(organize.threading, "Thread", lambda **kw: type("T", (), {"start": lambda self: None})())
    # answered at once: gone for everyone, the file still waiting to be moved
    assert c.post("/api/media/delete", json={"hashes": [h["a.jpg"], h["b.jpg"]]}, headers=hr).json()["trashed"] == 2
    assert mine.exists()
    assert {i["name"]: i["deleted"] for i in c.get("/api/sync", headers=hr).json()["items"]}["a.jpg"]
    # restored before the move: stays where it was (no "a.1.jpg")
    assert c.post("/api/trash/restore", json={"hashes": [h["a.jpg"]]}, headers=hr).json()["restored"] == 1
    assert mine.exists() and not list(mine.parent.glob("a.1.jpg"))
    # the background move finishes the other one
    assert organize.move_pending(db, settings) == 1
    assert not shared.exists() and list((settings.trash / "bin").rglob("b.jpg"))
    assert c.post("/api/trash/restore", json={"hashes": [h["b.jpg"]]}, headers=hr).json()["restored"] == 1
    assert shared.exists()


def test_blurry_measured_ahead_of_time(settings, db):
    import numpy as np
    from PIL import Image
    from photohub import derivatives, organize
    c, hr, hc, h, mine, shared, theirs = _setup(settings, db)
    rng = np.random.default_rng(0)
    pics = {"a.jpg": np.full((64, 64), 128, np.uint8),                       # flat: blurry
            "b.jpg": rng.integers(0, 255, (64, 64), dtype=np.uint8)}        # busy: sharp
    for name, px in pics.items():
        t = derivatives.path_for(settings, "thumb", h[name])
        t.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray(px).save(t, "WEBP")
    # nothing measured yet: the list does not measure while the person waits
    assert c.get("/api/suggestions/blurry", headers=hr).json()["items"] == []
    assert organize.measure_sharpness(db, settings) == 2     # c.jpg has no thumbnail yet
    assert organize.measure_sharpness(db, settings) == 0     # done once
    assert [i["hash"] for i in c.get("/api/suggestions/blurry", headers=hr).json()["items"]] == [h["a.jpg"]]


def test_album_move_between_mine_and_family(settings, db):
    from photohub.db import Album
    c, hr, hc, h, mine, shared, theirs = _setup(settings, db)
    aid = c.post("/api/albums", json={"name": "旅行", "hashes": [h["a.jpg"]], "shared": False}, headers=hr).json()["id"]
    r = c.post(f"/api/albums/{aid}/move", json={"to": "shared"}, headers=hr).json()
    assert r["moved"] == 1 and not mine.exists() and list(settings.library.rglob("a.jpg"))
    with db.session() as s:
        assert s.get(Album, aid) is None
        assert s.get(Album, r["album_id"]).owner_id is None and s.get(Album, r["album_id"]).name == "旅行"
    # already the family's: refused, nothing changes
    assert c.post(f"/api/albums/{r['album_id']}/move", json={"to": "shared"}, headers=hr).status_code == 400
    # and back to reo
    back = c.post(f"/api/albums/{r['album_id']}/move", json={"to": "mine"}, headers=hr).json()
    assert back["moved"] == 1 and list((Path(settings.homes_root) / "reo" / "Photos" / "PhotoHub").rglob("a.jpg"))


def test_duplicates_keep_the_copy_in_a_named_folder(settings, db):
    """Same picture, other name: the phone backup's copy goes, the one filed in 「パリ編」 stays (and the
    photo then points at it); two named folders: both stay."""
    from photohub import organize
    reo, tr = add_user(db, settings, "reo")
    root = Path(reo.personal_root)
    backup = root / "MobileBackup" / "iPhone" / "2022" / "10" / "IMG_20221018_201305.JPG"
    backup.parent.mkdir(parents=True)
    backup.write_bytes(make_image(5))
    scan(db, settings, scan_roots(db, settings), apply=True)
    filed = root / "パリ編" / "IMG_3582.JPG"
    filed.parent.mkdir(parents=True)
    filed.write_bytes(make_image(5))
    again = root / "旅行アルバム" / "IMG_3582 (1).JPG"
    again.parent.mkdir(parents=True)
    again.write_bytes(make_image(5))
    scan(db, settings, scan_roots(db, settings), apply=True)
    organize.refresh_duplicates(db, settings)
    c = TestClient(create_app(settings, db))
    h = {"Authorization": f"Bearer {tr}"}
    d = c.get("/api/duplicates", headers=h).json()["items"]
    assert [(x["path"], x["keep"]) for x in d] == [(str(backup), str(filed))]   # 旅行アルバム's copy is not offered
    assert c.post("/api/duplicates/remove", json={"paths": [str(backup)]}, headers=h).json()["removed"] == 1
    assert not backup.exists() and filed.exists() and again.exists()
    with db.session() as s:
        assert s.execute(select(Media)).scalar_one().nas_path == str(filed)
