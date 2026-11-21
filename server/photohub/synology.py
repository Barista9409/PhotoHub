"""Synology Photos Web API (D1: used only for album create/add and re-index).

The API is undocumented. Method names come from the NAS's own SYNO.Foto.lib/SYNO.FotoTeam.lib
(research §3.1); parameter shapes follow what the Synology Photos web UI sends and MUST be
confirmed on the real NAS with `photohub synology probe` before SYNOLOGY_API_ENABLED=true.
Every call is best-effort: a failure is logged and the album stays in DB + XMP (spec F3).
PhotoHub never moves or deletes files through this API.
"""
from __future__ import annotations

import json
from pathlib import Path

import httpx
from sqlalchemy import select

from .config import Settings
from .db import Album, AlbumItem, Database, Media, log_event


class SynologyError(RuntimeError):
    pass


class Client:
    def __init__(self, settings: Settings):
        if not settings.dsm_url:
            raise SynologyError("DSM_URL not set")
        self.s = settings
        self.http = httpx.Client(base_url=settings.dsm_url.rstrip("/"), verify=settings.dsm_verify_tls, timeout=60)
        self.sid: str | None = None

    # -- low level ---------------------------------------------------------
    def call(self, api: str, method: str, version: int = 1, **params) -> dict:  # noqa: ANN003
        data = {"api": api, "method": method, "version": str(version)}
        for k, v in params.items():
            data[k] = v if isinstance(v, str) else json.dumps(v)
        if self.sid:
            data["_sid"] = self.sid
        r = self.http.post("/webapi/entry.cgi", data=data)
        r.raise_for_status()
        body = r.json()
        if not body.get("success"):
            raise SynologyError(f"{api}.{method} failed: {body.get('error')}")
        return body.get("data") or {}

    def login(self) -> None:
        d = self.call("SYNO.API.Auth", "login", 6, account=self.s.dsm_user, passwd=self.s.dsm_password,
                      session="SynologyPhotos", format="sid")
        self.sid = d["sid"]

    def logout(self) -> None:
        try:
            self.call("SYNO.API.Auth", "logout", 6, session="SynologyPhotos")
        except Exception:
            pass
        self.sid = None

    def api_info(self) -> dict:
        r = self.http.get("/webapi/query.cgi", params={"api": "SYNO.API.Info", "version": "1",
                                                       "method": "query", "query": "SYNO.Foto,SYNO.FotoTeam"})
        return r.json().get("data", {})

    # -- features -------------------------------------------------------------
    def reindex(self, team: bool = True) -> None:
        self.call("SYNO.FotoTeam.Index" if team else "SYNO.Foto.Index", "reindex", 1, type="basic")

    def folder_id(self, rel_folder: str, team: bool = True) -> int | None:
        """Resolve '/2024/2024-05' (relative to the space root) to a folder id."""
        api = "SYNO.FotoTeam.Browse.Folder" if team else "SYNO.Foto.Browse.Folder"
        d = self.call(api, "get", 1, name=rel_folder)
        f = d.get("folder") or {}
        return f.get("id")

    def items_in_folder(self, folder_id: int, team: bool = True) -> dict[str, int]:
        api = "SYNO.FotoTeam.Browse.Item" if team else "SYNO.Foto.Browse.Item"
        out, offset = {}, 0
        while True:
            d = self.call(api, "list", 1, folder_id=folder_id, offset=offset, limit=500)
            items = d.get("list") or []
            for it in items:
                out[it.get("filename")] = it.get("id")
            if len(items) < 500:
                return out
            offset += 500

    def create_album(self, name: str, item_ids: list[int]) -> int:
        d = self.call("SYNO.Foto.Browse.NormalAlbum", "create", 1, name=name, item=item_ids)
        return (d.get("album") or {}).get("id")

    def add_to_album(self, album_id: int, item_ids: list[int]) -> None:
        if item_ids:
            self.call("SYNO.Foto.Browse.NormalAlbum", "add_item", 1, id=album_id, item=item_ids)


def sync_albums(db: Database, settings: Settings, *, apply: bool) -> dict:
    """Push PhotoHub albums into Synology Photos (only when enabled)."""
    stats = {"albums": 0, "items": 0, "skipped": 0, "errors": 0}
    if not settings.synology_api:
        stats["skipped"] = -1
        return stats
    cli = Client(settings)
    try:
        cli.login()
        if apply:
            cli.reindex(team=True)
        with db.session() as s:
            pending = s.execute(select(AlbumItem, Album, Media).join(Album, Album.id == AlbumItem.album_id)
                                .join(Media, Media.id == AlbumItem.media_id)
                                .where(AlbumItem.synced.is_(False), Media.status == "active")).all()
            folder_cache: dict[str, dict[str, int]] = {}
            by_album: dict[int, list[tuple[AlbumItem, int]]] = {}
            for ai, album, m in pending:
                p = Path(m.nas_path)
                try:
                    rel = "/" + p.parent.relative_to(settings.library).as_posix()
                except ValueError:
                    stats["skipped"] += 1
                    continue
                if rel not in folder_cache:
                    try:
                        fid = cli.folder_id(rel)
                        folder_cache[rel] = cli.items_in_folder(fid) if fid else {}
                    except Exception:
                        folder_cache[rel] = {}
                sid = folder_cache[rel].get(p.name)
                if sid is None:
                    stats["skipped"] += 1  # not indexed yet; next run
                    continue
                by_album.setdefault(album.id, []).append((ai, sid))
            for album_id, entries in by_album.items():
                album = s.get(Album, album_id)
                ids = [sid for _, sid in entries]
                if not apply:
                    stats["albums"] += 1
                    stats["items"] += len(ids)
                    continue
                try:
                    if album.synology_album_id is None:
                        album.synology_album_id = cli.create_album(album.name, ids)
                    else:
                        cli.add_to_album(album.synology_album_id, ids)
                    for ai, _ in entries:
                        ai.synced = True
                    stats["albums"] += 1
                    stats["items"] += len(ids)
                except Exception as e:
                    stats["errors"] += 1
                    log_event(s, "warn", "synology_album", f"album {album.name}: {e}")
            if not apply:
                s.rollback()
    except Exception as e:
        stats["errors"] += 1
        with db.session() as s:
            log_event(s, "warn", "synology_api", f"Synology API unavailable: {e}")
    finally:
        cli.logout()
    return stats
