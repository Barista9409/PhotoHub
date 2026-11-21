"""P3: the resident service - folder watch, schedules, notifications and the REST API.

Changes files only when PHOTOHUB_APPLY=true. Otherwise every job runs as a dry run and the
results show up in the job history / events, which is how the first run on real photos is done
(spec §9 instruction 3).
"""
from __future__ import annotations

import logging
import threading
import time
from datetime import datetime

import uvicorn
from apscheduler.schedulers.background import BackgroundScheduler
from watchdog.events import FileSystemEventHandler
from watchdog.observers.polling import PollingObserver

from . import derivatives, notify, scanner, similar, storage, synology
from .api import create_app
from .config import Settings
from .db import Database, kv_get, kv_set, log_event
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
        if not self.s.apply:
            # a dry-run scan hashes the whole library; once is enough for review
            with self.db.session() as s:
                if kv_get(s, "dry_scan_done"):
                    return
        rep = scanner.scan(self.db, self.s, scan_roots(self.db, self.s), apply=self.s.apply)
        if not self.s.apply:
            with self.db.session() as s:
                kv_set(s, "dry_scan_done", "1")
        if rep.duplicates:
            scanner.write_duplicate_report(rep, self.s.root / "reports" / f"duplicates-{datetime.now():%Y%m%d}.tsv")
        if not rep.dry_run:
            self._after_import()

    def job_storage(self) -> None:
        storage.verify_nas(self.db, self.s, limit=20000)
        if self.s.amazon_backup:
            storage.fill_outbox(self.db, self.s, apply=self.s.apply)
        for u in all_users(self.db):  # each person's own Google account
            storage.google_candidates(self.db, self.s, u.id)

    def job_health(self) -> None:
        notify.health_check(self.db, self.s)

    def job_backup(self) -> None:
        self.db.backup(self.s.db_dir / "backup")

    # ---- wiring ------------------------------------------------------------------
    def start_background(self) -> BackgroundScheduler:
        sch = BackgroundScheduler(job_defaults={"coalesce": True, "max_instances": 1, "misfire_grace_time": 3600})
        sch.add_job(lambda: self._guard("inbox", self.job_inbox), "interval", minutes=10, id="inbox",
                    next_run_time=datetime.now())
        sch.add_job(lambda: self._guard("incoming", self.job_incoming), "interval", minutes=5, id="incoming")
        sch.add_job(lambda: self._guard("scan", self.job_scan), "cron", hour=2, minute=30, id="scan")
        sch.add_job(lambda: self._guard("storage", self.job_storage), "cron", hour=5, minute=10, id="storage")
        sch.add_job(lambda: self._guard("health", self.job_health, heavy=False), "interval", hours=1, id="health",
                    next_run_time=datetime.now())
        sch.add_job(lambda: self._guard("backup", self.job_backup, heavy=False), "cron", hour=4, minute=0, id="backup")
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
    Service(settings, db).start_background()
    from .netinfo import Advertiser, lan_url, remote_url
    adv = Advertiser(settings)
    log.info("home address %s, Tailscale address %s, mDNS %s", lan_url(settings) or "?",
             remote_url(settings) or "none", "on" if adv.start() else "off")
    uvicorn.run(create_app(settings, db), host=settings.api_host, port=settings.api_port, log_level="info")
