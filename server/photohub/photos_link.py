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
                           if any(w in k for w in ("Album", "Item", "Folder", "Sharing", "Index"))}

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
