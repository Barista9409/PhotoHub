"""Synology Photos albums, as the person themself (their saved, encrypted login; see vault.py).

The Synology Photos Web API is undocumented. `probe` calls the album-related methods read-only and
reports exactly what this NAS answers, so the album sync can be built on confirmed shapes.
"""
from __future__ import annotations

import json

import httpx

from .config import Settings
from .db import User
from .netinfo import dsm_url
from .vault import open_


class PhotosError(RuntimeError):
    pass


class Session:
    """A Synology Photos login as one person. Use as a context manager (logs out at the end)."""

    def __init__(self, settings: Settings, user: User):
        cred = open_(settings, user.dsm_secret)
        if not cred:
            raise PhotosError("not linked: log out and log in again with 'Synology Photos のアルバムも同期する'")
        self.cred = cred
        self.http = httpx.Client(base_url=dsm_url(settings), verify=settings.dsm_verify_tls, timeout=60)
        self.sid: str | None = None

    def __enter__(self) -> "Session":
        data = {"api": "SYNO.API.Auth", "version": "6", "method": "login", "account": self.cred["account"],
                "passwd": self.cred["password"], "session": "SynologyPhotos", "format": "sid"}
        if self.cred.get("did"):
            data["device_id"] = self.cred["did"]
        body = self.http.post("/webapi/entry.cgi", data=data).json()
        if not body.get("success"):
            raise PhotosError(f"login failed: {body.get('error')}")
        self.sid = (body.get("data") or {}).get("sid")
        return self

    def __exit__(self, *exc) -> None:  # noqa: ANN002
        try:
            self.http.post("/webapi/entry.cgi", data={"api": "SYNO.API.Auth", "version": "6", "method": "logout",
                                                      "session": "SynologyPhotos", "_sid": self.sid or ""})
        except httpx.HTTPError:
            pass
        self.http.close()

    def raw(self, api: str, method: str, version: int = 1, **params) -> dict:  # noqa: ANN003
        data = {"api": api, "method": method, "version": str(version), "_sid": self.sid or ""}
        for k, v in params.items():
            data[k] = v if isinstance(v, str) else json.dumps(v)
        return self.http.post("/webapi/entry.cgi", data=data).json()

    def call(self, api: str, method: str, version: int = 1, **params) -> dict:  # noqa: ANN003
        body = self.raw(api, method, version, **params)
        if not body.get("success"):
            raise PhotosError(f"{api}.{method} v{version}: {body.get('error')}")
        return body.get("data") or {}

    def info(self) -> dict:
        r = self.http.get("/webapi/query.cgi", params={"api": "SYNO.API.Info", "version": "1", "method": "query",
                                                       "query": "SYNO.Foto,SYNO.FotoTeam"})
        return (r.json() or {}).get("data") or {}


def _short(x, n: int = 1500) -> object:  # noqa: ANN001
    s = json.dumps(x, ensure_ascii=False)
    return x if len(s) <= n else s[:n] + "…"


def probe(settings: Settings, user: User) -> dict:
    """Read-only tour of the album APIs as this person. Returns {"ok", "report"}."""
    out: dict = {}
    try:
        with Session(settings, user) as ses:
            info = ses.info()
            out["apis"] = {k: [v.get("minVersion"), v.get("maxVersion")] for k, v in sorted(info.items())
                           if any(w in k for w in ("Album", "Item", "Folder", "Sharing", "Index", "Person"))}

            def ver(api: str) -> list[int]:
                v = info.get(api) or {}
                hi = int(v.get("maxVersion") or 1)
                return sorted({1, min(2, hi), hi})

            def tryit(label: str, api: str, method: str, **params) -> dict:  # noqa: ANN003
                for v in ver(api):
                    b = ses.raw(api, method, v, **params)
                    if b.get("success"):
                        out[label] = {"version": v, "data": _short(b.get("data"))}
                        return b.get("data") or {}
                    out[label] = {"version": v, "error": b.get("error")}
                return {}

            albums = tryit("my_albums", "SYNO.Foto.Browse.Album", "list", offset=0, limit=20)
            tryit("albums_shared_with_me", "SYNO.Foto.Browse.Album", "list", offset=0, limit=20, category="shared_with_me")
            tryit("sharing_with_me", "SYNO.Foto.Sharing.Misc", "list_shared_with_me_album", offset=0, limit=20)
            tryit("team_root_folders", "SYNO.FotoTeam.Browse.Folder", "list", id=0, offset=0, limit=10)
            tryit("my_root_folders", "SYNO.Foto.Browse.Folder", "list", id=0, offset=0, limit=10)
            people = tryit("people", "SYNO.Foto.Browse.Person", "list", offset=0, limit=5, show_more=True)
            first_person = next(iter(people.get("list") or []), None)
            if first_person:
                tryit("person_items", "SYNO.Foto.Browse.Item", "list", person_id=first_person.get("id"), offset=0, limit=3)
            first = next(iter((albums.get("list") or [])), None)
            if first:
                items = tryit("first_album_items", "SYNO.Foto.Browse.Item", "list", album_id=first.get("id"),
                              offset=0, limit=3, additional=["thumbnail", "exif"])
                it = next(iter(items.get("list") or []), None)
                if it and it.get("folder_id") is not None:
                    tryit("item_folder_mine", "SYNO.Foto.Browse.Folder", "get", id=it["folder_id"])
                    tryit("item_folder_team", "SYNO.FotoTeam.Browse.Folder", "get", id=it["folder_id"])
        ok = True
    except (PhotosError, httpx.HTTPError, ValueError) as e:
        out["error"] = str(e)
        ok = False
    report = json.dumps(out, ensure_ascii=False, indent=1)
    return {"ok": ok, "report": report[:20000]}


