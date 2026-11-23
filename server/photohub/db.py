"""Database models (spec §5) and session helpers.

Additions to the spec, decided in docs/research.md:
  media.hash_version   how content_hash was computed (§5.1)
  media.tz_offset      minutes east of UTC at capture time (§5.3)
  media.pair_key       links Live Photo / motion photo halves (§5.4)
  media.rev            monotonically increasing change number for delta sync (F7)
  location.match       exact / similar (D4: storage-saver copies on Google)
  google_cleanup       batches of Google deletion candidates and their confirmation
  kv                   small key/value store (rev counter, heartbeats)

Family use (several people share one NAS):
  app_user             family members; each has their own API token, Google account and
                       Synology personal space. is_admin may organise shared photos.
  media.owner_id       NULL = the file is in the shared space (visible to everyone);
                       a user id = the file is in that person's personal space.
  media_access         who may see a personal-space item: its owner, plus anyone whose own
                       Google Takeout contained the same photo (they already have it).
  location.user_id     whose Google account an item is in (0 for nas/amazon/...).
  album.owner_id       Google albums belong to the person whose Takeout they came from.
"""
from __future__ import annotations

import shutil
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator

from sqlalchemy import (Boolean, DateTime, Float, ForeignKey, Integer, String, Text,
                        UniqueConstraint, create_engine, event, select)
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker


def utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "app_user"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(64), unique=True)  # same as the DSM user name
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    personal_root: Mapped[str | None] = mapped_column(Text)  # container path of their Synology Photos personal space
    takeout_target: Mapped[str] = mapped_column(String(16), default="personal")  # personal / shared
    takeout_schedule_start: Mapped[str | None] = mapped_column(String(10))  # YYYY-MM-DD
    # where this person's Drive folder "PhotoHub" appears on the NAS (Cloud Sync). Default:
    # <root>/inbox/drive/<name>. Set when an existing Cloud Sync task already syncs their whole Drive.
    drive_dir: Mapped[str | None] = mapped_column(Text)
    # where photos sent from the phone go, relative to personal_root (chosen in the app, because each
    # person may have pointed Synology Photos' own phone backup somewhere else). None = MobileBackup/<phone>
    backup_dir: Mapped[str | None] = mapped_column(Text)
    # the folder in their home where Cloud Sync puts their Google Drive (chosen in the app);
    # Drive photos are read from <it>/PhotoHub and Takeout ZIPs from <it>/Takeout. None = found automatically
    drive_root: Mapped[str | None] = mapped_column(Text)
    # chosen separately in the app, any depth inside their home (relative paths):
    drive_photos_dir: Mapped[str | None] = mapped_column(Text)  # where Drive's "PhotoHub" folder arrives
    takeout_dir: Mapped[str | None] = mapped_column(Text)        # where Takeout ZIPs arrive
    # Synology login for Synology Photos albums (encrypted, see vault.py), only if the person agreed
    dsm_secret: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class DeviceToken(Base):
    """One token per phone, so a person can use several phones and drop one without the others."""
    __tablename__ = "device_token"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("app_user.id"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    device: Mapped[str | None] = mapped_column(String(128))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Invite(Base):
    """Single-use setup code shown as a QR code (expires; the QR never contains a token)."""
    __tablename__ = "invite"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("app_user.id"), index=True)
    code_hash: Mapped[str] = mapped_column(String(64), unique=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime)
    used_at: Mapped[datetime | None] = mapped_column(DateTime)


