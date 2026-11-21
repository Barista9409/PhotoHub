"""Family use: shared space for everyone, personal spaces for their owner only,
one Google account per person, admins organise the shared space."""
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from sqlalchemy import select

from photohub import similar, storage
from photohub.api import create_app
from photohub.db import Location, Media, SimilarGroup
from photohub.sample import default_items, make_image, write_takeout_zip
from photohub.scanner import scan
from photohub.takeout import import_inbox
from photohub.users import add_user, scan_roots

from .conftest import needs_exiftool, needs_gpth


@pytest.fixture
def family(settings, db):
    reo, t_reo = add_user(db, settings, "reo", admin=True)
    chie, t_chie = add_user(db, settings, "chie")
    for u in (reo, chie):
        Path(u.personal_root).mkdir(parents=True, exist_ok=True)
    c = TestClient(create_app(settings, db))
    return {"reo": (reo, {"Authorization": f"Bearer {t_reo}"}),
            "chie": (chie, {"Authorization": f"Bearer {t_chie}"}), "client": c}


def _put(root: Path, rel: str, seed: int, **kw) -> Path:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(make_image(seed, **kw))
    return p


def _hashes(c, h):
    out, cur = [], 0
    while True:
        r = c.get(f"/api/sync?cursor={cur}", headers=h).json()
        out += r["items"]
        cur = r["cursor"]
        if not r["has_more"]:
            return out


def test_personal_space_is_private(settings, db, family):
    reo, h_reo = family["reo"]
    chie, h_chie = family["chie"]
    c = family["client"]
    _put(settings.library, "2024/shared.jpg", 1)
    _put(Path(reo.personal_root), "MobileBackup/reo.jpg", 2)
    _put(Path(chie.personal_root), "MobileBackup/chie.jpg", 3)
    scan(db, settings, scan_roots(db, settings), apply=True)

    names = lambda h: sorted(i["name"] for i in _hashes(c, h))  # noqa: E731
    assert names(h_reo) == ["reo.jpg", "shared.jpg"]
    assert names(h_chie) == ["chie.jpg", "shared.jpg"]
    with db.session() as s:
        reo_hash = s.execute(select(Media.content_hash).where(Media.nas_path.like("%reo.jpg"))).scalar_one()
    # chie cannot fetch reo's personal photo in any form; it looks like "not found"
    for path in ("thumb", "preview", "original"):
        assert c.get(f"/api/{path}/{reo_hash}", headers=h_chie).status_code == 404
        assert c.get(f"/api/{path}/{reo_hash}", headers=h_reo).status_code == 200
    body = c.post("/api/thumbs", json={"hashes": [reo_hash]}, headers=h_chie).content
    assert body.endswith(b"\x00\x00\x00\x00")  # empty entry
    assert c.get("/api/status", headers=h_chie).json()["photos"] == 2


def test_same_photo_in_two_personal_spaces_is_visible_to_both(settings, db, family):
    reo, h_reo = family["reo"]
    chie, h_chie = family["chie"]
    a = _put(Path(reo.personal_root), "a/IMG.jpg", 7)
    b = Path(chie.personal_root) / "b" / "IMG.jpg"
    b.parent.mkdir(parents=True)
    b.write_bytes(a.read_bytes())
    rep = scan(db, settings, scan_roots(db, settings), apply=True)
    assert rep.new == 1 and len(rep.duplicates) == 1
    c = family["client"]
    assert len(_hashes(c, h_reo)) == 1 and len(_hashes(c, h_chie)) == 1


def test_admin_only_for_shared_and_events(settings, db, family):
    reo, h_reo = family["reo"]
    chie, h_chie = family["chie"]
    c = family["client"]
    a = _put(settings.library, "s/A.jpg", 10, quality=95)
    with Image.open(a) as img:
        img.resize((320, 240)).save(settings.library / "s/A_small.jpg", "JPEG", quality=60)
    pa = _put(Path(chie.personal_root), "p/B.jpg", 20, quality=95)
    with Image.open(pa) as img:
        img.resize((320, 240)).save(Path(chie.personal_root) / "p/B_small.jpg", "JPEG", quality=60)
    scan(db, settings, scan_roots(db, settings), apply=True)
    assert similar.refresh(db, settings) == 2
    with db.session() as s:
        groups = {g.owner_id: g.id for g in s.execute(select(SimilarGroup)).scalars()}
        keep_shared = s.execute(select(Media.id).where(Media.nas_path == str(a))).scalar_one()
        keep_chie = s.execute(select(Media.id).where(Media.nas_path == str(pa))).scalar_one()

    # what each person is offered
    assert {g["space"] for g in c.get("/api/similar", headers=h_reo).json()} == {"shared"}
    assert {g["space"] for g in c.get("/api/similar", headers=h_chie).json()} == {"mine"}
    # chie may not organise the shared space; reo may not organise chie's personal space
    r = c.post(f"/api/similar/{groups[None]}/resolve", json={"keep": [keep_shared]}, headers=h_chie)
    assert r.status_code == 403
    r = c.post(f"/api/similar/{groups[chie.id]}/resolve", json={"keep": [keep_chie]}, headers=h_reo)
    assert r.status_code == 403
    assert c.post(f"/api/similar/{groups[chie.id]}/resolve", json={"keep": [keep_chie]}, headers=h_chie).status_code == 200
    assert c.get("/api/events", headers=h_chie).status_code == 403
    assert c.get("/api/events", headers=h_reo).status_code == 200
    assert c.post("/api/amazon/heartbeat", headers=h_chie).status_code == 403
    assert c.get("/api/me", headers=h_chie).json() == {"id": chie.id, "name": "chie", "admin": False,
                                                       "takeout_target": "personal"}


