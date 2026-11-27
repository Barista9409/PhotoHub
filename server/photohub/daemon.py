"""P3: the resident service - folder watch, schedules, notifications and the REST API.

Changes files only when PHOTOHUB_APPLY=true. Otherwise every job runs as a dry run and the
results show up in the job history / events, which is how the first run on real photos is done
(spec §9 instruction 3).
"""
from __future__ import annotations

import logging
import os
import threading
import time
from datetime import datetime, timedelta

import uvicorn
from apscheduler.schedulers.background import BackgroundScheduler
from watchdog.events import FileSystemEventHandler
from watchdog.observers.polling import PollingObserver

from . import derivatives, notify, scanner, similar, storage, synology
from .api import create_app
from .config import Settings
from .db import utcnow, Database, kv_get, kv_set, log_event
from .takeout import import_inbox
from .users import all_users, scan_roots

log = logging.getLogger("photohub")


class Service:
    def __init__(self, settings: Settings, db: Database):
        self.s, self.db = settings, db
        self.heavy = threading.Lock()  # one heavy job at a time (NAS load, spec §7)
        self._kick = threading.Event()

    def _guard(self, name: str, fn, heavy: bool = True) -> None:  # noqa: ANN001
        if heavy and not self.s.within_work_hours(datetime.now().hour):
            return
        if heavy and not self.heavy.acquire(blocking=False):
            return
        try:
            fn()
        except Exception as e:
            log.exception("%s failed", name)
            with self.db.session() as s:
                log_event(s, "error", "daemon_error", f"{name}: {e}")
        finally:
            if heavy:
                self.heavy.release()
            notify.flush(self.db, self.s)

    # ---- jobs ----------------------------------------------------------------
    def job_inbox(self) -> None:
        reports = import_inbox(self.db, self.s, apply=self.s.apply)
        if any(r.new or r.merged for r in reports if not r.dry_run):
            from .photos_link import mark_due
            with self.db.session() as s:
                mark_due(s, None)   # Takeout photos were placed: Synology Photos is asked to look again
            self._after_import()

    def job_incoming(self) -> None:
        from datetime import timedelta

        from .incoming import process_incoming
        if not self.s.apply:
            # dry run: uploads just wait; report on them once a day instead of every 5 minutes
            with self.db.session() as s:
                last = kv_get(s, "dry_incoming_at")
                if last and datetime.fromisoformat(last) > datetime.now() - timedelta(days=1):
                    return
                kv_set(s, "dry_incoming_at", datetime.now().isoformat())
        rep = process_incoming(self.db, self.s, apply=self.s.apply)
        if rep.new and not rep.dry_run:
            self._after_import()

    def _after_import(self) -> None:
        derivatives.build_all(self.db, self.s)
        similar.refresh(self.db, self.s)
        synology.sync_albums(self.db, self.s, apply=self.s.apply)

    def job_scan(self) -> None:
        # Indexing only reads the photos, so it always runs and is saved (that is what makes
        # Synology Photos' own backups show up in the app). Moving PhotoHub's own duplicate
        # copies to trash still waits for PHOTOHUB_APPLY.
        rep = scanner.scan(self.db, self.s, scan_roots(self.db, self.s), apply=True, move_files=self.s.apply)
        if rep.duplicates:
            scanner.write_duplicate_report(rep, self.s.root / "reports" / f"duplicates-{datetime.now():%Y%m%d}.tsv")
        if rep.new:
            self._after_import()

    def job_storage(self) -> None:
        storage.verify_nas(self.db, self.s, limit=20000)
        if self.s.amazon_backup:
            storage.fill_outbox(self.db, self.s, apply=self.s.apply)
        for u in all_users(self.db):  # each person's own Google account
            storage.google_candidates(self.db, self.s, u.id)

    def job_albums(self) -> None:
        from . import albums
        albums.pull_all(self.db, self.s)

    def job_thumbs(self) -> None:
        """Make the small pictures ahead of time (newest first), so phones never wait for them."""
        derivatives.build_all(self.db, self.s, limit=4000, kinds=("thumb",))
        derivatives.refresh_dark_video_thumbs(self.db, self.s)
        from . import organize
        organize.measure_sharpness(self.db, self.s)   # 「ぼやけた写真」 ready before anyone opens it

    def job_dates(self) -> None:
        from .dates import repair
        from .organize import remove_system_thumbs
        remove_system_thumbs(self.db, self.s)
        repair(self.db, self.s)
        from .organize import refresh_duplicates
        refresh_duplicates(self.db, self.s)   # 「まったく同じ写真」 ready before anyone opens it (hourly)

    def job_photos_reindex(self) -> None:
        from .photos_link import reindex_due
        reindex_due(self.db, self.s)

    def job_playable(self) -> None:
        from . import playable
        playable.prepare_all(self.db, self.s, budget_gb=self.s.playable_max_gb)

    def job_autoalbums(self) -> None:
        from . import autoalbums
        autoalbums.run_all(self.db, self.s)

    def job_places(self) -> None:
        from . import places
        places.assign(self.db)

    def job_trash(self) -> None:
        from . import organize
        organize.move_pending(self.db, self.s)
        organize.purge(self.db, self.s, older_than_days=self.s.trash_days)

    def job_space_v2(self) -> None:
        """Once: photos people shared with the family are sent to the phones again, now marked as theirs
        (minefam) for their owner, so マイフォト keeps them."""
        from sqlalchemy import select as _select
        from .db import Media, kv_get, kv_set, touch_media
        with self.db.session() as s:
            if kv_get(s, "space_v2"):
                return
            for m in s.execute(_select(Media).where(Media.family_shared.is_(True))).scalars():
                touch_media(s, m)
            kv_set(s, "space_v2", "1")

    def job_dup_restore(self) -> None:
        """Once after the duplicate keep rule changed: the files the old rule moved go back, then the list
        is made again with the new rule."""
        from . import organize
        if organize.restore_removed_duplicates(self.db, self.s) >= 0:
            organize.refresh_duplicates(self.db, self.s)

    def job_health(self) -> None:
        notify.health_check(self.db, self.s)

    def job_backup(self) -> None:
        self.db.backup(self.s.db_dir / "backup")

    # ---- wiring ------------------------------------------------------------------
    def mark_interrupted(self) -> int:
        """Scans and phone/Drive runs cut off by a restart would show 'running' forever: mark them.
        (Takeout jobs stay 'running': the importer resumes those.) The next scan picks up where it stopped."""
        from sqlalchemy import select
        from .db import ImportJob
        n = 0
        with self.db.session() as s:
            for j in s.execute(select(ImportJob).where(ImportJob.state == "running",
                                                       ImportJob.source.in_(["phone_scan", "phone"]))).scalars():
                j.state, j.finished_at = "interrupted", j.finished_at or utcnow()
                j.message = (j.message or "") + " (stopped by a restart; continued by the next run)"
                n += 1
        return n

    def start_background(self) -> BackgroundScheduler:
        sch = BackgroundScheduler(job_defaults={"coalesce": True, "max_instances": 1, "misfire_grace_time": 3600})
        sch.add_job(lambda: self._guard("inbox", self.job_inbox), "interval", minutes=10, id="inbox",
                    next_run_time=datetime.now())
        sch.add_job(lambda: self._guard("incoming", self.job_incoming), "interval", minutes=5, id="incoming")
        # new photos from Synology Photos' phone backup show up within a few hours (unchanged files are skipped quickly)
        sch.add_job(lambda: self._guard("scan", self.job_scan), "interval", hours=3, id="scan",
                    next_run_time=datetime.now() + timedelta(minutes=2))
        sch.add_job(lambda: self._guard("storage", self.job_storage), "cron", hour=5, minute=10, id="storage")
        sch.add_job(lambda: self._guard("health", self.job_health, heavy=False), "interval", hours=1, id="health",
                    next_run_time=datetime.now())
        sch.add_job(lambda: self._guard("backup", self.job_backup, heavy=False), "cron", hour=4, minute=0, id="backup")
        sch.add_job(lambda: self._guard("trash", self.job_trash, heavy=False), "cron", hour=4, minute=30, id="trash")
        sch.add_job(lambda: self._guard("space_v2", self.job_space_v2, heavy=False), "date",
                    run_date=datetime.now() + timedelta(seconds=30), id="space_v2")
        sch.add_job(lambda: self._guard("dup_restore", self.job_dup_restore, heavy=False), "date",
                    run_date=datetime.now() + timedelta(minutes=1), id="dup_restore")
        sch.add_job(lambda: self._guard("thumbs", self.job_thumbs), "interval", minutes=20, id="thumbs",
                    next_run_time=datetime.now() + timedelta(minutes=4))
        sch.add_job(lambda: self._guard("dates", self.job_dates, heavy=False), "interval", hours=1, id="dates",
                    next_run_time=datetime.now() + timedelta(minutes=6))
        sch.add_job(lambda: self._guard("albums", self.job_albums, heavy=False), "interval", minutes=15, id="albums",
                    next_run_time=datetime.now() + timedelta(minutes=3))
        sch.add_job(lambda: self._guard("places", self.job_places, heavy=False), "interval", hours=1, id="places",
                    next_run_time=datetime.now() + timedelta(minutes=5))
        # photos PhotoHub put in Synology Photos' folders: ask it to look again (it does not notice by itself)
        sch.add_job(lambda: self._guard("photos_reindex", self.job_photos_reindex, heavy=False), "interval", minutes=5,
                    id="photos_reindex", next_run_time=datetime.now() + timedelta(minutes=2))
        # phone-friendly copies of videos, made ahead of time (hourly, up to 50 minutes a run)
        sch.add_job(lambda: self._guard("playable", self.job_playable), "interval", hours=1, id="playable",
                    next_run_time=datetime.now() + timedelta(minutes=8))
        # ふりかえり / 旅行 / フォルダ albums: every night, and once soon after starting
        sch.add_job(lambda: self._guard("autoalbums", self.job_autoalbums, heavy=False), "cron", hour=3, minute=40,
                    id="autoalbums")
        sch.add_job(lambda: self._guard("autoalbums", self.job_autoalbums, heavy=False), "date",
                    run_date=datetime.now() + timedelta(minutes=12), id="autoalbums-first")
        sch.start()

        svc = self

        class Handler(FileSystemEventHandler):
            def on_any_event(self, event):  # noqa: ANN001
                if str(event.src_path).lower().endswith(".zip"):
                    svc._kick.set()

        # Polling observer: works for files written by Cloud Sync and over bind mounts alike.
        obs = PollingObserver(timeout=60)
        obs.schedule(Handler(), str(self.s.inbox), recursive=False)
        obs.daemon = True
        obs.start()

        def debounce() -> None:
            while True:
                self._kick.wait()
                self._kick.clear()
                time.sleep(self.s.stable_seconds + 5)  # wait until the ZIP is stable
                self._guard("inbox", self.job_inbox)

        threading.Thread(target=debounce, daemon=True).start()
        return sch


def serve(settings: Settings, db: Database) -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings.ensure_dirs()
    if not all_users(db):
        log.warning("no users yet: create one with `photohub user add NAME --admin`")
    log.info("PhotoHub starting (apply=%s, library=%s)", settings.apply, settings.library)
    with db.session() as s:
        log_event(s, "info", "daemon_start", f"apply={settings.apply}")
    svc = Service(settings, db)
    svc.mark_interrupted()
    sch = svc.start_background()
    from .netinfo import Advertiser, lan_url, remote_url
    adv = Advertiser(settings)
    log.info("home address %s, Tailscale address %s, mDNS %s", lan_url(settings) or "?",
             remote_url(settings) or "none", "on" if adv.start() else "off")
    uvicorn.run(create_app(settings, db), host=settings.api_host, port=settings.api_port, log_level="info")
    # Stop right away when Container Manager stops the container: a scan or import that is running
    # would otherwise keep the process alive until it finishes. All work resumes on the next start
    # (scans skip what is already indexed, imports are re-run safely).
    log.info("PhotoHub stopping")
    try:
        sch.shutdown(wait=False)
    except Exception:  # noqa: BLE001
        pass
    logging.shutdown()
    os._exit(0)