class Media(Base):
    __tablename__ = "media"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    content_hash: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    hash_version: Mapped[int] = mapped_column(Integer, default=1)
    file_hash: Mapped[str] = mapped_column(String(64), index=True)
    phash: Mapped[str | None] = mapped_column(String(16), index=True)
    kind: Mapped[str] = mapped_column(String(8))  # photo / video
    mime: Mapped[str | None] = mapped_column(String(64))
    taken_at: Mapped[datetime | None] = mapped_column(DateTime, index=True)  # UTC
    tz_offset: Mapped[int | None] = mapped_column(Integer)
    lat: Mapped[float | None] = mapped_column(Float)
    lon: Mapped[float | None] = mapped_column(Float)
    width: Mapped[int | None] = mapped_column(Integer)
    height: Mapped[int | None] = mapped_column(Integer)
    duration: Mapped[float | None] = mapped_column(Float)
    bytes: Mapped[int] = mapped_column(Integer)
    nas_path: Mapped[str] = mapped_column(Text, index=True)  # relative to library root
    description: Mapped[str | None] = mapped_column(Text)
    pair_key: Mapped[str | None] = mapped_column(String(255), index=True)
    favorite: Mapped[bool] = mapped_column(Boolean, default=False)
    # turn to show it upright, clockwise degrees (0/90/180/270), on top of the file's own orientation.
    # The file itself is left untouched; the app turns the picture when it shows it.
    rotation: Mapped[int] = mapped_column(Integer, default=0)
    # 「家族に共有」: a person's own photo the whole family may see (the file stays in their Photos)
    family_shared: Mapped[bool] = mapped_column(Boolean, default=False)
    status: Mapped[str] = mapped_column(String(16), default="active")  # active / trashed
    owner_id: Mapped[int | None] = mapped_column(Integer, index=True)  # NULL = shared space
    # True = PhotoHub wrote this file (Takeout / phone upload). If the same photo later turns up
    # elsewhere (e.g. Synology Photos' own backup), PhotoHub's copy is the one moved to trash.
    placed: Mapped[bool] = mapped_column(Boolean, default=False)
    rev: Mapped[int] = mapped_column(Integer, index=True, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class MediaAccess(Base):
    __tablename__ = "media_access"
    media_id: Mapped[int] = mapped_column(ForeignKey("media.id"), primary_key=True)
    user_id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)


class Location(Base):
    __tablename__ = "location"
    __table_args__ = (UniqueConstraint("media_id", "store", "user_id"),)
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    media_id: Mapped[int] = mapped_column(ForeignKey("media.id"), index=True)
    store: Mapped[str] = mapped_column(String(16))  # nas/google/amazon/offsite/phone
    user_id: Mapped[int] = mapped_column(Integer, default=0, index=True)  # google: whose account
    state: Mapped[str] = mapped_column(String(16))  # present/assumed/removed (+ queued for amazon)
    match: Mapped[str] = mapped_column(String(16), default="exact")  # exact/similar
    source_ref: Mapped[str | None] = mapped_column(Text)
    seen_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    # nas: last time the file was re-hashed and matched (F4 candidates require it)
    verified_at: Mapped[datetime | None] = mapped_column(DateTime)


class Album(Base):
    __tablename__ = "album"
    __table_args__ = (UniqueConstraint("name", "origin", "owner_id"),)
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(Text)
    origin: Mapped[str] = mapped_column(String(16))  # google/synology/manual
    owner_id: Mapped[int | None] = mapped_column(Integer, index=True)  # NULL = shared
    synology_album_id: Mapped[int | None] = mapped_column(Integer)
    rev: Mapped[int] = mapped_column(Integer, default=0, index=True)


class AlbumItem(Base):
    __tablename__ = "album_item"
    album_id: Mapped[int] = mapped_column(ForeignKey("album.id"), primary_key=True)
    media_id: Mapped[int] = mapped_column(ForeignKey("media.id"), primary_key=True)
    synced: Mapped[bool] = mapped_column(Boolean, default=False)


class SimilarGroup(Base):
    __tablename__ = "similar_group"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    status: Mapped[str] = mapped_column(String(16), default="open")  # open/resolved
    owner_id: Mapped[int | None] = mapped_column(Integer, index=True)  # groups never mix spaces
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class SimilarMember(Base):
    __tablename__ = "similar_member"
    group_id: Mapped[int] = mapped_column(ForeignKey("similar_group.id"), primary_key=True)
    media_id: Mapped[int] = mapped_column(ForeignKey("media.id"), primary_key=True)
    keep: Mapped[bool | None] = mapped_column(Boolean)


class ImportJob(Base):
    __tablename__ = "import_job"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    source: Mapped[str] = mapped_column(String(16))  # takeout/phone_scan
    user_id: Mapped[int | None] = mapped_column(Integer, index=True)  # whose Takeout
    archive_name: Mapped[str] = mapped_column(Text, index=True)
    archive_key: Mapped[str | None] = mapped_column(String(64), index=True)
    started_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime)
    new_count: Mapped[int] = mapped_column(Integer, default=0)
    dup_count: Mapped[int] = mapped_column(Integer, default=0)
    error_count: Mapped[int] = mapped_column(Integer, default=0)
    state: Mapped[str] = mapped_column(String(16), default="running")  # running/done/failed/dry_run
    message: Mapped[str | None] = mapped_column(Text)


