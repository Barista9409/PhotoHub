"""Read capture time, GPS, size and pairing identifiers with ExifTool (batch) / ffprobe.

Time handling (research §5.3): taken_at is stored in UTC, tz_offset separately.
  photos  DateTimeOriginal (+ OffsetTimeOriginal if present, else default offset)
  videos  QuickTime CreateDate is UTC by spec; Keys:CreationDate carries an offset
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from PIL import Image

TAGS = [
    "-DateTimeOriginal", "-SubSecDateTimeOriginal", "-OffsetTimeOriginal", "-OffsetTime",
    "-CreateDate", "-QuickTime:CreateDate", "-Keys:CreationDate",
    "-Composite:GPSLatitude", "-Composite:GPSLongitude",
    "-ImageWidth", "-ImageHeight", "-Duration", "-MIMEType",
    "-ImageDescription", "-XMP-dc:Description", "-Caption-Abstract",
    "-ContentIdentifier", "-MediaGroupUUID", "-FileSize#",
]

_DT = re.compile(r"(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(\.\d+)?\s*(Z|[+-]\d{2}:?\d{2})?")


@dataclass
class Meta:
    taken_at: datetime | None = None  # naive UTC
    tz_offset: int | None = None  # minutes
    lat: float | None = None
    lon: float | None = None
    width: int | None = None
    height: int | None = None
    duration: float | None = None
    mime: str | None = None
    description: str | None = None
    pair_id: str | None = None


def _parse_offset(s: str | None) -> int | None:
    if not s:
        return None
    s = s.strip()
    if s == "Z":
        return 0
    m = re.fullmatch(r"([+-])(\d{2}):?(\d{2})", s)
    if not m:
        return None
    mins = int(m.group(2)) * 60 + int(m.group(3))
    return mins if m.group(1) == "+" else -mins


def parse_exif_datetime(value: str | None) -> tuple[datetime | None, int | None]:
    """Returns (naive local datetime, offset minutes if embedded)."""
    if not value or not isinstance(value, str) or value.startswith("0000"):
        return None, None
    m = _DT.search(value)
    if not m:
        return None, None
    try:
        dt = datetime(*(int(m.group(i)) for i in range(1, 7)))
    except ValueError:
        return None, None
    if m.group(7):
        dt = dt.replace(microsecond=int(float(m.group(7)) * 1_000_000))
    return dt, _parse_offset(m.group(8))


def to_utc(local: datetime, offset_min: int) -> datetime:
    return local - timedelta(minutes=offset_min)


def _from_tags(t: dict, kind: str, default_tz: int) -> Meta:
    md = Meta()
    md.mime = t.get("MIMEType")
    md.width = _int(t.get("ImageWidth"))
    md.height = _int(t.get("ImageHeight"))
    md.duration = _float(t.get("Duration"))
    lat, lon = _float(t.get("GPSLatitude")), _float(t.get("GPSLongitude"))
    if lat is not None and lon is not None and not (lat == 0 and lon == 0):
        md.lat, md.lon = lat, lon
    desc = t.get("ImageDescription") or t.get("Description") or t.get("Caption-Abstract")
    if isinstance(desc, str) and desc.strip():
        md.description = desc.strip()
    pid = t.get("ContentIdentifier") or t.get("MediaGroupUUID")
    md.pair_id = str(pid) if pid else None

    if kind == "video":
        local, off = parse_exif_datetime(t.get("CreationDate"))  # Keys:CreationDate (with offset)
        if local and off is not None:
            md.taken_at, md.tz_offset = to_utc(local, off), off
        else:
            utc, _ = parse_exif_datetime(t.get("CreateDate"))  # QuickTime: UTC
            if utc:
                md.taken_at, md.tz_offset = utc, default_tz
    else:
        local, off = parse_exif_datetime(t.get("SubSecDateTimeOriginal") or t.get("DateTimeOriginal"))
        if local is None:
            local, off = parse_exif_datetime(t.get("CreateDate"))
        if local is not None:
            if off is None:
                off = _parse_offset(t.get("OffsetTimeOriginal") or t.get("OffsetTime"))
            if off is None:
                off_used, tz = default_tz, default_tz
            else:
                off_used = off
                # GPTH writes UTC with +00:00; show such items in the default zone.
                tz = off if off != 0 else default_tz
            md.taken_at, md.tz_offset = to_utc(local, off_used), tz
    return md


def _int(v) -> int | None:  # noqa: ANN001
    try:
        return int(float(v)) if v not in (None, "") else None
    except (TypeError, ValueError):
        return None


def _float(v) -> float | None:  # noqa: ANN001
    try:
        return float(v) if v not in (None, "") else None
    except (TypeError, ValueError):
        return None


def read_many(paths: list[Path], kinds: dict[Path, str], *, exiftool: str = "exiftool",
              default_tz: int = 540) -> dict[Path, Meta]:
    """Batch-read metadata. Falls back to Pillow when ExifTool is unavailable."""
    out: dict[Path, Meta] = {}
    if not paths:
        return out
    if shutil.which(exiftool):
        for i in range(0, len(paths), 200):
            batch = paths[i:i + 200]
            try:
                res = subprocess.run([exiftool, "-j", "-n", "-q", "-q", "-api", "LargeFileSupport=1",
                                      *TAGS, *map(str, batch)],
                                     capture_output=True, timeout=1800)
                rows = json.loads(res.stdout or b"[]")
            except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
                rows = []
            by_src = {Path(r.get("SourceFile", "")): r for r in rows}
            for p in batch:
                tags = by_src.get(p)
                if tags is not None:
                    out[p] = _from_tags(tags, kinds[p], default_tz)
    for p in paths:
        if p not in out:
            out[p] = _pillow_fallback(p, kinds[p], default_tz)
        if kinds[p] == "photo" and (out[p].width is None or out[p].height is None):
            try:
                with Image.open(p) as img:
                    out[p].width, out[p].height = img.size
            except Exception:
                pass
    return out


def _pillow_fallback(path: Path, kind: str, default_tz: int) -> Meta:
    md = Meta()
    if kind != "photo":
        return md
    try:
        with Image.open(path) as img:
            md.width, md.height = img.size
            exif = img.getexif()
            sub = exif.get_ifd(0x8769)
            local, off = parse_exif_datetime(sub.get(36867) or exif.get(306))
            off = off if off is not None else _parse_offset(sub.get(36881))
            if local:
                used = default_tz if off is None else off
                md.taken_at = to_utc(local, used)
                md.tz_offset = default_tz if not off else off
            gps = exif.get_ifd(0x8825)
            if gps and 2 in gps and 4 in gps:
                def deg(v):  # noqa: ANN001, ANN202
                    return float(v[0]) + float(v[1]) / 60 + float(v[2]) / 3600
                lat, lon = deg(gps[2]), deg(gps[4])
                if gps.get(1) == "S":
                    lat = -lat
                if gps.get(3) == "W":
                    lon = -lon
                md.lat, md.lon = lat, lon
    except Exception:
        pass
    return md


def file_mtime_utc(path: Path) -> datetime:
    return datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc).replace(tzinfo=None)
