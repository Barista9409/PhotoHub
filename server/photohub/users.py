"""Family members. Each person gets their own API token (shown once), their own Takeout
inbox (inbox/takeout/<name>/) and, by default, Takeout photos go to their personal space."""
from __future__ import annotations

import hashlib
import secrets
from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import Database, User, log_event


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def personal_root_default(settings: Settings, name: str) -> Path:
    # Synology Photos personal space: /volume1/homes/<user>/Photos (mounted at homes_root)
    return settings.homes_root / name / "Photos"


def add_user(db: Database, settings: Settings, name: str, *, admin: bool = False,
             personal_root: str | None = None, takeout_target: str = "personal") -> tuple[User, str]:
    if not name or "/" in name or name.startswith("."):
        raise ValueError("invalid name")
    if takeout_target not in ("personal", "shared"):
        raise ValueError("takeout_target must be personal or shared")
    token = secrets.token_urlsafe(32)
    with db.session() as s:
        if s.execute(select(User).where(User.name == name)).scalar_one_or_none():
            raise ValueError(f"user {name} already exists")
        u = User(name=name, is_admin=admin, token_hash=hash_token(token),
                 personal_root=str(personal_root or personal_root_default(settings, name)),
                 takeout_target=takeout_target)
        s.add(u)
        s.flush()
        log_event(s, "info", "user_added", f"user {name} (admin={admin})")
    (settings.inbox / name).mkdir(parents=True, exist_ok=True)
    return u, token


def reset_token(db: Database, name: str) -> str:
    token = secrets.token_urlsafe(32)
    with db.session() as s:
        u = s.execute(select(User).where(User.name == name)).scalar_one()
        u.token_hash = hash_token(token)
        log_event(s, "info", "user_token_reset", f"user {name}")
    return token


def by_token(db: Database, token: str) -> User | None:
    if not token:
        return None
    with db.session() as s:
        return s.execute(select(User).where(User.token_hash == hash_token(token))).scalar_one_or_none()


def by_name(db: Database, name: str) -> User | None:
    with db.session() as s:
        return s.execute(select(User).where(User.name == name)).scalar_one_or_none()


def all_users(db: Database) -> list[User]:
    with db.session() as s:
        return list(s.execute(select(User).order_by(User.id)).scalars())


def scan_roots(db: Database, settings: Settings) -> list[tuple[Path, int | None]]:
    """(folder, owner) pairs to index: the shared space, each person's personal space, extras."""
    roots: list[tuple[Path, int | None]] = [(settings.library, None)]
    for u in all_users(db):
        if u.personal_root and Path(u.personal_root).exists():
            roots.append((Path(u.personal_root), u.id))
    roots += [(p, None) for p in settings.scan_extra]
    return roots