class ImportItem(Base):
    __tablename__ = "import_item"
    __table_args__ = (UniqueConstraint("job_id", "original_path"),)
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    job_id: Mapped[int] = mapped_column(ForeignKey("import_job.id"), index=True)
    original_path: Mapped[str] = mapped_column(Text)
    media_id: Mapped[int | None] = mapped_column(ForeignKey("media.id"))
    result: Mapped[str] = mapped_column(String(16))  # new/duplicate/merged/error/skipped
    message: Mapped[str | None] = mapped_column(Text)


class Event(Base):
    __tablename__ = "event"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)
    level: Mapped[str] = mapped_column(String(8))  # info/warn/error
    kind: Mapped[str] = mapped_column(String(32), index=True)
    message: Mapped[str] = mapped_column(Text)
    notified: Mapped[bool] = mapped_column(Boolean, default=False)


class GoogleCleanup(Base):
    __tablename__ = "google_cleanup"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(Integer, default=0, index=True)
    month: Mapped[str] = mapped_column(String(7), index=True)  # YYYY-MM
    match: Mapped[str] = mapped_column(String(16))  # exact/similar
    photo_count: Mapped[int] = mapped_column(Integer, default=0)
    video_count: Mapped[int] = mapped_column(Integer, default=0)
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime)
    __table_args__ = (UniqueConstraint("user_id", "month", "match"),)


class Upload(Base):
    """A file a phone sent (or one seen in the Drive folder), and what became of it."""
    __tablename__ = "upload"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(Integer, index=True)
    source: Mapped[str] = mapped_column(String(8))  # phone / drive
    file_hash: Mapped[str] = mapped_column(String(64), index=True)
    path: Mapped[str] = mapped_column(Text)
    size: Mapped[int] = mapped_column(Integer)
    mtime: Mapped[float] = mapped_column(Float, default=0)
    name: Mapped[str] = mapped_column(Text)
    received_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    processed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    media_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    held: Mapped[bool] = mapped_column(Boolean, default=False)  # dry-run: waiting for apply
    device: Mapped[str | None] = mapped_column(String(128))     # phone name (Synology's backup folder)
    rel_path: Mapped[str | None] = mapped_column(Text)          # folder on the phone, e.g. DCIM/Camera/
    rev: Mapped[int] = mapped_column(Integer, default=0, index=True)  # when it was linked to a photo (phones ask for newer)


class ScanState(Base):
    """P2: per-file progress so a full library scan can stop and resume."""
    __tablename__ = "scan_state"
    path: Mapped[str] = mapped_column(Text, primary_key=True)
    size: Mapped[int] = mapped_column(Integer)
    mtime: Mapped[float] = mapped_column(Float)
    content_hash: Mapped[str | None] = mapped_column(String(80), index=True)
    media_id: Mapped[int | None] = mapped_column(Integer)
    seen_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class MediaTag(Base):
    """What is in a photo ("犬", "海", ...), from Synology Photos' recognition: for searching."""
    __tablename__ = "media_tag"
    media_id: Mapped[int] = mapped_column(ForeignKey("media.id"), primary_key=True)
    tag: Mapped[str] = mapped_column(String(80), primary_key=True, index=True)


class Favorite(Base):
    """A person's ♡ (kept on the Synology, so it survives a new phone)."""
    __tablename__ = "favorite"
    user_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    media_id: Mapped[int] = mapped_column(ForeignKey("media.id"), primary_key=True, index=True)
    at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class KV(Base):
    __tablename__ = "kv"
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[str] = mapped_column(Text)


# --------------------------------------------------------------------------

def make_engine(db_path: Path | str) -> Engine:
    url = "sqlite:///:memory:" if str(db_path) == ":memory:" else f"sqlite:///{db_path}"
    engine = create_engine(url, future=True, connect_args={"timeout": 60, "check_same_thread": False})

    @event.listens_for(engine, "connect")
    def _pragmas(dbapi_conn, _):  # noqa: ANN001
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute("PRAGMA synchronous=NORMAL")
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()

    return engine


