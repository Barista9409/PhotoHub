"""Generate a synthetic Google Takeout ZIP for tests and for trying PhotoHub safely
(spec §8: "always check with samples before using real photos").

No real photos are involved: images are generated gradients/shapes with random noise.
"""
from __future__ import annotations

import json
import random
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw


@dataclass
class SampleItem:
    name: str
    taken_utc: datetime
    lat: float | None
    lon: float | None
    albums: list[str] = field(default_factory=list)
    description: str = ""
    seed: int = 0
    in_year_folder: bool = True


def make_image(seed: int, size: tuple[int, int] = (640, 480), quality: int = 90,
               fmt: str = "JPEG") -> bytes:
    rnd = random.Random(seed)
    img = Image.new("RGB", size, (rnd.randrange(256), rnd.randrange(256), rnd.randrange(256)))
    d = ImageDraw.Draw(img)
    for _ in range(12):
        x0, y0 = rnd.randrange(size[0]), rnd.randrange(size[1])
        x1, y1 = x0 + rnd.randrange(40, 300), y0 + rnd.randrange(40, 300)
        d.ellipse([x0, y0, x1, y1], fill=(rnd.randrange(256), rnd.randrange(256), rnd.randrange(256)))
    buf = BytesIO()
    img.save(buf, fmt, quality=quality)
    return buf.getvalue()


def sidecar(item: SampleItem) -> dict:
    ts = int(item.taken_utc.replace(tzinfo=timezone.utc).timestamp())
    geo = {"latitude": item.lat or 0.0, "longitude": item.lon or 0.0, "altitude": 0.0,
           "latitudeSpan": 0.0, "longitudeSpan": 0.0}
    return {
        "title": item.name,
        "description": item.description,
        "imageViews": "0",
        "creationTime": {"timestamp": str(ts + 60), "formatted": ""},
        "photoTakenTime": {"timestamp": str(ts), "formatted": ""},
        "geoData": geo,
        "geoDataExif": geo,
        "url": "https://photos.google.com/photo/sample",
        "googlePhotosOrigin": {"mobileUpload": {"deviceType": "ANDROID_PHONE"}},
    }


def default_items(n: int = 100, start_seed: int = 1) -> list[SampleItem]:
    items = []
    base = datetime(2023, 1, 15, 3, 0, 0)
    for i in range(n):
        t = base.replace(month=1 + (i % 12), day=1 + (i % 27), hour=i % 24, minute=i % 60)
        lat = 35.0 + (i % 50) / 100 if i % 4 else None
        lon = 139.0 + (i % 50) / 100 if i % 4 else None
        albums = ["Trip 2023"] if i % 10 == 0 else []
        if i % 15 == 0:
            albums.append("Family")
        items.append(SampleItem(name=f"PXL_2023_{i:04d}.jpg", taken_utc=t, lat=lat, lon=lon,
                                albums=albums, description="sample" if i % 7 == 0 else "",
                                seed=start_seed + i))
    return items


def write_takeout_zip(dest: Path, items: list[SampleItem], *, sidecar_suffix: str = ".supplemental-metadata.json",
                      extra_files: dict[str, bytes] | None = None) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    root = "Takeout/Google Photos"
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_STORED) as z:
        albums: dict[str, list[SampleItem]] = {}
        for it in items:
            data = make_image(it.seed)
            meta = json.dumps(sidecar(it)).encode()
            if it.in_year_folder:
                folder = f"{root}/Photos from {it.taken_utc:%Y}"
                z.writestr(f"{folder}/{it.name}", data)
                z.writestr(f"{folder}/{it.name}{sidecar_suffix}", meta)
            for a in it.albums:
                albums.setdefault(a, []).append(it)
                z.writestr(f"{root}/{a}/{it.name}", data)
                z.writestr(f"{root}/{a}/{it.name}{sidecar_suffix}", meta)
        for a in albums:
            z.writestr(f"{root}/{a}/metadata.json", json.dumps({"title": a}).encode())
        for name, data in (extra_files or {}).items():
            z.writestr(f"{root}/{name}", data)
    return dest
