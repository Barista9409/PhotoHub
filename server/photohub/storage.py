"""F4: where each item lives, the Amazon outbox, and Google deletion candidates.

Amazon (no API): photos are hard-linked into amazon-outbox/ (same volume => no extra space;
falls back to a verified copy). location(amazon) is "queued" when placed and becomes
"assumed" (= probably uploaded) once the PC reports that the Amazon Photos app ran after that
(heartbeat file amazon-outbox/.amazon-heartbeat or POST /api/amazon/heartbeat).

Google deletion candidates (never deleted by PhotoHub):
  * taken more than N months ago (default 12)
  * on the NAS, file present and its SHA-256 re-checked against the DB (verified_at)
  * present on Google per Takeout
  * photos additionally: amazon state "assumed"
  match=exact  -> candidates proper (P4 acceptance)
  match=similar -> storage-saver copies, listed separately for a human to spot-check (D4)
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

from sqlalchemy import and_, select

from .config import Settings
from .db import Database, GoogleCleanup, Location, Media, User, visible_clause, kv_get, kv_set, log_event, set_location, utcnow
from .fileops import copy_verified
from .hashing import file_sha256

HEARTBEAT = ".amazon-heartbeat"


def _local(m: Media) -> datetime:
    return (m.taken_at or m.created_at) + timedelta(minutes=m.tz_offset or 0)


def amazon_heartbeat(db: Database, settings: Settings) -> datetime | None:
    hb = settings.outbox / HEARTBEAT
    file_time = datetime.fromtimestamp(hb.stat().st_mtime, tz=timezone.utc).replace(tzinfo=None) if hb.exists() else None
    with db.session() as s:
        v = kv_get(s, "amazon_heartbeat")
    api_time = datetime.fromisoformat(v) if v else None
    times = [t for t in (file_time, api_time) if t]
    return max(times) if times else None


def record_heartbeat(db: Database) -> None:
    with db.session() as s:
        kv_set(s, "amazon_heartbeat", utcnow().isoformat())


def fill_outbox(db: Database, settings: Settings, *, apply: bool, limit: int | None = None) -> dict:
    stats = {"linked": 0, "copied": 0, "planned": 0, "assumed": 0, "errors": 0}
    hb = amazon_heartbeat(db, settings)
    s = db.new_session()
    try:
        q = (select(Media).outerjoin(Location, and_(Location.media_id == Media.id, Location.store == "amazon"))
             .where(Media.kind == "photo", Media.status == "active", Location.id.is_(None)))
        rows = list(s.execute(q).scalars())
        if limit:
            rows = rows[:limit]
        # one Amazon account for the family: outbox/shared/... and outbox/<person>/...
        owners = {u.id: u for u in s.execute(select(User)).scalars()}
        for m in rows:
            src = Path(m.nas_path)
            if not src.exists():
                continue
            owner = owners.get(m.owner_id) if m.owner_id is not None else None
            space = owner.name if owner else "shared"
            root = Path(owner.personal_root) if owner and owner.personal_root else settings.library
            try:
                rel = src.relative_to(root)
            except ValueError:
                rel = Path(f"{_local(m):%Y}") / f"{_local(m):%Y-%m}" / src.name
            dst = settings.outbox / space / rel
            stats["planned"] += 1
            if apply:
                try:
                    if not dst.exists():
                        dst.parent.mkdir(parents=True, exist_ok=True)
                        try:
                            os.link(src, dst)
                            stats["linked"] += 1
                        except OSError:
                            copy_verified(src, dst, m.file_hash)
                            stats["copied"] += 1
                    set_location(s, m.id, "amazon", "queued", source_ref=str(dst))
                except Exception as e:
                    stats["errors"] += 1
                    log_event(s, "error", "outbox_error", f"{src}: {e}")
        # queued -> assumed once the PC app has run after the item was placed (+1 h margin)
        if hb:
            for loc in s.execute(select(Location).where(Location.store == "amazon",
                                                        Location.state == "queued")).scalars():
                if hb > loc.seen_at + timedelta(hours=1):
                    loc.state = "assumed"
                    stats["assumed"] += 1
        if apply:
            log_event(s, "info", "outbox", f"outbox: {stats}")
            s.commit()
        else:
            s.rollback()
    finally:
        s.close()
    return stats


def verify_nas(db: Database, settings: Settings, max_age_days: int = 90, limit: int | None = None) -> dict:
    """Re-hash NAS files that are about to become candidates (read-only on the library)."""
    cutoff = utcnow() - timedelta(days=30 * settings.google_keep_months)
    stale = utcnow() - timedelta(days=max_age_days)
    res = {"ok": 0, "mismatch": 0, "missing": 0}
    with db.session() as s:
        q = (select(Media, Location).join(Location, and_(Location.media_id == Media.id, Location.store == "nas"))
             .where(Media.status == "active", Media.taken_at < cutoff, Location.state == "present"))
        n = 0
        for m, loc in s.execute(q).all():
            if loc.verified_at and loc.verified_at > stale:
                continue
            if limit is not None and n >= limit:
                break
            n += 1
            p = Path(m.nas_path)
            if not p.exists():
                res["missing"] += 1
                loc.verified_at = None
                continue
            if file_sha256(p) == m.file_hash:
                loc.verified_at = utcnow()
                res["ok"] += 1
            else:
                loc.verified_at = None
                res["mismatch"] += 1
                log_event(s, "error", "nas_hash_mismatch", f"{p} no longer matches its recorded hash")
    return res


def _eligible(s, settings: Settings, user_id: int):  # noqa: ANN001, ANN202
    """Yield (media, google location, (month, match)) for every item that may leave
    `user_id`'s Google account (each family member has their own)."""
    cutoff = utcnow() - timedelta(days=30 * settings.google_keep_months)
    stale = utcnow() - timedelta(days=90)
    locs: dict[int, dict[str, Location]] = {}
    for loc in s.execute(select(Location)).scalars():
        if loc.store == "google" and loc.user_id != user_id:
            continue
        locs.setdefault(loc.media_id, {})[loc.store] = loc
    for m in s.execute(select(Media).where(Media.status == "active", Media.taken_at < cutoff)).scalars():
        l = locs.get(m.id, {})
        nas, g, amz = l.get("nas"), l.get("google"), l.get("amazon")
        if not (nas and nas.state == "present" and nas.verified_at and nas.verified_at > stale):
            continue
        if not Path(m.nas_path).exists():
            continue
        if not (g and g.state == "present"):
            continue
        if settings.amazon_backup and m.kind == "photo" and not (amz and amz.state == "assumed"):
            continue
        yield m, g, (f"{_local(m):%Y-%m}", g.match)


