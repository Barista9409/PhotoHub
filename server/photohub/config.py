"""Settings, read from environment variables (see .env.example)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path


def _bool(name: str, default: bool) -> bool:
    v = os.environ.get(name)
    if v is None or v == "":
        return default
    return v.strip().lower() in ("1", "true", "yes", "on")


def _int(name: str, default: int) -> int:
    v = os.environ.get(name)
    return int(v) if v not in (None, "") else default


def _date(name: str) -> date | None:
    v = os.environ.get(name)
    return date.fromisoformat(v) if v else None


@dataclass
class Settings:
    root: Path
    library: Path
    # Safety: every mutating command is a dry run unless apply is set
    # (CLI --apply, or PHOTOHUB_APPLY=true for the daemon).
    apply: bool = False
    gpth_bin: str = "gpth"
    exiftool_bin: str = "exiftool"
    ffmpeg_bin: str = "ffmpeg"
    ffprobe_bin: str = "ffprobe"
    workers: int = 2
    # Hours (local) in which heavy work may run, e.g. "1-7". Empty = always.
    work_hours: str = ""
    stable_seconds: int = 300
    similar_threshold: int = 6
    google_keep_months: int = 12
    trash_days: int = 30
    min_free_gb: int = 200
    amazon_stale_days: int = 7
    takeout_interval_days: int = 61
    takeout_grace_days: int = 10
    takeout_schedule_start: date | None = None
    ntfy_url: str = ""
    ntfy_topic: str = ""
    synology_api: bool = False
    dsm_url: str = ""
    dsm_user: str = ""
    dsm_password: str = ""
    dsm_verify_tls: bool = True
    default_tz_minutes: int = 540  # +09:00
    # extra folders to index (e.g. Synology Photos mobile backup in a personal space)
    scan_extra: list = field(default_factory=list)
    # where the DSM "homes" share is mounted (personal spaces: <homes_root>/<user>/Photos)
    homes_root: Path = Path("/volume1/homes")
    api_host: str = "0.0.0.0"
    api_port: int = 8765
    extra: dict = field(default_factory=dict)

    # ---- derived paths ----
    def within_work_hours(self, hour: int) -> bool:
        if not self.work_hours:
            return True
        start, end = (int(x) for x in self.work_hours.split("-"))
        return start <= hour < end if start <= end else (hour >= start or hour < end)

    @property
    def inbox(self) -> Path:
        return self.root / "inbox" / "takeout"

    @property
    def work(self) -> Path:
        return self.root / "work"

    @property
    def processed(self) -> Path:
        return self.root / "processed"

    @property
    def trash(self) -> Path:
        return self.root / "trash"

    @property
    def db_dir(self) -> Path:
        return self.root / "db"

    @property
    def db_path(self) -> Path:
        return self.db_dir / "photohub.sqlite"

    @property
    def cache(self) -> Path:
        return self.root / "cache"

    @property
    def outbox(self) -> Path:
        return self.root / "amazon-outbox"

    @property
    def app_updates(self) -> Path:
        """Android app releases: photohub-<ver>.apk + photohub-<ver>.json (see app_updates.py)."""
        return self.root / "app-updates"

    @property
    def google_trash(self) -> Path:
        """Takeout items from Google's Trash are kept here, not in the library (D5)."""
        return self.root / "google-trash"

    def ensure_dirs(self) -> None:
        for p in (self.inbox, self.work, self.processed, self.trash, self.db_dir,
                  self.cache, self.outbox, self.google_trash, self.library, self.app_updates):
            p.mkdir(parents=True, exist_ok=True)

    @classmethod
    def from_env(cls, **overrides) -> "Settings":
        s = cls(
            root=Path(os.environ.get("PHOTOHUB_ROOT", "/volume1/photohub")),
            library=Path(os.environ.get("PHOTO_LIBRARY_ROOT", "/volume1/photo")),
            apply=_bool("PHOTOHUB_APPLY", False),
            gpth_bin=os.environ.get("GPTH_BIN", "gpth"),
            exiftool_bin=os.environ.get("EXIFTOOL_BIN", "exiftool"),
            ffmpeg_bin=os.environ.get("FFMPEG_BIN", "ffmpeg"),
            ffprobe_bin=os.environ.get("FFPROBE_BIN", "ffprobe"),
            workers=_int("PHOTOHUB_WORKERS", 2),
            work_hours=os.environ.get("PHOTOHUB_WORK_HOURS", ""),
            stable_seconds=_int("PHOTOHUB_STABLE_SECONDS", 300),
            similar_threshold=_int("PHOTOHUB_SIMILAR_THRESHOLD", 6),
            google_keep_months=_int("PHOTOHUB_GOOGLE_KEEP_MONTHS", 12),
            trash_days=_int("PHOTOHUB_TRASH_DAYS", 30),
            min_free_gb=_int("PHOTOHUB_MIN_FREE_GB", 200),
            amazon_stale_days=_int("PHOTOHUB_AMAZON_STALE_DAYS", 7),
            takeout_interval_days=_int("PHOTOHUB_TAKEOUT_INTERVAL_DAYS", 61),
            takeout_grace_days=_int("PHOTOHUB_TAKEOUT_GRACE_DAYS", 10),
            takeout_schedule_start=_date("PHOTOHUB_TAKEOUT_SCHEDULE_START"),
            ntfy_url=os.environ.get("NTFY_URL", ""),
            ntfy_topic=os.environ.get("NTFY_TOPIC", ""),
            synology_api=_bool("SYNOLOGY_API_ENABLED", False),
            dsm_url=os.environ.get("DSM_URL", ""),
            dsm_user=os.environ.get("DSM_USER", ""),
            dsm_password=os.environ.get("DSM_PASSWORD", ""),
            dsm_verify_tls=_bool("DSM_VERIFY_TLS", True),
            default_tz_minutes=_int("PHOTOHUB_DEFAULT_TZ_MINUTES", 540),
            scan_extra=[Path(p) for p in os.environ.get("PHOTOHUB_SCAN_EXTRA", "").split(":") if p],
            homes_root=Path(os.environ.get("PHOTOHUB_HOMES_ROOT", "/volume1/homes")),
            api_host=os.environ.get("PHOTOHUB_API_HOST", "0.0.0.0"),
            api_port=_int("PHOTOHUB_API_PORT", 8765),
        )
        for k, v in overrides.items():
            setattr(s, k, v)
        return s
