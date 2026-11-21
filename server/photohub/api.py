"""F7 + management REST API for the Android app. Token-protected; meant to be reached only on
the LAN or over Tailscale (spec §6: never exposed directly to the internet).

Family use: every person has their own token (`photohub user add`). Each request sees only the
shared space, their own personal space, and personal items they were granted (media_access).
Admins additionally organise the shared space and see every import job and the event log."""
from __future__ import annotations

import struct
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel
from sqlalchemy import func, select

from . import derivatives, netinfo, similar, storage
from .dsm_login import login
from .config import Settings
from .db import (Album, AlbumItem, Database, Event, ImportItem, ImportJob, Location, Media, SimilarGroup,
                 SimilarMember, User, can_see, kv_get, kv_set, touch_media, utcnow, visible_clause)
from .users import by_token, redeem_invite

PACK_MAGIC = b"PHP1"
IMMUTABLE = {"Cache-Control": "private, max-age=31536000, immutable"}


class Hashes(BaseModel):
    hashes: list[str]


class Redeem(BaseModel):
    code: str
    device: str | None = None


class Login(BaseModel):
    username: str
    password: str
    otp: str | None = None
    device: str | None = None


class MySettings(BaseModel):
    takeout_target: str  # personal / shared


class DeviceAlbum(BaseModel):
    name: str
    file_hashes: list[str]


class Keep(BaseModel):
    keep: list[int]


def _ms(dt: datetime | None) -> int | None:
    return int(dt.replace(tzinfo=timezone.utc).timestamp() * 1000) if dt else None


