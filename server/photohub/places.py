"""Place names for photos, offline (GeoNames table built into the image; see tools/build_places.py).
Nothing about where photos were taken leaves the NAS. Japan: "東京都 渋谷区"; abroad: "Paris, France"."""
from __future__ import annotations

import math
import os
from pathlib import Path

from sqlalchemy import select

from .db import Database, Media, kv_get, kv_set, touch_media

DEFAULT = Path(os.environ.get("PHOTOHUB_PLACES", "/opt/geo/places.tsv"))
_grid: dict[tuple[int, int], list[tuple[float, float, str]]] | None = None


def _load(path: Path) -> dict:
    grid: dict[tuple[int, int], list[tuple[float, float, str]]] = {}
    with path.open(encoding="utf-8") as f:
        for line in f:
            lat, lon, cc, region, name = line.rstrip("\n").split("\t")
            la, lo = float(lat), float(lon)
            label = f"{region} {name}".strip() if cc == "JP" else f"{name}, {region}"
            grid.setdefault((math.floor(la), math.floor(lo)), []).append((la, lo, label))
    return grid


def name_for(lat: float, lon: float, path: Path = DEFAULT) -> str | None:
    """The nearest town (within about 30 km), or None."""
    global _grid
    if _grid is None:
        if not path.exists():
            return None
        _grid = _load(path)
    best, best_d = None, 0.3 ** 2  # ~30 km in degrees (rough, enough to pick the nearest town)
    k = math.cos(math.radians(lat))
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for la, lo, label in _grid.get((math.floor(lat) + dy, math.floor(lon) + dx), ()):
                d = (la - lat) ** 2 + ((lo - lon) * k) ** 2
                if d < best_d:
                    best, best_d = label, d
    return best


def assign(db: Database, limit: int = 20000) -> int:
    """Give photos with GPS a place name (stored in kv as place:<media id>); returns how many."""
    n = 0
    with db.session() as s:
        for m in s.execute(select(Media).where(Media.lat.is_not(None), Media.lon.is_not(None), Media.status == "active")).scalars():
            key = f"place:{m.id}"
            if kv_get(s, key) is not None:
                continue
            kv_set(s, key, name_for(m.lat, m.lon) or "")
            touch_media(s, m)  # phones pick the name up at the next sync
            n += 1
            if n >= limit:
                break
    return n


def place_of(s, m: Media) -> str | None:  # noqa: ANN001
    return kv_get(s, f"place:{m.id}") or None
