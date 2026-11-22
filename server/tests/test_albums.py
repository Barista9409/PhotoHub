"""Albums from the app, and mirroring Synology Photos albums (API faked)."""
import json
from pathlib import Path

import httpx
from fastapi.testclient import TestClient
from sqlalchemy import select

from photohub import dsm_login
from photohub.api import create_app
from photohub.db import Media, User
from photohub.sample import make_image
from photohub.scanner import scan
from photohub.users import add_user, scan_roots


def _lib(settings, db):
    reo, tr = add_user(db, settings, "reo")
    Path(reo.personal_root).mkdir(parents=True)
    for i, n in enumerate(["a.jpg", "b.jpg", "c.jpg"]):
        p = Path(reo.personal_root) / "2024" / n
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(make_image(10 + i))
    scan(db, settings, scan_roots(db, settings), apply=True)
    with db.session() as s:
        ms = {Path(m.nas_path).name: (m.content_hash, m.bytes) for m in s.execute(select(Media)).scalars()}
    return TestClient(create_app(settings, db)), {"Authorization": f"Bearer {tr}"}, ms


def test_albums_from_the_app(settings, db):
    c, h, ms = _lib(settings, db)
    aid = c.post("/api/albums", json={"name": "旅行", "hashes": [ms["a.jpg"][0]]}, headers=h).json()["id"]
    assert c.post(f"/api/albums/{aid}/add", json={"hashes": [ms["b.jpg"][0]]}, headers=h).json()["changed"] == 1
    assert c.post(f"/api/albums/{aid}/remove", json={"hashes": [ms["a.jpg"][0]]}, headers=h).json()["changed"] == 1
    assert c.put(f"/api/albums/{aid}", json={"name": "沖縄旅行"}, headers=h).json()["ok"]
    r = c.get("/api/sync", headers=h).json()
    assert {a["name"] for a in r["albums"]} == {"沖縄旅行"}
    assert {i["name"]: i["albums"] for i in r["items"]}["b.jpg"] == [aid]
    assert c.delete(f"/api/albums/{aid}", headers=h).json()["ok"]
    assert c.get("/api/sync", headers=h).json()["albums"] == []


def test_pull_synology_photos_albums(settings, db, monkeypatch):
    c, h, ms = _lib(settings, db)
    from photohub.vault import seal
    with db.session() as s:
        s.execute(select(User)).scalar_one().dsm_secret = seal(settings, {"account": "reo", "password": "x"})
    albums = [{"id": 7, "name": "家族旅行", "shared": True}, {"id": 8, "name": "メモ"}]
    items = {7: [{"id": 101, "filename": "a.jpg", "filesize": ms["a.jpg"][1]},
                 {"id": 102, "filename": "B.JPG", "filesize": ms["b.jpg"][1]},
                 {"id": 103, "filename": "zzz.jpg", "filesize": 1}],
             8: [{"id": 104, "filename": "c.jpg", "filesize": ms["c.jpg"][1]}]}
    calls = []

    def handler(req: httpx.Request) -> httpx.Response:
        form = dict(x.split("=", 1) for x in req.content.decode().split("&"))
        from urllib.parse import unquote_plus
        form = {k: unquote_plus(v) for k, v in form.items()}
        calls.append((form["api"], form["method"]))
        if form["api"] == "SYNO.API.Auth":
            return httpx.Response(200, json={"success": True, "data": {"sid": "S"}})
        if form["api"] == "SYNO.Foto.Browse.Album" and form["method"] == "list":
            return httpx.Response(200, json={"success": True, "data": {"list": albums}})
        if form["api"] == "SYNO.Foto.Browse.Item":
            return httpx.Response(200, json={"success": True, "data": {"list": items[int(form["album_id"])]}})
        return httpx.Response(200, json={"success": True, "data": {}})
    real = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kw: real(transport=httpx.MockTransport(handler), **kw))
    r = c.post("/api/albums/pull", headers=h).json()
    assert r == {"albums": 2, "matched": 3, "unmatched": 1}
    sync = c.get("/api/sync", headers=h).json()
    by = {a["name"]: a for a in sync["albums"]}
    assert by["家族旅行"]["shared"] and not by["メモ"]["shared"]
    # adding a photo in the app goes to Synology Photos too (its item id is known from the pull)
    aid = by["メモ"]["id"]
    c.post(f"/api/albums/{aid}/add", json={"hashes": [ms["a.jpg"][0]]}, headers=h)
    assert ("SYNO.Foto.Browse.NormalAlbum", "add_item") in calls
    # an album deleted in Synology Photos disappears here
    albums.pop()
    assert c.post("/api/albums/pull", headers=h).json().get("error") is None
    assert {a["name"] for a in c.get("/api/sync", headers=h).json()["albums"]} == {"家族旅行"}
    # deleted in PhotoHub: stays deleted even though Synology Photos still lists it
    fam = {a["name"]: a for a in c.get("/api/sync", headers=h).json()["albums"]}["家族旅行"]["id"]
    assert c.delete(f"/api/albums/{fam}", headers=h).json()["ok"]
    c.post("/api/albums/pull", headers=h)
    assert c.get("/api/sync", headers=h).json()["albums"] == []


