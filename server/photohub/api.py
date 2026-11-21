"""F7 + management REST API for the Android app. Token-protected; meant to be reached only on
the LAN or over Tailscale (spec §6: never exposed directly to the internet)."""
from __future__ import annotations

import hmac
import struct
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel
from sqlalchemy import func, select

from . import derivatives, similar, storage
from .config import Settings
from .db import (Album, AlbumItem, Database, Event, ImportItem, ImportJob, Location, Media, SimilarGroup,
                 SimilarMember, kv_get, kv_set, utcnow)

PACK_MAGIC = b"PHP1"
IMMUTABLE = {"Cache-Control": "private, max-age=31536000, immutable"}


class Hashes(BaseModel):
    hashes: list[str]


class Keep(BaseModel):
    keep: list[int]


def _ms(dt: datetime | None) -> int | None:
    return int(dt.replace(tzinfo=timezone.utc).timestamp() * 1000) if dt else None


def create_app(settings: Settings, db: Database) -> FastAPI:
    app = FastAPI(title="PhotoHub", version="0.1.0")

    def auth(request: Request) -> None:
        if not settings.api_token:
            raise HTTPException(503, "PHOTOHUB_API_TOKEN is not configured")
        h = request.headers.get("authorization", "")
        token = h[7:] if h.lower().startswith("bearer ") else request.query_params.get("token", "")
        if not hmac.compare_digest(token.encode(), settings.api_token.encode()):
            raise HTTPException(401, "bad token")

    A = [Depends(auth)]

    def media_or_404(s, content_hash: str) -> Media:  # noqa: ANN001
        m = s.execute(select(Media).where(Media.content_hash == content_hash)).scalar_one_or_none()
        if m is None:
            raise HTTPException(404)
        return m

    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True, "version": "0.1.0", "apply": settings.apply}

    @app.get("/api/status", dependencies=A)
    def status() -> dict:
        with db.session() as s:
            photos = s.scalar(select(func.count()).select_from(Media).where(Media.status == "active",
                                                                            Media.kind == "photo"))
            videos = s.scalar(select(func.count()).select_from(Media).where(Media.status == "active",
                                                                            Media.kind == "video"))
            dups = s.scalar(select(func.count()).select_from(ImportItem).where(ImportItem.result == "duplicate"))
            open_groups = s.scalar(select(func.count()).select_from(SimilarGroup).where(SimilarGroup.status == "open"))
            last_import, last_scan = kv_get(s, "last_import_at"), kv_get(s, "last_scan_at")
            last_device = kv_get(s, "last_device_sync")
            max_rev = s.scalar(select(func.max(Media.rev))) or 0
        return {"photos": photos, "videos": videos, "stores": storage.store_counts(db),
                "last_import_at": last_import, "last_scan_at": last_scan, "last_device_sync_at": last_device,
                "duplicates": dups, "similar_open": open_groups, "rev": max_rev, "apply": settings.apply,
                "amazon_heartbeat": (hb.isoformat() if (hb := storage.amazon_heartbeat(db, settings)) else None)}

    @app.get("/api/jobs", dependencies=A)
    def jobs(limit: int = 50) -> list[dict]:
        with db.session() as s:
            rows = s.execute(select(ImportJob).order_by(ImportJob.id.desc()).limit(min(limit, 500))).scalars()
            return [{"id": j.id, "source": j.source, "archive": j.archive_name, "state": j.state,
                     "started_at": j.started_at.isoformat(), "finished_at": j.finished_at.isoformat() if j.finished_at else None,
                     "new": j.new_count, "duplicate": j.dup_count, "error": j.error_count, "message": j.message}
                    for j in rows]

    @app.get("/api/jobs/{job_id}/errors", dependencies=A)
    def job_errors(job_id: int) -> list[dict]:
        with db.session() as s:
            rows = s.execute(select(ImportItem).where(ImportItem.job_id == job_id, ImportItem.result == "error")
                             .limit(1000)).scalars()
            return [{"path": i.original_path, "message": i.message} for i in rows]

    @app.get("/api/events", dependencies=A)
    def events(limit: int = 100) -> list[dict]:
        with db.session() as s:
            rows = s.execute(select(Event).order_by(Event.id.desc()).limit(min(limit, 1000))).scalars()
            return [{"at": e.at.isoformat(), "level": e.level, "kind": e.kind, "message": e.message} for e in rows]

    # ---- F7 delta sync ---------------------------------------------------
    @app.get("/api/sync", dependencies=A)
    def sync(cursor: int = 0, limit: int = 2000) -> dict:
        limit = max(1, min(limit, 5000))
        with db.session() as s:
            rows = list(s.execute(select(Media).where(Media.rev > cursor).order_by(Media.rev).limit(limit)).scalars())
            ids = [m.id for m in rows]
            stores: dict[int, list[str]] = {}
            albums_of: dict[int, list[int]] = {}
            if ids:
                for loc in s.execute(select(Location).where(Location.media_id.in_(ids),
                                                            Location.state.in_(["present", "assumed", "queued"]))).scalars():
                    if loc.store == "amazon" and loc.state == "queued":
                        continue
                    stores.setdefault(loc.media_id, []).append(loc.store)
                for ai in s.execute(select(AlbumItem).where(AlbumItem.media_id.in_(ids))).scalars():
                    albums_of.setdefault(ai.media_id, []).append(ai.album_id)
            items = [{
                "hash": m.content_hash, "file_hash": m.file_hash, "kind": m.kind, "mime": m.mime,
                "taken_at": _ms(m.taken_at), "tz": m.tz_offset, "lat": m.lat, "lon": m.lon,
                "w": m.width, "h": m.height, "duration": m.duration, "bytes": m.bytes,
                "name": Path(m.nas_path).name, "albums": albums_of.get(m.id, []), "stores": sorted(stores.get(m.id, [])),
                "deleted": m.status != "active", "rev": m.rev, "pair": m.pair_key, "desc": m.description,
            } for m in rows]
            albums = [{"id": a.id, "name": a.name, "origin": a.origin}
                      for a in s.execute(select(Album).order_by(Album.name)).scalars()]
            next_cursor = rows[-1].rev if rows else cursor
            more = bool(rows) and s.scalar(select(func.count()).select_from(Media).where(Media.rev > next_cursor)) > 0
        return {"items": items, "albums": albums, "cursor": next_cursor, "has_more": more}

    @app.post("/api/thumbs", dependencies=A)
    def thumbs(body: Hashes) -> Response:
        """Thumbnail pack: 'PHP1' u32 count, then per item: u16 len, hash, u32 len, webp bytes.
        Missing thumbnails are generated on the fly; still-missing ones get length 0."""
        hashes = body.hashes[:1000]
        parts = [PACK_MAGIC, struct.pack(">I", len(hashes))]
        with db.session() as s:
            ms = {m.content_hash: m for m in s.execute(select(Media).where(Media.content_hash.in_(hashes))).scalars()}
        for h in hashes:
            p = derivatives.path_for(settings, "thumb", h)
            if not p.exists() and h in ms:
                try:
                    derivatives.make(settings, ms[h])
                except Exception:
                    pass
            data = p.read_bytes() if p.exists() else b""
            hb = h.encode()
            parts += [struct.pack(">H", len(hb)), hb, struct.pack(">I", len(data)), data]
        return Response(b"".join(parts), media_type="application/octet-stream")

    def _derived(kind: str, content_hash: str) -> FileResponse:
        p = derivatives.path_for(settings, kind, content_hash)
        if not p.exists():
            with db.session() as s:
                m = media_or_404(s, content_hash)
            derivatives.make(settings, m)
        if not p.exists():
            raise HTTPException(404)
        return FileResponse(p, media_type="image/webp", headers=IMMUTABLE)

    @app.get("/api/thumb/{content_hash}", dependencies=A)
    def thumb(content_hash: str) -> FileResponse:
        return _derived("thumb", content_hash)

    @app.get("/api/preview/{content_hash}", dependencies=A)
    def preview(content_hash: str) -> FileResponse:
        return _derived("preview", content_hash)

    @app.get("/api/original/{content_hash}", dependencies=A)
    def original(content_hash: str) -> FileResponse:
        """Original file; also used to stream videos (Range requests supported)."""
        with db.session() as s:
            m = media_or_404(s, content_hash)
        p = Path(m.nas_path)
        if not p.exists():
            raise HTTPException(410, "file not on NAS")
        return FileResponse(p, media_type=m.mime or None, filename=p.name, headers=IMMUTABLE)

    # ---- management --------------------------------------------------------
    @app.get("/api/similar", dependencies=A)
    def similar_groups(status: str = "open", limit: int = 100) -> list[dict]:
        with db.session() as s:
            groups = list(s.execute(select(SimilarGroup).where(SimilarGroup.status == status)
                                    .order_by(SimilarGroup.id).limit(min(limit, 500))).scalars())
            out = []
            for g in groups:
                mem = s.execute(select(SimilarMember, Media).join(Media, Media.id == SimilarMember.media_id)
                                .where(SimilarMember.group_id == g.id)).all()
                out.append({"id": g.id, "status": g.status, "members": [
                    {"media_id": m.id, "hash": m.content_hash, "w": m.width, "h": m.height, "bytes": m.bytes,
                     "taken_at": _ms(m.taken_at), "name": Path(m.nas_path).name, "keep": sm.keep}
                    for sm, m in mem]})
        return out

    @app.post("/api/similar/{group_id}/resolve", dependencies=A)
    def similar_resolve(group_id: int, body: Keep) -> dict:
        try:
            actions = similar.resolve(db, settings, group_id, set(body.keep), apply=settings.apply)
        except ValueError as e:
            raise HTTPException(400, str(e))
        return {"applied": settings.apply, "actions": actions}

    @app.get("/api/google-candidates", dependencies=A)
    def candidates() -> list[dict]:
        return storage.google_candidates(db, settings)

    @app.post("/api/google-candidates/{cid}/confirm", dependencies=A)
    def candidates_confirm(cid: int) -> dict:
        try:
            return {"marked_removed": storage.confirm_cleanup(db, settings, cid)}
        except ValueError as e:
            raise HTTPException(404, str(e))

    @app.post("/api/amazon/heartbeat", dependencies=A)
    def amazon_heartbeat() -> dict:
        storage.record_heartbeat(db)
        return {"ok": True}

    @app.post("/api/device-sync", dependencies=A)
    def device_sync() -> dict:
        with db.session() as s:
            kv_set(s, "last_device_sync", utcnow().isoformat())
        return {"ok": True}

    @app.exception_handler(Exception)
    async def errors(_: Request, exc: Exception) -> JSONResponse:  # pragma: no cover
        return JSONResponse({"error": str(exc)}, status_code=500)

    return app
