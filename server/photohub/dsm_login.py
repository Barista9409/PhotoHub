"""Family members log in with their DSM (Synology) user name and password.

PhotoHub never stores the password: it asks DSM once (SYNO.API.Auth login, then logout) and,
if DSM says yes, issues a token for that phone. 2-step verification codes are passed through.
"""
from __future__ import annotations

import httpx
from sqlalchemy import select

from .config import Settings
from .db import Database, User
from .netinfo import dsm_url
from .users import add_user, issue_device_token

RESULT = {400: "bad_password", 401: "disabled", 402: "denied", 403: "otp_required", 404: "bad_otp",
          406: "otp_setup_required", 407: "blocked", 408: "password_expired", 409: "password_expired",
          410: "password_expired"}
NOT_ALLOWED = {"admin", "guest"}


def check_password(settings: Settings, username: str, password: str, otp: str | None = None) -> str:
    """'ok' or a reason (see RESULT; 'unreachable' when DSM does not answer)."""
    data = {"api": "SYNO.API.Auth", "version": "6", "method": "login", "account": username,
            "passwd": password, "session": "PhotoHubLogin", "format": "sid"}
    if otp:
        data["otp_code"] = otp
    try:
        with httpx.Client(base_url=dsm_url(settings), verify=settings.dsm_verify_tls, timeout=20) as c:
            body = c.post("/webapi/entry.cgi", data=data).json()
            if body.get("success"):
                sid = (body.get("data") or {}).get("sid")
                try:
                    c.post("/webapi/entry.cgi", data={"api": "SYNO.API.Auth", "version": "6", "method": "logout",
                                                      "session": "PhotoHubLogin", "_sid": sid or ""})
                except httpx.HTTPError:
                    pass
                return "ok"
            return RESULT.get((body.get("error") or {}).get("code"), "bad_password")
    except (httpx.HTTPError, ValueError):
        return "unreachable"


def login(db: Database, settings: Settings, username: str, password: str, otp: str | None,
          device: str | None) -> tuple[str, dict | None]:
    """Returns (status, result). status 'ok' -> result has token/user/admin."""
    username = (username or "").strip()
    if not username or username.lower() in NOT_ALLOWED:
        return "denied", None
    status = check_password(settings, username, password, otp)
    if status != "ok":
        return status, None
    with db.session() as s:
        u = s.execute(select(User).where(User.name == username)).scalar_one_or_none()
    if u is None:
        if not settings.auto_users:
            return "not_registered", None
        u, _ = add_user(db, settings, username, admin=username in settings.admins)
    token = issue_device_token(db, u.id, device)
    return "ok", {"token": token, "user": u.name, "admin": u.is_admin}
