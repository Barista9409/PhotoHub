"""Encrypted storage for a person's Synology password (only for those who agreed in the app, to keep
their Synology Photos albums in sync). The key is created on first use in the database folder
(readable only by PhotoHub) and never leaves the NAS."""
from __future__ import annotations

import json
import os
from pathlib import Path

from cryptography.fernet import Fernet, InvalidToken

from .config import Settings


def _key(settings: Settings) -> bytes:
    p = Path(settings.db_dir) / "vault.key"
    if not p.exists():
        p.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(Fernet.generate_key())
    return p.read_bytes().strip()


def seal(settings: Settings, data: dict) -> str:
    return Fernet(_key(settings)).encrypt(json.dumps(data).encode()).decode()


def open_(settings: Settings, token: str | None) -> dict | None:
    if not token:
        return None
    try:
        return json.loads(Fernet(_key(settings)).decrypt(token.encode()))
    except (InvalidToken, ValueError):
        return None
