"""Hashes used to decide identity (spec F2, research §5.1).

content_hash (hash_version 1)
  photo   SHA-256 over decoded pixels normalised to 8-bit RGB/RGBA plus size.
          Independent of EXIF/XMP, so GPTH writing dates/GPS does not change it.
  video   SHA-256 over ffmpeg's per-stream packet hashes (streamhash muxer).
          Independent of container metadata, which ExifTool/GPTH rewrites.
          (The spec said "whole-file SHA-256"; that breaks as soon as GPTH writes
          dates into an MP4, so we hash the streams instead. file_hash keeps the
          whole-file hash as the spec asks.)
  raw     RAW decoders are not stable across versions, so RAW uses the
          whole-file hash, prefixed "f:" to keep the namespaces apart.
"""
from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path

from PIL import Image, ImageOps

try:  # HEIC/HEIF support
    import pillow_heif

    pillow_heif.register_heif_opener()
except Exception:  # pragma: no cover
    pillow_heif = None

HASH_VERSION = 1
CHUNK = 1 << 20

PHOTO_EXT = {".jpg", ".jpeg", ".heic", ".heif", ".png", ".webp", ".gif", ".bmp", ".tif", ".tiff", ".avif"}
RAW_EXT = {".dng", ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".raf", ".srw", ".pef"}
VIDEO_EXT = {".mp4", ".mov", ".m4v", ".3gp", ".mkv", ".avi", ".mts", ".m2ts", ".webm", ".mp"}
# .MP (Pixel motion photo container) is really a JPEG; handled as photo below.
MEDIA_EXT = PHOTO_EXT | RAW_EXT | VIDEO_EXT


def kind_of(path: Path) -> str | None:
    ext = path.suffix.lower()
    if ext == ".mp":
        return "photo"
    if ext in PHOTO_EXT or ext in RAW_EXT:
        return "photo"
    if ext in VIDEO_EXT:
        return "video"
    return None


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(CHUNK):
            h.update(chunk)
    return h.hexdigest()


def _normalise(img: Image.Image) -> Image.Image:
    has_alpha = img.mode in ("RGBA", "LA", "PA") or (img.mode == "P" and "transparency" in img.info)
    target = "RGBA" if has_alpha else "RGB"
    return img if img.mode == target else img.convert(target)


def pixel_sha256(path: Path) -> str:
    with Image.open(path) as img:
        img.seek(0)
        img.load()
        norm = _normalise(img)
        h = hashlib.sha256()
        h.update(f"v{HASH_VERSION}|{norm.width}x{norm.height}|{norm.mode}|".encode())
        h.update(norm.tobytes())
        return h.hexdigest()


def video_stream_sha256(path: Path, ffmpeg: str = "ffmpeg") -> str | None:
    try:
        out = subprocess.run(
            [ffmpeg, "-v", "error", "-nostdin", "-i", str(path), "-map", "0", "-c", "copy",
             "-f", "streamhash", "-hash", "sha256", "-"],
            capture_output=True, check=True, timeout=3600,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    lines = [ln.strip() for ln in out.decode(errors="replace").splitlines() if ln.strip()]
    if not lines:
        return None
    return hashlib.sha256(("v1|" + "\n".join(lines)).encode()).hexdigest()


def phash(path: Path) -> str | None:
    try:
        import imagehash

        with Image.open(path) as img:
            img.seek(0)
            img.draft("RGB", (512, 512))
            img = ImageOps.exif_transpose(img)
            img.thumbnail((512, 512))
            return str(imagehash.phash(img.convert("RGB")))
    except Exception:
        return None


def content_hash(path: Path, kind: str, file_hash: str, ffmpeg: str = "ffmpeg") -> str:
    ext = path.suffix.lower()
    if kind == "video":
        return video_stream_sha256(path, ffmpeg) or ("f:" + file_hash)
    if ext in RAW_EXT:
        return "f:" + file_hash
    try:
        return pixel_sha256(path)
    except Exception:
        # Undecodable photo: fall back to the file hash rather than skip it.
        return "f:" + file_hash


def hamming(a: str, b: str) -> int:
    return bin(int(a, 16) ^ int(b, 16)).count("1")