def create_app(settings: Settings, db: Database) -> FastAPI:
    app = FastAPI(title="PhotoHub", version="0.1.0")

    def auth(request: Request) -> User:
        h = request.headers.get("authorization", "")
        token = h[7:] if h.lower().startswith("bearer ") else request.query_params.get("token", "")
        user = by_token(db, token)
        if user is None:
            raise HTTPException(401, "bad token")
        return user

    def admin(user: User = Depends(auth)) -> User:
        if not user.is_admin:
            raise HTTPException(403, "admin only")
        return user

    def media_or_404(s, content_hash: str, user: User) -> Media:  # noqa: ANN001
        m = s.execute(select(Media).where(Media.content_hash == content_hash)).scalar_one_or_none()
        if m is None or not can_see(s, user, m):
            raise HTTPException(404)  # someone else's personal photo looks like "not found"
        return m

    def album_visible(user: User):  # noqa: ANN202
        return (Album.owner_id.is_(None)) | (Album.owner_id == user.id)

    @app.get("/api/health")
    def health() -> dict:
        # "app" lets the phone recognise a PhotoHub server when it looks around the home network
        return {"ok": True, "app": "photohub", "version": "0.1.0", "apply": settings.apply}

    fails: dict[str, list[float]] = {}

    @app.post("/api/setup/redeem")
    def setup_redeem(body: Redeem, request: Request) -> dict:
        """Setup QR code -> this phone's own token. No token needed; the code is single-use."""
        import time
        ip = request.client.host if request.client else "?"
        recent = [t for t in fails.get(ip, []) if time.time() - t < 600]
        if len(recent) >= 10:
            raise HTTPException(429, "too many attempts")
        res = redeem_invite(db, settings, body.code, body.device)
        if res is None:
            fails[ip] = recent + [time.time()]
            raise HTTPException(400, "invalid or expired setup code")
        return res

    @app.post("/api/login")
    def dsm_login(body: Login, request: Request) -> dict:
        """DSM user name + password (+ 2-step code) -> this phone's own token."""
        import time
        ip = request.client.host if request.client else "?"
        recent = [t for t in fails.get(ip, []) if time.time() - t < 600]
        if len(recent) >= 10:
            raise HTTPException(429, "too many attempts")
        status_, res = login(db, settings, body.username, body.password, body.otp, body.device)
        if status_ != "ok":
            if status_ not in ("otp_required", "unreachable"):
                fails[ip] = recent + [time.time()]
            raise HTTPException(503 if status_ == "unreachable" else 401, status_)
        return {**res, "lan": netinfo.lan_url(settings), "remote": netinfo.remote_url(settings)}

    @app.put("/api/me/settings")
    def my_settings(body: MySettings, user: User = Depends(auth)) -> dict:
        """Each person chooses where photos from *their* Google Takeout go (from the next import on)."""
        if body.takeout_target not in ("personal", "shared"):
            raise HTTPException(400, "takeout_target must be personal or shared")
        with db.session() as s:
            u = s.get(User, user.id)
            u.takeout_target = body.takeout_target
            from .db import log_event
            log_event(s, "info", "user_setting", f"{u.name}: takeout_target={u.takeout_target}")
        return {"takeout_target": body.takeout_target}

    @app.post("/api/device-albums")
    def device_albums(body: DeviceAlbum, user: User = Depends(auth)) -> dict:
        """A folder on the person's phone (LINE, Download, ...) -> their own album on the NAS.
        Photos are matched by whole-file hash; ones not on the NAS yet are linked on a later sync.
        Add-only: removing a photo from the phone folder does not remove it from the album."""
        name = body.name.strip()[:100]
        if not name:
            raise HTTPException(400, "empty name")
        hashes = list(dict.fromkeys(body.file_hashes))[:5000]
        from .ingest import _album
        with db.session() as s:
            album = _album(s, name, "device", user.id)
            matched = added = 0
            for m in s.execute(select(Media).where(Media.file_hash.in_(hashes), Media.status == "active",
                                                   visible_clause(user))).scalars():
                matched += 1
                if s.get(AlbumItem, (album.id, m.id)) is None:
                    s.add(AlbumItem(album_id=album.id, media_id=m.id))
                    touch_media(s, m)  # the phone gets the new album link on its next sync
                    added += 1
        return {"album_id": album.id, "matched": matched, "added": added}

    @app.post("/api/upload")
    async def upload(request: Request, user: User = Depends(auth)) -> dict:
        """Phone backup straight to the NAS. Body: the file. Headers: X-File-Hash (sha256), X-File-Name."""
        import os
        import tempfile
        from urllib.parse import unquote
        fh = request.headers.get("x-file-hash", "")
        name = unquote(request.headers.get("x-file-name", "upload"))
        if len(fh) != 64:
            raise HTTPException(400, "X-File-Hash required")
        tmp_dir = settings.root / "incoming" / ".tmp"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=tmp_dir)
        size = 0
        try:
            with os.fdopen(fd, "wb") as f:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > 8 * 1024**3:
                        raise HTTPException(413, "too large")
                    f.write(chunk)
            from .incoming import UploadRejected, store_upload
            try:
                return store_upload(db, settings, user, Path(tmp), fh, name)
            except UploadRejected as e:
                raise HTTPException(400, str(e))
        finally:
            if Path(tmp).exists():
                Path(tmp).unlink()

    @app.post("/api/have")
    def have_files(body: Hashes, user: User = Depends(auth)) -> dict:
        """Which of these file hashes are safely on the NAS ('nas') or received ('queued')."""
        from .incoming import have
        return have(db, user, body.hashes)

    @app.get("/api/me")
    def me(user: User = Depends(auth)) -> dict:
        return {"id": user.id, "name": user.name, "admin": user.is_admin, "takeout_target": user.takeout_target}

    @app.get("/api/status")
    def status(user: User = Depends(auth)) -> dict:
        vis = visible_clause(user)
        with db.session() as s:
            photos = s.scalar(select(func.count()).select_from(Media).where(Media.status == "active",
                                                                            Media.kind == "photo", vis))
            videos = s.scalar(select(func.count()).select_from(Media).where(Media.status == "active",
                                                                            Media.kind == "video", vis))
            jobs_q = select(ImportJob.id) if user.is_admin else select(ImportJob.id).where(ImportJob.user_id == user.id)
            dups = s.scalar(select(func.count()).select_from(ImportItem).where(ImportItem.result == "duplicate",
                                                                               ImportItem.job_id.in_(jobs_q)))
            open_groups = s.scalar(select(func.count()).select_from(SimilarGroup).where(
                SimilarGroup.status == "open", _group_scope(user)))
            last_import = kv_get(s, f"last_import_at:{user.id}")
            last_scan = kv_get(s, "last_scan_at")
            last_device = kv_get(s, f"last_device_sync:{user.id}")
            max_rev = s.scalar(select(func.max(Media.rev))) or 0
        return {"user": user.name, "admin": user.is_admin,
                "photos": photos, "videos": videos, "stores": storage.store_counts(db, user),
                "last_import_at": last_import, "last_scan_at": last_scan, "last_device_sync_at": last_device,
                "duplicates": dups, "similar_open": open_groups, "rev": max_rev, "apply": settings.apply,
                "amazon_heartbeat": (hb.isoformat() if (hb := storage.amazon_heartbeat(db, settings)) else None)}

    def _group_scope(user: User):  # noqa: ANN202
        """Similar groups a person may organise: their own space; admins also the shared one."""
        own = SimilarGroup.owner_id == user.id
        return (SimilarGroup.owner_id.is_(None)) | own if user.is_admin else own

    @app.get("/api/jobs")
    def jobs(limit: int = 50, user: User = Depends(auth)) -> list[dict]:
        with db.session() as s:
            names = {u.id: u.name for u in s.execute(select(User)).scalars()}
            q = select(ImportJob)
            if not user.is_admin:
                q = q.where(ImportJob.user_id == user.id)
            rows = s.execute(q.order_by(ImportJob.id.desc()).limit(min(limit, 500))).scalars()
            return [{"id": j.id, "source": j.source, "user": names.get(j.user_id), "archive": j.archive_name, "state": j.state,
                     "started_at": j.started_at.isoformat(), "finished_at": j.finished_at.isoformat() if j.finished_at else None,
                     "new": j.new_count, "duplicate": j.dup_count, "error": j.error_count, "message": j.message}
                    for j in rows]

    @app.get("/api/jobs/{job_id}/errors")
    def job_errors(job_id: int, user: User = Depends(auth)) -> list[dict]:
        with db.session() as s:
            job = s.get(ImportJob, job_id)
            if job is None or not (user.is_admin or job.user_id == user.id):
                raise HTTPException(404)
            rows = s.execute(select(ImportItem).where(ImportItem.job_id == job_id, ImportItem.result == "error")
                             .limit(1000)).scalars()
            return [{"path": i.original_path, "message": i.message} for i in rows]

    @app.get("/api/events", dependencies=[Depends(admin)])
    def events(limit: int = 100) -> list[dict]:
        with db.session() as s:
            rows = s.execute(select(Event).order_by(Event.id.desc()).limit(min(limit, 1000))).scalars()
            return [{"at": e.at.isoformat(), "level": e.level, "kind": e.kind, "message": e.message} for e in rows]

    # ---- F7 delta sync ---------------------------------------------------
    @app.get("/api/sync")
    def sync(cursor: int = 0, limit: int = 2000, user: User = Depends(auth)) -> dict:
        limit = max(1, min(limit, 5000))
        vis = visible_clause(user)
        with db.session() as s:
            rows = list(s.execute(select(Media).where(Media.rev > cursor, vis).order_by(Media.rev).limit(limit)).scalars())
            my_albums = set(s.execute(select(Album.id).where(album_visible(user))).scalars())
            ids = [m.id for m in rows]
            stores: dict[int, list[str]] = {}
            albums_of: dict[int, list[int]] = {}
            if ids:
                for loc in s.execute(select(Location).where(Location.media_id.in_(ids),
                                                            Location.state.in_(["present", "assumed", "queued"]))).scalars():
                    if loc.store == "amazon" and loc.state == "queued":
                        continue
                    if loc.store == "google" and loc.user_id != user.id:
                        continue  # the "G" badge means: in *your* Google account
                    stores.setdefault(loc.media_id, []).append(loc.store)
                for ai in s.execute(select(AlbumItem).where(AlbumItem.media_id.in_(ids))).scalars():
                    if ai.album_id in my_albums:
                        albums_of.setdefault(ai.media_id, []).append(ai.album_id)
            items = [{
                "hash": m.content_hash, "file_hash": m.file_hash, "kind": m.kind, "mime": m.mime,
                "taken_at": _ms(m.taken_at), "tz": m.tz_offset, "lat": m.lat, "lon": m.lon,
                "w": m.width, "h": m.height, "duration": m.duration, "bytes": m.bytes,
                "name": Path(m.nas_path).name, "albums": albums_of.get(m.id, []), "stores": sorted(stores.get(m.id, [])),
                "deleted": m.status != "active", "rev": m.rev, "pair": m.pair_key, "desc": m.description,
                "space": "shared" if m.owner_id is None else ("mine" if m.owner_id == user.id else "granted"),
            } for m in rows]
            albums = [{"id": a.id, "name": a.name, "origin": a.origin, "shared": a.owner_id is None}
                      for a in s.execute(select(Album).where(album_visible(user)).order_by(Album.name)).scalars()]
            next_cursor = rows[-1].rev if rows else cursor
            more = bool(rows) and s.scalar(select(func.count()).select_from(Media).where(Media.rev > next_cursor, vis)) > 0
        return {"items": items, "albums": albums, "cursor": next_cursor, "has_more": more}

    @app.post("/api/thumbs")
    def thumbs(body: Hashes, user: User = Depends(auth)) -> Response:
        """Thumbnail pack: 'PHP1' u32 count, then per item: u16 len, hash, u32 len, webp bytes.
        Missing thumbnails are generated on the fly; still-missing ones get length 0."""
        hashes = body.hashes[:1000]
        parts = [PACK_MAGIC, struct.pack(">I", len(hashes))]
        with db.session() as s:
            ms = {m.content_hash: m for m in s.execute(select(Media).where(Media.content_hash.in_(hashes),
                                                                           visible_clause(user))).scalars()}
        for h in hashes:
            p = derivatives.path_for(settings, "thumb", h)
            if h not in ms:  # not visible to this person -> empty entry, like a missing one
                hb = h.encode()
                parts += [struct.pack(">H", len(hb)), hb, struct.pack(">I", 0)]
                continue
            if not p.exists():
                try:
                    derivatives.make(settings, ms[h])
                except Exception:
                    pass
            data = p.read_bytes() if p.exists() else b""
            hb = h.encode()
            parts += [struct.pack(">H", len(hb)), hb, struct.pack(">I", len(data)), data]
        return Response(b"".join(parts), media_type="application/octet-stream")

    def _derived(kind: str, content_hash: str, user: User) -> FileResponse:
        p = derivatives.path_for(settings, kind, content_hash)
        with db.session() as s:
            m = media_or_404(s, content_hash, user)
        if not p.exists():
            derivatives.make(settings, m)
        if not p.exists():
            raise HTTPException(404)
        return FileResponse(p, media_type="image/webp", headers=IMMUTABLE)

    @app.get("/api/thumb/{content_hash}")
    def thumb(content_hash: str, user: User = Depends(auth)) -> FileResponse:
        return _derived("thumb", content_hash, user)

    @app.get("/api/preview/{content_hash}")
    def preview(content_hash: str, user: User = Depends(auth)) -> FileResponse:
        return _derived("preview", content_hash, user)

    @app.get("/api/original/{content_hash}")
    def original(content_hash: str, user: User = Depends(auth)) -> FileResponse:
        """Original file; also used to stream videos (Range requests supported)."""
        with db.session() as s:
            m = media_or_404(s, content_hash, user)
        p = Path(m.nas_path)
        if not p.exists():
            raise HTTPException(410, "file not on NAS")
        return FileResponse(p, media_type=m.mime or None, filename=p.name, headers=IMMUTABLE)

    # ---- management --------------------------------------------------------
    @app.get("/api/similar")
    def similar_groups(status: str = "open", limit: int = 100, user: User = Depends(auth)) -> list[dict]:
        with db.session() as s:
            groups = list(s.execute(select(SimilarGroup).where(SimilarGroup.status == status, _group_scope(user))
                                    .order_by(SimilarGroup.id).limit(min(limit, 500))).scalars())
            out = []
            for g in groups:
                mem = s.execute(select(SimilarMember, Media).join(Media, Media.id == SimilarMember.media_id)
                                .where(SimilarMember.group_id == g.id)).all()
                out.append({"id": g.id, "status": g.status, "space": "shared" if g.owner_id is None else "mine", "members": [
                    {"media_id": m.id, "hash": m.content_hash, "w": m.width, "h": m.height, "bytes": m.bytes,
                     "taken_at": _ms(m.taken_at), "name": Path(m.nas_path).name, "keep": sm.keep}
                    for sm, m in mem]})
        return out

    @app.post("/api/similar/{group_id}/resolve")
    def similar_resolve(group_id: int, body: Keep, user: User = Depends(auth)) -> dict:
        try:
            actions = similar.resolve(db, settings, group_id, set(body.keep), apply=settings.apply, user=user)
        except similar.Forbidden as e:
            raise HTTPException(403, str(e))
        except ValueError as e:
            raise HTTPException(400, str(e))
        return {"applied": settings.apply, "actions": actions}

    @app.get("/api/google-candidates")
    def candidates(user: User = Depends(auth)) -> list[dict]:
        return storage.google_candidates(db, settings, user.id)  # only your own Google account

    @app.post("/api/google-candidates/{cid}/confirm")
    def candidates_confirm(cid: int, user: User = Depends(auth)) -> dict:
        try:
            return {"marked_removed": storage.confirm_cleanup(db, settings, cid, user.id)}
        except ValueError as e:
            raise HTTPException(404, str(e))

    @app.post("/api/amazon/heartbeat", dependencies=[Depends(admin)])
    def amazon_heartbeat() -> dict:
        storage.record_heartbeat(db)
        return {"ok": True}

    @app.post("/api/device-sync")
    def device_sync(user: User = Depends(auth)) -> dict:
        with db.session() as s:
            kv_set(s, f"last_device_sync:{user.id}", utcnow().isoformat())
        return {"ok": True}

    @app.exception_handler(Exception)
    async def errors(_: Request, exc: Exception) -> JSONResponse:  # pragma: no cover
        return JSONResponse({"error": str(exc)}, status_code=500)

    return app
