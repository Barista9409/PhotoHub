"""Videos phones cannot play as they are (AVCHD .MTS/.M2TS with AC-3 sound, AVI, WMV, MPEG-2 ...):
a phone-friendly MP4 is made once with ffmpeg and kept in the cache. H.264/HEVC video is only
re-packaged (fast); other video is re-encoded (slow on a NAS, but only once)."""
from __future__ import annotations

import json
import subprocess
import threading
from pathlib import Path

from .config import Settings
from .db import Media

NEEDS = {".mts", ".m2ts", ".avi", ".wmv", ".mpg", ".mpeg", ".vob", ".flv", ".asf", ".mod", ".tod", ".3g2", ".divx"}
_locks: dict[str, threading.Lock] = {}
_guard = threading.Lock()


def needs_conversion(m: Media) -> bool:
    return m.kind == "video" and Path(m.nas_path).suffix.lower() in NEEDS


def path_for(settings: Settings, m: Media) -> Path:
    return settings.cache / "playable" / m.content_hash[:2] / f"{m.content_hash.replace(':', '_')}.mp4"


def _video_codec(src: Path) -> str:
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name",
                              "-of", "json", str(src)], capture_output=True, text=True, timeout=60).stdout
        return (json.loads(out).get("streams") or [{}])[0].get("codec_name", "")
    except Exception:  # noqa: BLE001
        return ""


def make(settings: Settings, m: Media) -> Path:
    """The phone-friendly MP4 for this video (made on first use). Raises RuntimeError if ffmpeg fails."""
    dst = path_for(settings, m)
    if dst.exists():
        return dst
    with _guard:
        lock = _locks.setdefault(m.content_hash, threading.Lock())
    with lock:  # two phones asking at once: convert once
        if dst.exists():
            return dst
        src = Path(m.nas_path)
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_suffix(".tmp.mp4")
        codec = _video_codec(src)
        copy = codec in ("h264", "hevc")
        if copy:
            video = ["-c:v", "copy"] + (["-tag:v", "hvc1"] if codec == "hevc" else [])
        else:
            video = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p"]
        cmd = ["ffmpeg", "-y", "-v", "error", "-i", str(src), "-map", "0:v:0", "-map", "0:a:0?", *video,
               "-c:a", "aac", "-b:a", "160k", "-ac", "2", "-movflags", "+faststart", str(tmp)]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=3 * 3600)
        if r.returncode != 0 and copy:  # odd streams: re-encode instead of copying
            cmd = ["ffmpeg", "-y", "-v", "error", "-i", str(src), "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264",
                   "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-ac", "2",
                   "-movflags", "+faststart", str(tmp)]
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=3 * 3600)
        if r.returncode != 0 or not tmp.exists():
            tmp.unlink(missing_ok=True)
            raise RuntimeError(f"ffmpeg failed: {r.stderr[-300:]}")
        tmp.replace(dst)
        return dst


MP4_LIKE = {".mp4", ".mov", ".m4v", ".3gp"}


def is_faststart(src: Path) -> bool:
    """True when the MP4's index ('moov') comes before the picture data ('mdat'): it can start
    playing at once over the network. Phones often write the index at the end."""
    try:
        with src.open("rb") as f:
            pos, size = 0, src.stat().st_size
            for _ in range(64):
                if pos + 8 > size:
                    return True
                f.seek(pos)
                head = f.read(16)
                n, kind = int.from_bytes(head[:4], "big"), head[4:8]
                if n == 1:
                    n = int.from_bytes(head[8:16], "big")
                elif n == 0:
                    n = size - pos
                if kind == b"moov":
                    return True
                if kind == b"mdat":
                    return False
                if n < 8:
                    return True
                pos += n
    except OSError:
        return True
    return True


def wants_copy(m: Media) -> bool:
    """A phone-friendly copy helps: a format phones cannot play, or an MP4 whose index is at the end."""
    if m.kind != "video":
        return False
    p = Path(m.nas_path)
    suffix = p.suffix.lower()
    return suffix in NEEDS or (suffix in MP4_LIKE and not is_faststart(p))


def prepare_all(db, settings: Settings, budget_gb: float = 50.0, minutes: int = 50) -> dict:  # noqa: ANN001
    """Make the phone-friendly copies ahead of time, newest videos first, so they play at once.
    Stops at the time limit (the next run goes on) and keeps the copies under `budget_gb`
    (older copies are not made once it is full). Copies of videos that are gone are removed."""
    import time
    from sqlalchemy import select
    start = time.time()
    root = settings.cache / "playable"
    root.mkdir(parents=True, exist_ok=True)
    used = sum(f.stat().st_size for f in root.rglob("*.mp4") if f.is_file())
    made = skipped = 0
    with db.session() as s:
        videos = list(s.execute(select(Media).where(Media.kind == "video", Media.status == "active")
                                .order_by(Media.taken_at.desc())).scalars())
        for m in videos:
            s.expunge(m)
    keep = {path_for(settings, m) for m in videos}
    for f in root.rglob("*.mp4"):   # copies of deleted videos
        if f not in keep and not f.name.endswith(".tmp.mp4"):
            used -= f.stat().st_size
            f.unlink(missing_ok=True)
    for m in videos:
        if time.time() - start > minutes * 60:
            break
        if path_for(settings, m).exists() or not Path(m.nas_path).exists():
            continue
        if not wants_copy(m):
            continue
        if used + (m.bytes or 0) > budget_gb * 1e9:
            skipped += 1
            continue
        try:
            used += make(settings, m).stat().st_size
            made += 1
        except Exception:  # noqa: BLE001 - one odd video must not stop the rest
            skipped += 1
    return {"made": made, "skipped": skipped, "used_gb": round(used / 1e9, 2)}
