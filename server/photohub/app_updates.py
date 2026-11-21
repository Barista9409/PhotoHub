"""In-app updates for the Android app, served from the NAS (family use only; nothing public).

Publishing = putting the two files that `./gradlew publishUpdate` writes into
<root>/app-updates/ (File Station upload is fine):

    photohub-0.3.0.apk
    photohub-0.3.0.json   {"versionCode": 3, "versionName": "0.3.0", "notes": "...", "apk": "photohub-0.3.0.apk"}

The newest versionCode whose APK exists is offered. The app checks the SHA-256 given here and,
on the phone, that the package name matches and the APK is signed with the same key as the
installed app before installing, so a wrong or tampered file is never installed.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .config import Settings
from .hashing import file_sha256

_sha_cache: dict[tuple[str, float, int], str] = {}


@dataclass
class Release:
    version_code: int
    version_name: str
    notes: str
    apk: Path
    size: int
    sha256: str

    def to_json(self) -> dict:
        return {"version_code": self.version_code, "version_name": self.version_name, "notes": self.notes,
                "size": self.size, "sha256": self.sha256}


def _sha(p: Path) -> str:
    st = p.stat()
    key = (str(p), st.st_mtime, st.st_size)
    if key not in _sha_cache:
        _sha_cache[key] = file_sha256(p)
    return _sha_cache[key]


def releases(settings: Settings) -> list[Release]:
    out = []
    for mf in settings.app_updates.glob("*.json"):
        try:
            d = json.loads(mf.read_text(encoding="utf-8"))
            apk = (settings.app_updates / Path(d.get("apk") or mf.with_suffix(".apk").name).name)
            if not apk.is_file():
                continue
            out.append(Release(int(d["versionCode"]), str(d.get("versionName", "")), str(d.get("notes", "")),
                               apk, apk.stat().st_size, ""))
        except (ValueError, KeyError, OSError):
            continue
    return sorted(out, key=lambda r: r.version_code)


def latest(settings: Settings) -> Release | None:
    rs = releases(settings)
    if not rs:
        return None
    r = rs[-1]
    r.sha256 = _sha(r.apk)
    return r


def publish(settings: Settings, apk: Path, version_code: int, version_name: str, notes: str = "") -> Path:
    """CLI helper: copy an APK in and write its manifest."""
    from .fileops import copy_verified

    settings.app_updates.mkdir(parents=True, exist_ok=True)
    dst = settings.app_updates / f"photohub-{version_name}.apk"
    if not dst.exists():
        copy_verified(apk, dst)
    mf = dst.with_suffix(".json")
    mf.write_text(json.dumps({"versionCode": version_code, "versionName": version_name, "notes": notes,
                              "apk": dst.name}, ensure_ascii=False, indent=1), encoding="utf-8")
    return mf
