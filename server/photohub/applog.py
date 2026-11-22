"""What the phones report about themselves: stops, errors and how long things took.

Kept as one JSON line per record in <root>/logs/YYYY-MM-DD.jsonl (14 days), and summarised for the
app's 「動作の記録」 page: stops grouped by where they happened, errors by kind, and the time of each
operation (median, slowest 5%). Nothing leaves the Synology.
"""
from __future__ import annotations

import json
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .config import Settings

KEEP_DAYS = 14
MAX_BATCH = 500


def _dir(settings: Settings) -> Path:
    d = settings.root / "logs"
    d.mkdir(parents=True, exist_ok=True)
    return d


def store(settings: Settings, user: str, device: str, version: str, items: list[dict]) -> int:
    now = datetime.now(timezone.utc)
    f = _dir(settings) / f"{now:%Y-%m-%d}.jsonl"
    n = 0
    with f.open("a", encoding="utf-8") as out:
        for it in items[:MAX_BATCH]:
            rec = {"at": it.get("t") or int(now.timestamp() * 1000), "user": user, "device": device[:80],
                   "v": version[:20], "kind": str(it.get("kind", "info"))[:16], "name": str(it.get("name", ""))[:80]}
            if isinstance(it.get("ms"), (int, float)):
                rec["ms"] = int(it["ms"])
            if it.get("msg"):
                rec["msg"] = str(it["msg"])[:4000]
            out.write(json.dumps(rec, ensure_ascii=False) + "\n")
            n += 1
    for old in _dir(settings).glob("*.jsonl"):   # keep two weeks
        try:
            if datetime.strptime(old.stem, "%Y-%m-%d").replace(tzinfo=timezone.utc) < now - timedelta(days=KEEP_DAYS):
                old.unlink()
        except ValueError:
            pass
    return n


def _records(settings: Settings, days: int, user: str | None):
    since = int((datetime.now(timezone.utc) - timedelta(days=days)).timestamp() * 1000)
    for f in sorted(_dir(settings).glob("*.jsonl")):
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                r = json.loads(line)
            except ValueError:
                continue
            if r.get("at", 0) >= since and (user is None or r.get("user") == user):
                yield r


def _where(msg: str) -> str:
    """The first line of a stack trace that is our own code: where it happened."""
    lines = msg.splitlines()
    head = lines[0] if lines else ""
    for ln in lines[1:]:
        ln = ln.strip()
        if ln.startswith("at com.photohub"):
            return f"{head[:120]} @ {ln[3:][:120]}"
    return head[:160]


def summary(settings: Settings, days: int = 7, user: str | None = None) -> dict:
    stops: dict[str, dict] = {}
    errors: dict[str, dict] = {}
    times: dict[str, list[int]] = defaultdict(list)
    devices: dict[str, dict] = {}
    recent: list[dict] = []
    for r in _records(settings, days, user):
        dev = f"{r.get('user')}・{r.get('device')}"
        d = devices.setdefault(dev, {"device": dev, "version": r.get("v"), "last": 0, "stops": 0, "errors": 0})
        if r["at"] >= d["last"]:
            d["last"], d["version"] = r["at"], r.get("v")
        kind = r.get("kind")
        if kind in ("crash", "error"):
            key = _where(r.get("msg") or r.get("name") or "") if kind == "crash" else r.get("name") or "?"
            bucket = stops if kind == "crash" else errors
            g = bucket.setdefault(key, {"what": key, "count": 0, "last": 0, "version": r.get("v"), "sample": ""})
            g["count"] += 1
            if r["at"] >= g["last"]:
                g["last"], g["version"], g["sample"] = r["at"], r.get("v"), (r.get("msg") or "")[:1500]
            d["stops" if kind == "crash" else "errors"] += 1
            recent.append({"at": r["at"], "kind": kind, "device": dev, "name": r.get("name"), "msg": (r.get("msg") or "")[:300]})
        elif kind == "time" and "ms" in r:
            times[r.get("name") or "?"].append(r["ms"])

    def pct(xs: list[int], p: float) -> int:
        xs = sorted(xs)
        return xs[min(len(xs) - 1, int(len(xs) * p))]
    timing = sorted(({"name": k, "count": len(v), "median": pct(v, 0.5), "p95": pct(v, 0.95), "max": max(v)}
                     for k, v in times.items()), key=lambda t: -t["p95"])
    return {
        "days": days,
        "devices": sorted(devices.values(), key=lambda d: -d["last"]),
        "stops": sorted(stops.values(), key=lambda g: (-g["count"], -g["last"])),
        "errors": sorted(errors.values(), key=lambda g: (-g["count"], -g["last"]))[:30],
        "timing": timing,
        "recent": sorted(recent, key=lambda x: -x["at"])[:30],
    }