@needs_gpth
@needs_exiftool
def test_each_persons_takeout_goes_to_their_space(settings, db, family):
    reo, h_reo = family["reo"]
    chie, h_chie = family["chie"]
    items = default_items(6)
    write_takeout_zip(settings.inbox / "reo" / "takeout-r-001.zip", items[:4])
    # chie's Google has 2 of the same photos (e.g. shared album) + 2 of her own
    from photohub.sample import SampleItem
    own = [SampleItem(name=f"CHIE_{i}.jpg", taken_utc=datetime(2023, 6, 1, i), lat=None, lon=None, seed=900 + i)
           for i in range(2)]
    write_takeout_zip(settings.inbox / "chie" / "takeout-c-001.zip", items[2:4] + own)
    # a ZIP without an owner folder is not imported once users exist
    write_takeout_zip(settings.inbox / "takeout-loose-001.zip", items[4:])
    reps = import_inbox(db, settings, apply=True)
    assert sorted((r.new, r.duplicate + r.merged) for r in reps) == [(2, 2), (4, 0)]
    assert (settings.inbox / "takeout-loose-001.zip").exists()
    assert len(list(Path(reo.personal_root, "PhotoHub").rglob("*.jpg"))) == 4
    assert len(list(Path(chie.personal_root, "PhotoHub").rglob("*.jpg"))) == 2
    assert not list(settings.library.rglob("*.jpg"))

    c = family["client"]
    assert len(_hashes(c, h_reo)) == 4
    chie_items = _hashes(c, h_chie)
    assert len(chie_items) == 4  # her 2 + the 2 she also has in her Google
    assert sum(1 for i in chie_items if i["space"] == "granted") == 2
    assert all("google" in i["stores"] for i in chie_items)
    # Google is per person: 4 records for reo, 4 for chie (2 of them on the same photos)
    with db.session() as s:
        g = s.execute(select(Location).where(Location.store == "google")).scalars().all()
    assert sorted(x.user_id for x in g) == sorted([reo.id] * 4 + [chie.id] * 4)


@needs_exiftool
def test_google_candidates_are_per_person(settings, db, family):
    import subprocess
    reo, h_reo = family["reo"]
    chie, h_chie = family["chie"]
    old = datetime.now() - timedelta(days=500)
    p = _put(settings.library, "old/x.jpg", 5)
    subprocess.run(["exiftool", "-q", "-overwrite_original", f"-DateTimeOriginal={old:%Y:%m:%d %H:%M:%S}", str(p)], check=True)
    scan(db, settings, scan_roots(db, settings), apply=True)
    with db.session() as s:
        m = s.execute(select(Media)).scalar_one()
        storage.set_location(s, m.id, "google", "present", user_id=reo.id)
    storage.fill_outbox(db, settings, apply=True)
    with db.session() as s:
        for loc in s.execute(select(Location).where(Location.store == "amazon")).scalars():
            loc.seen_at = datetime.utcnow() - timedelta(hours=3)
    storage.record_heartbeat(db)
    storage.fill_outbox(db, settings, apply=True)
    storage.verify_nas(db, settings)
    c = family["client"]
    mine = c.get("/api/google-candidates", headers=h_reo).json()
    assert len(mine) == 1 and mine[0]["photos"] == 1
    assert c.get("/api/google-candidates", headers=h_chie).json() == []
    assert c.post(f"/api/google-candidates/{mine[0]['id']}/confirm", headers=h_chie).status_code == 404
    assert c.post(f"/api/google-candidates/{mine[0]['id']}/confirm", headers=h_reo).json() == {"marked_removed": 1}


