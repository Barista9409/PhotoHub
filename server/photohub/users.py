"""Family members. Each person gets their own API token (shown once), their own Takeout
inbox (inbox/takeout/<name>/) and, by default, Takeout photos go to their personal space."""
from __future__ import annotations

import hashlib
import secrets
from datetime import timedelta
from pathlib import Path
from urllib.parse import urlencode

from sqlalchemy import select

from .config import Settings
from .db import Database, DeviceToken, Invite, User, log_event, utcnow

INVITE_DAYS = 3


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
    ensure_user_dirs(settings, name)
    return u, token


def ensure_user_dirs(settings: Settings, name: str) -> None:
    """Folders Cloud Sync writes into: Takeout ZIPs and the person's Google Drive window."""
    for d in (settings.inbox / name, settings.inbox.parent / "drive" / name):
        try:
            d.mkdir(parents=True, exist_ok=True)
        except OSError:
            pass


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
    h = hash_token(token)
    with db.session() as s:
        u = s.execute(select(User).where(User.token_hash == h)).scalar_one_or_none()
        if u is None:
            u = s.execute(select(User).join(DeviceToken, DeviceToken.user_id == User.id)
                          .where(DeviceToken.token_hash == h)).scalar_one_or_none()
        return u


# ---- setup QR codes ----------------------------------------------------------------

def create_invite(db: Database, settings: Settings, name: str, *, lan: str | None = None,
                  remote: str | None = None) -> tuple[str, str]:
    """Returns (code, setup link). The link goes into a QR code that the app reads."""
    from .netinfo import lan_url, remote_url
    lan = (lan or lan_url(settings) or "").rstrip("/")
    remote = (remote or remote_url(settings) or "").rstrip("/")
    if not (lan or remote):
        raise ValueError("set PHOTOHUB_LAN_URL / PHOTOHUB_REMOTE_URL (or pass --lan / --remote)")
    code = secrets.token_urlsafe(18)
    with db.session() as s:
        u = s.execute(select(User).where(User.name == name)).scalar_one()
        s.add(Invite(user_id=u.id, code_hash=hash_token(code), expires_at=utcnow() + timedelta(days=INVITE_DAYS)))
        log_event(s, "info", "invite_created", f"setup code for {name} (valid {INVITE_DAYS} days)")
    q = {"v": "1", "user": name, "code": code}
    if lan:
        q["lan"] = lan
    if remote:
        q["remote"] = remote
    return code, "photohub://setup?" + urlencode(q)


def redeem_invite(db: Database, settings: Settings, code: str, device: str | None) -> dict | None:
    """Exchange a setup code for a new device token (once). None = invalid/expired/used."""
    with db.session() as s:
        inv = s.execute(select(Invite).where(Invite.code_hash == hash_token(code or ""))).scalar_one_or_none()
        if inv is None or inv.used_at is not None or inv.expires_at < utcnow():
            return None
        inv.used_at = utcnow()
        u = s.get(User, inv.user_id)
        uid, name, admin = u.id, u.name, u.is_admin
    from .netinfo import lan_url, remote_url
    return {"token": issue_device_token(db, uid, device), "user": name, "admin": admin,
            "lan": lan_url(settings), "remote": remote_url(settings)}


def issue_device_token(db: Database, user_id: int, device: str | None) -> str:
    token = secrets.token_urlsafe(32)
    with db.session() as s:
        u = s.get(User, user_id)
        s.add(DeviceToken(user_id=user_id, token_hash=hash_token(token), device=(device or "")[:128]))
        log_event(s, "info", "device_added", f"{u.name}: {device or 'device'}")
    return token


def write_invite_files(settings: Settings, name: str, link: str) -> tuple[Path, Path]:
    """invites/<name>.png and .html (open the html in File Station / a browser and scan it)."""
    import segno

    d = settings.root / "invites"
    d.mkdir(parents=True, exist_ok=True)
    qr = segno.make(link, error="m")
    png = d / f"{name}.png"
    qr.save(png, scale=10, border=3)
    html = d / f"{name}.html"
    svg = qr.svg_inline(scale=8, border=3)
    html.write_text(f"""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>PhotoHub: {name}さんの設定</title>
<body style="font-family:sans-serif;text-align:center;padding:24px">
<h2>{name}さんのスマホで読み取ってください</h2>
<p>PhotoHubのアプリを開き「QRコードで設定」を押して、このコードを写します。</p>
{svg}
<p>このコードは1回だけ・{INVITE_DAYS}日間有効です。読み取ったら、このファイルは削除してかまいません。</p>
</body>""", encoding="utf-8")
    return png, html


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


def rename_user(db: Database, settings: Settings, user_id: int, new: str) -> User:
    """Give a person their real DSM user name (they were created under the e-mail address they
    logged in with). Their folders move along; the personal space follows the new name."""
    import os
    from .db import Upload
    with db.session() as s:
        u = s.get(User, user_id)
        old = u.name
        if u.personal_root in (None, str(personal_root_default(settings, old))):
            u.personal_root = str(personal_root_default(settings, new))
        u.name = new
        moves = [(settings.inbox / old, settings.inbox / new),
                 (settings.inbox.parent / "drive" / old, settings.inbox.parent / "drive" / new),
                 (settings.root / "incoming" / old, settings.root / "incoming" / new)]
        for a, b in moves:
            if a.exists() and not b.exists():
                try:
                    os.replace(a, b)
                except OSError:
                    continue
                for up in s.execute(select(Upload).where(Upload.user_id == user_id)).scalars():
                    if up.path.startswith(str(a) + "/"):
                        up.path = str(b) + up.path[len(str(a)):]
        log_event(s, "info", "user_renamed", f"{old} -> {new}")
        s.flush()
        s.expunge(u)
    ensure_user_dirs(settings, new)
    return u
