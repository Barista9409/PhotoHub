"""F5: notifications (ntfy) and health checks. Every notification is also an `event` row."""
from __future__ import annotations

from datetime import date, datetime, timedelta
from pathlib import Path

import httpx
from sqlalchemy import select

from .config import Settings
from .db import Database, Event, ImportJob, kv_get, kv_set, log_event, utcnow
from .storage import amazon_heartbeat

NOTIFY_KINDS = {"import_done", "import_failed", "takeout_schedule_ending", "takeout_overdue",
                "disk_low", "raid_degraded", "amazon_stale", "nas_hash_mismatch", "daemon_error"}


def send(settings: Settings, title: str, message: str, priority: str = "default") -> bool:
    if not (settings.ntfy_url and settings.ntfy_topic):
        return False
    try:
        r = httpx.post(f"{settings.ntfy_url.rstrip('/')}/{settings.ntfy_topic}", content=message.encode(),
                       headers={"Title": title.encode("utf-8").decode("latin-1", "ignore") or "PhotoHub",
                                "Priority": priority, "Tags": "camera"}, timeout=15)
        return r.status_code < 300
    except httpx.HTTPError:
        return False


def flush(db: Database, settings: Settings) -> int:
    """Send events that should notify and have not been sent yet."""
    sent = 0
    with db.session() as s:
        for e in s.execute(select(Event).where(Event.notified.is_(False), Event.kind.in_(NOTIFY_KINDS))
                           .order_by(Event.id)).scalars():
            prio = "high" if e.level == "error" else "default"
            if send(settings, f"PhotoHub: {e.kind}", e.message, prio) or not settings.ntfy_url:
                e.notified = True  # without ntfy configured, the event log is the channel
                sent += 1
    return sent


def _once(s, key: str, period: timedelta) -> bool:  # noqa: ANN001
    """Rate-limit repeating warnings."""
    last = kv_get(s, f"warned:{key}")
    if last and utcnow() - datetime.fromisoformat(last) < period:
        return False
    kv_set(s, f"warned:{key}", utcnow().isoformat())
    return True


def raid_status(mdstat: Path = Path("/proc/mdstat")) -> list[str]:
    """Return degraded md arrays ("[UU_]" style status contains '_')."""
    if not mdstat.exists():
        return []
    bad, current = [], None
    for line in mdstat.read_text().splitlines():
        if line and not line.startswith(" ") and ":" in line and line.split(":")[0].strip().startswith("md"):
            current = line.split(":")[0].strip()
        elif current and "[" in line and "]" in line and line.strip().endswith("]"):
            status = line.strip().rsplit("[", 1)[-1]
            if "_" in status:
                bad.append(current)
            current = None
    return bad


def health_check(db: Database, settings: Settings, today: date | None = None) -> list[str]:
    today = today or date.today()
    raised: list[str] = []
    with db.session() as s:
        # disk space
        free = __import__("shutil").disk_usage(settings.library).free / 1e9
        if free < settings.min_free_gb and _once(s, "disk_low", timedelta(days=1)):
            log_event(s, "error", "disk_low", f"NAS free space low: {free:.0f} GB (< {settings.min_free_gb} GB)")
            raised.append("disk_low")
        # RAID
        bad = raid_status()
        if bad and _once(s, "raid", timedelta(hours=6)):
            log_event(s, "error", "raid_degraded", f"RAID degraded: {', '.join(bad)}")
            raised.append("raid_degraded")
        # Takeout schedule (1 year from start) ending soon
        start = settings.takeout_schedule_start
        if start:
            end = start.replace(year=start.year + 1)
            if end - timedelta(days=31) <= today <= end and _once(s, "schedule_end", timedelta(days=7)):
                log_event(s, "warn", "takeout_schedule_ending",
                          f"Takeout scheduled export ends on {end}. Book a new one-year schedule.")
                raised.append("takeout_schedule_ending")
        # Takeout overdue
        last = kv_get(s, "last_import_at")
        ref = datetime.fromisoformat(last).date() if last else start
        if ref and (today - ref).days > settings.takeout_interval_days + settings.takeout_grace_days:
            if _once(s, "takeout_overdue", timedelta(days=3)):
                log_event(s, "warn", "takeout_overdue",
                          f"No Takeout has arrived since {ref} (expected every {settings.takeout_interval_days} days).")
                raised.append("takeout_overdue")
        # running jobs stuck (e.g. container killed)
        stuck = s.execute(select(ImportJob).where(ImportJob.state == "running",
                                                  ImportJob.started_at < utcnow() - timedelta(days=2))).scalars().all()
        for j in stuck:
            j.state = "failed"
            j.message = (j.message or "") + " (marked failed: no progress for 2 days; re-run continues)"
    hb = amazon_heartbeat(db, settings)
    with db.session() as s:
        if hb is not None and utcnow() - hb > timedelta(days=settings.amazon_stale_days):
            if _once(s, "amazon_stale", timedelta(days=1)):
                log_event(s, "warn", "amazon_stale", f"Amazon Photos app on the PC has not run since {hb:%Y-%m-%d}.")
                raised.append("amazon_stale")
    return raised