def google_candidates(db: Database, settings: Settings, user_id: int = 0) -> list[dict]:
    """Recompute `user_id`'s candidate counts per month and match kind (confirmed ones kept)."""
    with db.session() as s:
        counts: dict[tuple[str, str], list[int]] = {}
        for m, _g, key in _eligible(s, settings, user_id):
            c = counts.setdefault(key, [0, 0])
            c[0 if m.kind == "photo" else 1] += 1
        for (month, match), (ph, vi) in sorted(counts.items()):
            row = s.execute(select(GoogleCleanup).where(GoogleCleanup.user_id == user_id, GoogleCleanup.month == month,
                                                        GoogleCleanup.match == match)).scalar_one_or_none()
            if row is None:
                row = GoogleCleanup(user_id=user_id, month=month, match=match)
                s.add(row)
            if row.confirmed_at is None:
                row.photo_count, row.video_count = ph, vi
        for row in s.execute(select(GoogleCleanup).where(GoogleCleanup.user_id == user_id,
                                                         GoogleCleanup.confirmed_at.is_(None))).scalars():
            if (row.month, row.match) not in counts:
                row.photo_count = row.video_count = 0
        s.flush()
        out = []
        for row in s.execute(select(GoogleCleanup).where(GoogleCleanup.user_id == user_id)
                             .order_by(GoogleCleanup.month, GoogleCleanup.match)).scalars():
            if row.confirmed_at is None and row.photo_count + row.video_count == 0:
                continue
            out.append({"id": row.id, "month": row.month, "match": row.match, "photos": row.photo_count,
                        "videos": row.video_count,
                        "confirmed_at": row.confirmed_at.isoformat() if row.confirmed_at else None,
                        "note": ("videos will exist only on the NAS after deletion from Google"
                                 if row.video_count else "")})
    return out


def confirm_cleanup(db: Database, settings: Settings, cleanup_id: int, user_id: int | None = None) -> int:
    """A person deleted that month from their Google Photos: record it (google -> removed).
    `user_id` given (API): only their own candidates can be confirmed."""
    with db.session() as s:
        row = s.get(GoogleCleanup, cleanup_id)
        if row is None or (user_id is not None and row.user_id != user_id):
            raise ValueError("unknown candidate")
        if row.confirmed_at is not None:
            return 0
        n = 0
        for _m, g, key in list(_eligible(s, settings, row.user_id)):
            if key == (row.month, row.match):
                g.state = "removed"
                n += 1
        row.confirmed_at = utcnow()
        log_event(s, "info", "google_cleanup_confirmed", f"{row.month} ({row.match}): {n} items marked removed")
    return n


def store_counts(db: Database, user: User | None = None) -> dict:
    """Per-store counts. With `user`: only items they can see, and only their own Google."""
    with db.session() as s:
        out: dict[str, int] = {}
        q = (select(Location).join(Media, Media.id == Location.media_id)
             .where(Location.state.in_(["present", "assumed", "queued"]), Media.status == "active"))
        if user is not None:
            q = q.where(visible_clause(user))
        for loc in s.execute(q).scalars():
            if loc.store == "google" and user is not None and loc.user_id != user.id:
                continue
            out[loc.store] = out.get(loc.store, 0) + 1
    return out
