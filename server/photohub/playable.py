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
