"""Before PhotoHub writes new photos for a person, bring the index of their space up to date,
so photos Synology Photos (or anyone) already put there count as "already have"."""
from __future__ import annotations

from pathlib import Path

from .config import Settings
from .db import Database, User


def refresh_index(db: Database, settings: Settings, user: User | None) -> None:
    from .scanner import scan
    roots: list = [(settings.library, None)]
    if user is not None and user.personal_root and Path(user.personal_root).exists():
        roots.insert(0, (Path(user.personal_root), user.id))
    scan(db, settings, roots, apply=True)  # incremental: unchanged files are skipped
