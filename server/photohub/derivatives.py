"""F7: thumbnails (long side 320 px) and previews (1600 px), WebP, keyed by content hash.

cache/thumb/ab/<hash>.webp, cache/preview/ab/<hash>.webp
Source preference (research §4): Synology Photos' own XL thumbnail if present (saves decoding
HEIC/RAW), else the original. Videos: a frame grabbed with ffmpeg.
"""
from __future__ import annotations

import io
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image, ImageOps
from sqlalchemy import select

from .config import Settings
from .db import Database, Media

SIZES = {"thumb": (320, 70), "preview": (1600, 80)}


def path_for(settings: Settings, kind: str, content_hash: str) -> Path:
    safe = content_hash.replace(":", "_")
    return settings.cache / kind / safe[:2] / f"{safe}.webp"


def synology_thumb(original: Path, want: int = 1600) -> Path | None:
    """Synology Photos' own thumbnails (already made, already rotated). For a small thumbnail the
    small ones are enough, which is far quicker than decoding the original photo."""
    d = original.parent / "@eaDir" / original.name
    names = ["SYNOPHOTO_THUMB_M.jpg", "SYNOPHOTO_THUMB_B.jpg"] if want <= 640 else []
    for name in names + ["SYNOPHOTO_THUMB_XL.jpg", "SYNOPHOTO_THUMB_L.jpg"]:
        p = d / name
        if p.exists():
            try:
                with Image.open(p) as im:
                    if max(im.size) >= want:
                        return p
            except OSError:
                continue
    return None


def _video_frame(settings: Settings, path: Path) -> Image.Image | None:
    for ss in ("1", "0"):
        try:
            data = subprocess.run([settings.ffmpeg_bin, "-v", "error", "-nostdin", "-ss", ss, "-i", str(path),
                                   "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-"],
                                  capture_output=True, check=True, timeout=120).stdout
            if data:
                return Image.open(io.BytesIO(data))
        except (OSError, subprocess.SubprocessError):
            continue
    return None


def _source_image(settings: Settings, m: Media, want: int) -> Image.Image | None:
    p = Path(m.nas_path)
    if not p.exists():
        return None
    if m.kind == "video":
        return _video_frame(settings, p)
    syn = synology_thumb(p, want)
    if syn is not None:
        return Image.open(syn)  # Synology's thumb is already rotated and big enough
    img = Image.open(p)
    img.draft("RGB", (want * 2, want * 2))
    return ImageOps.exif_transpose(img)


def make(settings: Settings, m: Media, force: bool = False, kinds: tuple[str, ...] | None = None) -> bool:
    """kinds=("thumb",) makes only the small one (what the photo grid needs, quickly)."""
    todo = [k for k in (kinds or tuple(SIZES)) if force or not path_for(settings, k, m.content_hash).exists()]
    if not todo:
        return False
    img = None
    for kind in sorted(todo, key=lambda k: -SIZES[k][0]):
        size, quality = SIZES[kind]
        if img is None:
            img = _source_image(settings, m, size)
            if img is None:
                return False
            img = img.convert("RGB")
        out = img.copy()
        out.thumbnail((size, size), Image.Resampling.LANCZOS)
        dst = path_for(settings, kind, m.content_hash)
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_suffix(".tmp")
        out.save(tmp, "WEBP", quality=quality, method=4)
        tmp.replace(dst)
    return True


def build_all(db: Database, settings: Settings, limit: int | None = None,
              kinds: tuple[str, ...] | None = None) -> tuple[int, int]:
    with db.session() as s:
        media = list(s.execute(select(Media).where(Media.status == "active").order_by(Media.taken_at.desc())).scalars())
    want = kinds or tuple(SIZES)
    media = [m for m in media if not all(path_for(settings, k, m.content_hash).exists() for k in want)]
    if limit:
        media = media[:limit]
    ok = fail = 0

    def one(m: Media) -> bool:
        try:
            return make(settings, m, kinds=kinds)
        except Exception:
            return False

    with ThreadPoolExecutor(max_workers=max(1, settings.workers)) as ex:
        for r in ex.map(one, media):
            ok, fail = (ok + 1, fail) if r else (ok, fail + 1)
    return ok, fail