def test_setup_qr_invite_flow(settings, db):
    from urllib.parse import parse_qs, urlparse

    from photohub.users import create_invite
    settings.lan_url, settings.remote_url = "http://192.168.1.10:8765", "http://100.1.2.3:8765"
    add_user(db, settings, "chie")
    code, link = create_invite(db, settings, "chie")
    q = parse_qs(urlparse(link).query)
    assert link.startswith("photohub://setup?") and q["code"] == [code] and q["user"] == ["chie"]
    assert "token" not in link
    c = TestClient(create_app(settings, db))
    r = c.post("/api/setup/redeem", json={"code": code, "device": "Pixel"})
    assert r.status_code == 200
    body = r.json()
    assert body["user"] == "chie" and body["lan"] == settings.lan_url and body["remote"] == settings.remote_url
    h = {"Authorization": f"Bearer {body['token']}"}
    assert c.get("/api/me", headers=h).json()["name"] == "chie"
    # single use
    assert c.post("/api/setup/redeem", json={"code": code}).status_code == 400
    # a second phone gets its own token; both keep working
    code2, _ = create_invite(db, settings, "chie")
    t2 = c.post("/api/setup/redeem", json={"code": code2, "device": "Tablet"}).json()["token"]
    assert c.get("/api/me", headers={"Authorization": f"Bearer {t2}"}).status_code == 200
    assert c.get("/api/me", headers=h).status_code == 200
    # brute force is throttled
    for _ in range(10):
        c.post("/api/setup/redeem", json={"code": "nope"})
    assert c.post("/api/setup/redeem", json={"code": "nope"}).status_code == 429


def test_dsm_login(settings, db, monkeypatch):
    """Log in with the DSM user name/password; PhotoHub asks DSM and issues a phone token."""
    from photohub import dsm_login
    users = {"reo": ("pw-reo", None), "chie": ("pw-chie", "123456")}  # chie uses 2-step verification

    def fake_check(_settings, username, password, otp=None):
        if username not in users or users[username][0] != password:
            return "bad_password"
        need = users[username][1]
        if need and not otp:
            return "otp_required"
        if need and otp != need:
            return "bad_otp"
        return "ok"

    monkeypatch.setattr(dsm_login, "check_password", fake_check)
    settings.admins = ["reo"]
    c = TestClient(create_app(settings, db))
    r = c.post("/api/login", json={"username": "reo", "password": "pw-reo", "device": "Pixel"})
    assert r.status_code == 200 and r.json()["user"] == "reo" and r.json()["admin"] is True
    assert c.get("/api/me", headers={"Authorization": f"Bearer {r.json()['token']}"}).json()["name"] == "reo"
    # first login created the user with a Takeout inbox and personal space
    assert (settings.inbox / "reo").is_dir()

    assert c.post("/api/login", json={"username": "chie", "password": "pw-chie"}).json()["detail"] == "otp_required"
    assert c.post("/api/login", json={"username": "chie", "password": "pw-chie", "otp": "000000"}).json()["detail"] == "bad_otp"
    r = c.post("/api/login", json={"username": "chie", "password": "pw-chie", "otp": "123456"})
    assert r.status_code == 200 and r.json()["admin"] is False
    assert c.post("/api/login", json={"username": "chie", "password": "wrong"}).status_code == 401
    assert c.post("/api/login", json={"username": "admin", "password": "x"}).json()["detail"] == "denied"
    settings.auto_users = False
    users["mio"] = ("pw", None)
    assert c.post("/api/login", json={"username": "mio", "password": "pw"}).json()["detail"] == "not_registered"
    for _ in range(10):
        c.post("/api/login", json={"username": "reo", "password": "bad"})
    assert c.post("/api/login", json={"username": "reo", "password": "pw-reo"}).status_code == 429


def test_dsm_check_password_against_fake_dsm(settings, monkeypatch):
    """The real request/response handling, with httpx answered by a fake DSM."""
    import httpx

    from photohub import dsm_login
    seen = []

    def handler(req: httpx.Request) -> httpx.Response:
        form = dict(x.split("=", 1) for x in req.content.decode().split("&"))
        seen.append(form["method"])
        if form["method"] == "logout":
            return httpx.Response(200, json={"success": True})
        if form.get("passwd") == "good":
            return httpx.Response(200, json={"success": True, "data": {"sid": "S"}})
        return httpx.Response(200, json={"success": False, "error": {"code": 403 if form.get("passwd") == "otp" else 400}})

    real = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kw: real(transport=httpx.MockTransport(handler), **kw))
    assert dsm_login.check_password(settings, "reo", "good") == "ok"
    assert seen == ["login", "logout"]  # never keeps a DSM session
    assert dsm_login.check_password(settings, "reo", "otp") == "otp_required"
    assert dsm_login.check_password(settings, "reo", "bad") == "bad_password"


def test_each_person_chooses_takeout_destination(settings, db, family):
    reo, h_reo = family["reo"]
    chie, h_chie = family["chie"]
    c = family["client"]
    assert c.get("/api/me", headers=h_chie).json()["takeout_target"] == "personal"
    assert c.put("/api/me/settings", json={"takeout_target": "shared"}, headers=h_chie).status_code == 200
    assert c.get("/api/me", headers=h_chie).json()["takeout_target"] == "shared"
    assert c.get("/api/me", headers=h_reo).json()["takeout_target"] == "personal"  # only her own
    assert c.put("/api/me/settings", json={"takeout_target": "x"}, headers=h_chie).status_code == 400
    from photohub.ingest import takeout_destination
    from photohub.users import by_name
    assert takeout_destination(settings, by_name(db, "chie")) == (settings.library, None)
    root, owner = takeout_destination(settings, by_name(db, "reo"))
    assert owner == reo.id and str(root).endswith("Photos/PhotoHub")