# How Synology Photos is asked to look at its folders again. The API is undocumented: the forms that
# worked on this NAS are remembered (KV "photos_reindex_form"), the others are tried in this order.
_REINDEX_FORMS = [
    ("SYNO.Foto.Index", "reindex", 1, {"type": "basic"}),
    ("SYNO.Foto.Index", "reindex", 1, {}),
    ("SYNO.Foto.Index", "reindex", 2, {"type": "basic"}),
]


def reindex(settings: Settings, user: User, preferred: int | None = None) -> dict:
    """Ask Synology Photos (as this person) to index their Photos folder again, so photos PhotoHub put
    there appear in it (files written from the container do not notify Synology Photos by themselves).
    Returns {"ok", "form", "error"}."""
    forms = list(range(len(_REINDEX_FORMS)))
    if preferred is not None and 0 <= preferred < len(forms):
        forms.remove(preferred)
        forms.insert(0, preferred)
    last = None
    try:
        with Session(settings, user) as ses:
            for i in forms:
                api, method, version, params = _REINDEX_FORMS[i]
                b = ses.raw(api, method, version, **params)
                if b.get("success"):
                    return {"ok": True, "form": i}
                last = b.get("error")
    except (PhotosError, httpx.HTTPError, ValueError) as e:
        return {"ok": False, "form": None, "error": str(e)}
    return {"ok": False, "form": None, "error": f"Synology Photos refused: {last}"}


def reindex_due(db, settings: Settings, min_gap_minutes: int = 10) -> int:  # noqa: ANN001
    """For everyone with new photos placed since the last time (KV "reindex_due:<id>"), ask Synology
    Photos to look again - at most every `min_gap_minutes` per person. Returns how many were asked."""
    import time
    from sqlalchemy import select
    from .db import kv_get, kv_set, log_event
    n = 0
    with db.session() as s:
        users = [u for u in s.execute(select(User)).scalars() if u.dsm_secret and kv_get(s, f"reindex_due:{u.id}")]
        for u in users:
            s.expunge(u)
    for u in users:
        with db.session() as s:
            if time.time() - float(kv_get(s, f"reindex_at:{u.id}") or 0) < min_gap_minutes * 60:
                continue
            pref = kv_get(s, "photos_reindex_form")
        r = reindex(settings, u, int(pref) if pref else None)
        with db.session() as s:
            kv_set(s, f"reindex_at:{u.id}", str(time.time()))
            if r["ok"]:
                kv_set(s, f"reindex_due:{u.id}", "")
                kv_set(s, "photos_reindex_form", str(r["form"]))
                n += 1
            else:
                log_event(s, "warn", "photos_reindex", f"{u.name}: {r.get('error')}")
    return n


def mark_due(s, user_id: int | None) -> None:  # noqa: ANN001
    """New photos were put in this person's folders (None = the shared space: everyone who can see it)."""
    from sqlalchemy import select
    from .db import kv_set
    ids = [user_id] if user_id is not None else [u.id for u in s.execute(select(User)).scalars()]
    for i in ids:
        kv_set(s, f"reindex_due:{i}", "1")