class Database:
    def __init__(self, db_path: Path | str):
        self.path = db_path
        if str(db_path) != ":memory:":
            Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self.engine = make_engine(db_path)
        Base.metadata.create_all(self.engine)
        self._add_missing_columns()
        self._sm = sessionmaker(self.engine, expire_on_commit=False, future=True)

    @contextmanager
    def session(self) -> Iterator[Session]:
        s = self._sm()
        try:
            yield s
            s.commit()
        except Exception:
            s.rollback()
            raise
        finally:
            s.close()

    def _add_missing_columns(self) -> None:
        """Upgrade an existing database in place: add columns that newer versions defined.
        (New tables are created by create_all; new columns need ALTER TABLE in SQLite.)"""
        from sqlalchemy import inspect, text
        insp = inspect(self.engine)
        with self.engine.begin() as conn:
            for table in Base.metadata.sorted_tables:
                if not insp.has_table(table.name):
                    continue
                have = {c["name"] for c in insp.get_columns(table.name)}
                for col in table.columns:
                    if col.name in have or col.primary_key:
                        continue
                    ddl = f'ALTER TABLE "{table.name}" ADD COLUMN "{col.name}" {col.type.compile(self.engine.dialect)}'
                    default = col.default.arg if col.default is not None and not callable(col.default.arg) else None
                    if default is not None:
                        ddl += f" DEFAULT {int(default) if isinstance(default, bool) else repr(default)}"
                    conn.execute(text(ddl))

    def new_session(self) -> Session:
        """A session the caller commits/rolls back itself (used for dry-run rollback)."""
        return self._sm()

    def backup(self, dest_dir: Path, keep: int = 14) -> Path:
        """Daily online backup with sqlite's backup API; keeps the newest `keep` files."""
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / f"photohub-{datetime.now():%Y%m%d}.sqlite"
        src = sqlite3.connect(str(self.path))
        dst = sqlite3.connect(str(dest))
        with dst:
            src.backup(dst)
        src.close()
        dst.close()
        backups = sorted(dest_dir.glob("photohub-*.sqlite"))
        for old in backups[:-keep]:
            old.unlink()
        return dest


def next_rev(s: Session) -> int:
    row = s.get(KV, "rev")
    if row is None:
        row = KV(key="rev", value="0")
        s.add(row)
    val = int(row.value) + 1
    row.value = str(val)
    s.flush()
    return val


def kv_get(s: Session, key: str, default: str | None = None) -> str | None:
    row = s.get(KV, key)
    return row.value if row else default


def kv_set(s: Session, key: str, value: str) -> None:
    row = s.get(KV, key)
    if row is None:
        s.add(KV(key=key, value=value))
    else:
        row.value = value


def log_event(s: Session, level: str, kind: str, message: str) -> Event:
    e = Event(level=level, kind=kind, message=message)
    s.add(e)
    s.flush()
    return e


def touch_media(s: Session, m: Media) -> None:
    m.rev = next_rev(s)
    m.updated_at = utcnow()


def set_location(s: Session, media_id: int, store: str, state: str, *,
                 match: str = "exact", source_ref: str | None = None, user_id: int = 0) -> Location:
    loc = s.execute(select(Location).where(Location.media_id == media_id, Location.store == store,
                                           Location.user_id == user_id)).scalar_one_or_none()
    if loc is None:
        loc = Location(media_id=media_id, store=store, state=state, match=match, user_id=user_id,
                       source_ref=source_ref, seen_at=utcnow())
        s.add(loc)
    else:
        # never downgrade an exact match to similar
        if not (loc.match == "exact" and match == "similar"):
            loc.match = match
        loc.state = state
        loc.seen_at = utcnow()
        if source_ref:
            loc.source_ref = source_ref
    s.flush()
    return loc


def grant_access(s: Session, m: Media, user_id: int | None) -> bool:
    """Let `user_id` see a personal-space item (no-op for shared items). Returns True if added."""
    if user_id is None or m.owner_id is None or m.owner_id == user_id:
        return False
    if s.get(MediaAccess, (m.id, user_id)) is None:
        s.add(MediaAccess(media_id=m.id, user_id=user_id))
        s.flush()
        return True
    return False


def visible_clause(user: "User | None"):  # noqa: ANN201
    """SQL condition: media the user may see (shared, own, or granted)."""
    from sqlalchemy import or_
    if user is None:
        return Media.owner_id.is_(None)
    return or_(Media.owner_id.is_(None), Media.owner_id == user.id, Media.family_shared.is_(True),
               Media.id.in_(select(MediaAccess.media_id).where(MediaAccess.user_id == user.id)))


def can_see(s: Session, user: "User", m: Media) -> bool:
    return (m.owner_id is None or m.owner_id == user.id or bool(m.family_shared)
            or s.get(MediaAccess, (m.id, user.id)) is not None)


def disk_free_gb(path: Path) -> float:
    return shutil.disk_usage(path).free / 1e9
