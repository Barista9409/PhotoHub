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
from .users import add_user, issue_device_token, rename_user

RESULT = {400: "bad_password", 401: "disabled", 402: "denied", 403: "otp_required", 404: "bad_otp",
          406: "otp_setup_required", 407: "blocked", 408: "password_expired", 409: "password_expired",
          410: "password_expired"}
NOT_ALLOWED = {"admin", "guest"}


def check_password(settings: Settings, username: str, password: str, otp: str | None = None,
                   who: dict | None = None, device_token: str | None = None) -> str:
    """'ok' or a reason (see RESULT; 'unreachable' when DSM does not answer).
    With `who`, also fills in the real DSM user name ("name") and e-mail ("email"):
    DSM accepts an e-mail address as the login name, but folders are named after the user name."""
    data = {"api": "SYNO.API.Auth", "version": "6", "method": "login", "account": username,
            "passwd": password, "session": "PhotoHubLogin", "format": "sid"}
    if otp:
        data["otp_code"] = otp
    if who is not None and "remember" in who:
        # ask DSM for a device token, so PhotoHub can log in again later without the 2-step code
        data.update({"enable_device_token": "yes", "device_name": "PhotoHub"})
    if device_token:
        data["device_id"] = device_token
    try:
        with httpx.Client(base_url=dsm_url(settings), verify=settings.dsm_verify_tls, timeout=20) as c:
            body = c.post("/webapi/entry.cgi", data=data).json()
            if body.get("success"):
                sid = (body.get("data") or {}).get("sid")
                if who is not None:
                    who.update(_identity(c, sid or ""))
                    did = (body.get("data") or {}).get("did")
                    if did:
                        who["did"] = did
                try:
                    c.post("/webapi/entry.cgi", data={"api": "SYNO.API.Auth", "version": "6", "method": "logout",
                                                      "session": "PhotoHubLogin", "_sid": sid or ""})
                except httpx.HTTPError:
                    pass
                return "ok"
            return RESULT.get((body.get("error") or {}).get("code"), "bad_password")
    except (httpx.HTTPError, ValueError):
        return "unreachable"


def _identity(c: httpx.Client, sid: str) -> dict:
    """Ask DSM who just logged in: the user's own settings, else the path of their home folder."""
    out: dict = {}
    try:
        b = c.post("/webapi/entry.cgi", data={"api": "SYNO.Core.NormalUser", "version": "1", "method": "get",
                                              "_sid": sid}).json()
        if b.get("success"):
            d = b.get("data") or {}
            if d.get("username"):
                out["name"] = str(d["username"])
            if d.get("email"):
                out["email"] = str(d["email"])
    except (httpx.HTTPError, ValueError):
        pass
    if "name" not in out:
        try:
            b = c.post("/webapi/entry.cgi", data={"api": "SYNO.FileStation.List", "version": "2", "method": "list_share",
                                                  "additional": '["real_path"]', "_sid": sid}).json()
            for sh in ((b.get("data") or {}).get("shares") or []) if b.get("success") else []:
                real = ((sh.get("additional") or {}).get("real_path") or "").rstrip("/")
                if sh.get("name") == "home" and "/homes/" in real:
                    out["name"] = real.rsplit("/", 1)[-1]
                    break
        except (httpx.HTTPError, ValueError):
            pass
    return out


def login(db: Database, settings: Settings, username: str, password: str, otp: str | None,
          device: str | None, link_photos: bool = False) -> tuple[str, dict | None]:
    """Returns (status, result). status 'ok' -> result has token/user/admin."""
    username = (username or "").strip()
    if not username or username.lower() in NOT_ALLOWED:
        return "denied", None
    who: dict = {"remember": True} if link_photos else {}
    status = check_password(settings, username, password, otp, who=who)
    who.pop("remember", None)
    if status != "ok":
        return status, None
    name = who.get("name") or username
    if "@" in name:
        # logged in with an e-mail address and DSM did not say the user name: the person's
        # Synology Photos folder cannot be found, so ask for the user name instead
        return "need_username", None
    if name.lower() in NOT_ALLOWED:
        return "denied", None
    with db.session() as s:
        u = s.execute(select(User).where(User.name == name)).scalar_one_or_none()
        if u is None:
            # created earlier under the e-mail address (older versions): take it over
            for alias in {username, who.get("email") or ""} - {"", name}:
                old = s.execute(select(User).where(User.name == alias)).scalar_one_or_none()
                if old is not None:
                    u = old
                    break
    created = False
    if u is not None and u.name != name:
        u = rename_user(db, settings, u.id, name)
        created = True  # its personal space is new to the index
    if u is None:
        if not settings.auto_users:
            return "not_registered", None
        u, _ = add_user(db, settings, name, admin=name in settings.admins)
        created = True
    if created:
        # index their Synology Photos space now, so the app shows it without waiting for the next scan
        import threading
        from .prescan import refresh_index
        threading.Thread(target=lambda: refresh_index(db, settings, u), daemon=True, name="first-scan").start()
    if link_photos:
        from .vault import seal
        with db.session() as s:
            s.get(User, u.id).dsm_secret = seal(settings, {"account": username, "password": password,
                                                           "did": who.get("did")})
    token = issue_device_token(db, u.id, device)
    return "ok", {"token": token, "user": u.name, "admin": u.is_admin}