def test_phone_folder_album_stays_deleted(settings, db):
    import hashlib
    from fastapi.testclient import TestClient
    from photohub.api import create_app
    from photohub.sample import make_image
    from photohub.scanner import scan
    from photohub.users import add_user, scan_roots
    u, token = add_user(db, settings, "reo")
    from pathlib import Path
    p = Path(u.personal_root) / "LINE" / "a.jpg"
    p.parent.mkdir(parents=True)
    img = make_image(5)
    p.write_bytes(img)
    q = Path(u.personal_root) / "LINE" / "b.jpg"
    img2 = make_image(6)
    q.write_bytes(img2)
    scan(db, settings, scan_roots(db, settings), apply=True)
    c = TestClient(create_app(settings, db))
    h = {"Authorization": f"Bearer {token}"}
    fh = [hashlib.sha256(img).hexdigest(), hashlib.sha256(img2).hexdigest()]
    r = c.post("/api/device-albums", json={"name": "LINE", "file_hashes": fh}, headers=h).json()
    assert r["added"] == 2
    aid = r["album_id"]
    # one photo taken out by hand: the next phone sync does not put it back
    sync = c.get("/api/sync", headers=h).json()
    one = next(i["hash"] for i in sync["items"] if i["file_hash"] == fh[0])
    c.post(f"/api/albums/{aid}/remove", json={"hashes": [one]}, headers=h)
    assert c.post("/api/device-albums", json={"name": "LINE", "file_hashes": fh}, headers=h).json()["added"] == 0
    # the album deleted: the next phone sync does not bring it back
    assert c.delete(f"/api/albums/{aid}", headers=h).json()["ok"]
    assert c.post("/api/device-albums", json={"name": "LINE", "file_hashes": fh}, headers=h).json()["added"] == 0
    assert c.get("/api/sync", headers=h).json()["albums"] == []


def test_album_list_sent_only_when_changed(settings, db):
    from fastapi.testclient import TestClient
    from photohub.api import create_app
    from photohub.users import add_user
    _, token = add_user(db, settings, "reo")
    c = TestClient(create_app(settings, db))
    h = {"Authorization": f"Bearer {token}"}
    first = c.get("/api/sync", headers=h).json()
    assert first["albums"] == [] and first["albums_tag"]
    same = c.get(f"/api/sync?albums_tag={first['albums_tag']}", headers=h).json()
    assert same["albums"] is None   # unchanged: not sent again
    c.post("/api/albums", json={"name": "旅行", "hashes": []}, headers=h)
    changed = c.get(f"/api/sync?albums_tag={first['albums_tag']}", headers=h).json()
    assert [a["name"] for a in changed["albums"]] == ["旅行"]
