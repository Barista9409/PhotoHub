"""Repairing dates of photos that got "the day they reached the Synology" as their date
(no date inside the file, and the phone copy is gone, so the phone cannot tell us any more).

Clues, in order: a date in the file name (Screenshot_20240812-153012.png, IMG_20230812_143012.jpg,
PXL_20231105_..., 2023-08-12 14.30.12 ...), then the file time of the file itself when it is clearly
older (Synology Photos' own backup keeps the original time). Only arrival-like dates are touched.
People can also set a date by hand for photos without any clue.
"""
from __future__ import annotations

import os
import re
from datetime import datetime, timedelta
from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import Database, Media, kv_get, kv_set, log_event, touch_media, utcnow

NAME_DATE = re.compile(r"(?<!\d)((?:19|20)\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])"
                       r"(?:[ _T\-.]?([01]\d|2[0-3])[-_.:h]?([0-5]\d)[-_.:m]?([0-5]\d)?(?:\d{1,3})?)?(?!\d)")


def date_from_name(name: str) -> datetime | None:
    """Local date/time written in a file name, if it looks like one."""
    m = NAME_DATE.search(name)
    if not m:
        return None
    y, mo, d, h, mi, s = (int(x) if x else 0 for x in m.groups())
    try:
        dt = datetime(y, mo, d, h, mi, s)
    except ValueError:
        return None
    return dt if datetime(1995, 1, 1) <= dt <= datetime.now() + timedelta(days=1) else None


EXTRA_TAGS = ["-PNG:CreationTime", "-XMP:DateCreated", "-XMP:CreateDate", "-XMP-photoshop:DateCreated",
              "-EXIF:CreateDate", "-EXIF:ModifyDate", "-IPTC:DateCreated", "-QuickTime:CreateDate", "-RIFF:DateTimeOriginal"]


def date_inside(settings: Settings, path: Path) -> datetime | None:
    """Any creation date written inside the file besides the usual EXIF one (PNG, XMP, IPTC ...)."""
    import json
    import shutil
    import subprocess
    if not shutil.which(settings.exiftool_bin):
        return None
    try:
        out = subprocess.run([settings.exiftool_bin, "-j", "-q", "-q", "-d", "%Y-%m-%dT%H:%M:%S", *EXTRA_TAGS, str(path)],
                             capture_output=True, text=True, timeout=60).stdout
        tags = (json.loads(out or "[]") or [{}])[0]
    except Exception:  # noqa: BLE001
        return None
    found = []
    for k, v in tags.items():
        if k == "SourceFile" or not isinstance(v, str):
            continue
        try:
            found.append(datetime.fromisoformat(v[:19]))
        except ValueError:
            continue
    found = [d for d in found if datetime(1995, 1, 1) <= d <= datetime.now() + timedelta(days=1)]
    return min(found) if found else None


def arrival_like(m: Media) -> bool:
    return m.taken_at is None or abs((m.taken_at - m.created_at).total_seconds()) < 2 * 86400


def _set(m: Media, utc: datetime, tz: int | None) -> None:
    m.taken_at, m.tz_offset = utc, tz
    try:
        t = (utc - datetime(1970, 1, 1)).total_seconds()
        os.utime(m.nas_path, (t, t))
    except OSError:
        pass


def repair(db: Database, settings: Settings, owner_ids: set[int | None] | None = None) -> dict:
    """Fix arrival-like dates from the clues above. owner_ids limits it to some spaces (None = all)."""
    fixed_name = fixed_file = fixed_inside = left = 0
    tz = settings.default_tz_minutes
    with db.session() as s:
        for m in s.execute(select(Media).where(Media.status == "active")).scalars():
            if owner_ids is not None and m.owner_id not in owner_ids:
                continue
            if not arrival_like(m):
                continue
            local = date_from_name(Path(m.nas_path).name)
            if local is not None and (m.taken_at is None or local - timedelta(minutes=tz) < m.taken_at - timedelta(days=1)):
                _set(m, local - timedelta(minutes=tz), tz)
                touch_media(s, m)
                fixed_name += 1
                continue
            checked = kv_get(s, f"noclue:{m.id}") is not None   # looked inside before: do not run exiftool again
            inside = None if checked else date_inside(settings, Path(m.nas_path))
            if inside is not None and (m.taken_at is None or inside - timedelta(minutes=tz) < m.taken_at - timedelta(days=1)):
                _set(m, inside - timedelta(minutes=tz), tz)
                touch_media(s, m)
                fixed_inside += 1
                continue
            try:
                mt = datetime.utcfromtimestamp(Path(m.nas_path).stat().st_mtime)
            except OSError:
                mt = None
            if mt is not None and m.taken_at is not None and mt < m.taken_at - timedelta(days=2) and mt.year >= 1995:
                m.taken_at, m.tz_offset = mt, None
                touch_media(s, m)
                fixed_file += 1
                continue
            if not checked:
                kv_set(s, f"noclue:{m.id}", "1")
            left += 1
        if fixed_name or fixed_file or fixed_inside:
            log_event(s, "info", "dates_repaired", f"from file names {fixed_name}, inside the files {fixed_inside}, "
                      f"from file times {fixed_file}, no clue {left}")
    return {"from_name": fixed_name, "from_inside": fixed_inside, "from_file": fixed_file, "no_clue": left}


def set_by_hand(db: Database, user_id: int, hashes: list[str], local: datetime, tz: int) -> int:
    """People choose the date for photos without any clue (own and family photos)."""
    n = 0
    with db.session() as s:
        for m in s.execute(select(Media).where(Media.content_hash.in_(hashes[:5000]), Media.status == "active")).scalars():
            if m.owner_id not in (None, user_id):
                continue
            _set(m, local - timedelta(minutes=tz), tz)
            touch_media(s, m)
            n += 1
    return n
