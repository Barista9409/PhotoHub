"""Put phone backups exactly where Synology Photos' own mobile backup puts them, so that
either app can back up a phone and Synology Photos never shows the same photo twice.

  <personal space>/MobileBackup/<device name>/<layout>/<original file name>

The device folder and its layout (year/month, year-month, by phone album, flat, ...) are taken
from what Synology Photos already wrote for that phone; Synology does not document a fixed scheme.
A photo whose file (name + bytes) is already there is not written again.
"""
from __future__ import annotations

import re
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

from .config import Settings
from .db import User

MOBILE_BACKUP = "MobileBackup"
DEFAULT_LAYOUT = "{Y}/{M}"   # used only when the phone has never been backed up by Synology Photos

TEMPLATES = ["{Y}/{M}", "{Y}/{MM}", "{Y}-{MM}", "{Y}/{Y}-{MM}", "{Y}", "{album}", "{relpath}", ""]


def _norm(s: str) -> str:
    return re.sub(r"[^0-9a-z]+", "", s.casefold())


def backup_root(user: User) -> Path | None:
    if not user.personal_root:
        return None
    return Path(user.personal_root) / MOBILE_BACKUP


def device_dir(user: User, device: str | None) -> Path | None:
    """The phone's folder under MobileBackup: an existing one whose name matches the device
    (Synology uses the phone's name), otherwise a new one named after it."""
    root = backup_root(user)
    if root is None:
        return None
    name = (device or "").strip() or "Android"
    if root.exists():
        want = _norm(name)
        for d in sorted(p for p in root.iterdir() if p.is_dir()):
            if _norm(d.name) == want or (want and (want in _norm(d.name) or _norm(d.name) in want)):
                return d
    safe = re.sub(r'[\\/:*?"<>|]+', "_", name)[:80]
    return root / safe


def render(template: str, local: datetime, relpath: str | None) -> str:
    rel = (relpath or "").strip("/")
    album = rel.split("/")[-1] if rel else ""
    return (template.replace("{Y}", f"{local:%Y}").replace("{MM}", f"{local:%m}")
            .replace("{M}", str(local.month)).replace("{album}", album).replace("{relpath}", rel)).strip("/")


def infer_layout(dev: Path, samples: list[tuple[Path, datetime | None]]) -> str:
    """Pick the template that best reproduces where existing files sit.
    samples: (file path, local capture time) of files already in `dev`."""
    if not samples:
        return DEFAULT_LAYOUT
    score: Counter = Counter()
    for p, local in samples:
        try:
            parent = p.parent.relative_to(dev).as_posix()
        except ValueError:
            continue
        parent = "" if parent == "." else parent
        for t in TEMPLATES:
            if "{album}" in t or "{relpath}" in t:
                continue  # needs the phone's folder; judged by shape below
            if local is not None and render(t, local, None) == parent:
                score[t] += 1
        if parent and local is not None and not any(render(t, local, None) == parent for t in TEMPLATES[:5]):
            score["{album}" if "/" not in parent else "{relpath}"] += 1
    if not score:
        return DEFAULT_LAYOUT
    best, n = score.most_common(1)[0]
    return best if n >= max(1, len(samples) // 2) else DEFAULT_LAYOUT


def target_path(dev: Path, layout: str, name: str, local: datetime, relpath: str | None) -> Path:
    sub = render(layout, local, relpath)
    return (dev / sub / name) if sub else (dev / name)


def local_time(taken_utc: datetime | None, tz_offset: int | None, fallback: datetime) -> datetime:
    t = taken_utc or fallback
    return t + timedelta(minutes=tz_offset or 0)


def samples_for(dev: Path, settings: Settings, limit: int = 300) -> list[tuple[Path, datetime | None]]:
    """Existing files in the device folder with their capture time (from EXIF)."""
    from .hashing import kind_of
    from .metadata import read_many
    files = []
    if dev.exists():
        for p in sorted(dev.rglob("*"), reverse=True):
            if p.is_file() and kind_of(p) and "@eaDir" not in p.parts and not p.name.startswith("."):
                files.append(p)
                if len(files) >= limit:
                    break
    if not files:
        return []
    metas = read_many(files, {p: kind_of(p) or "photo" for p in files}, exiftool=settings.exiftool_bin,
                      default_tz=settings.default_tz_minutes)
    return [(p, local_time(metas[p].taken_at, metas[p].tz_offset, datetime.fromtimestamp(p.stat().st_mtime))
             if metas[p].taken_at else None) for p in files]
