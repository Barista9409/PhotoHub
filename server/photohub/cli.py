"""photohub command line. Every command that changes files is a DRY RUN unless --apply is given
(spec §7 rule 6, §9 instruction 3)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import click

from .config import Settings
from .db import Database


def _ctx(root: str | None, library: str | None) -> tuple[Settings, Database]:
    over = {}
    if root:
        over["root"] = Path(root)
    if library:
        over["library"] = Path(library)
    s = Settings.from_env(**over)
    s.ensure_dirs()
    return s, Database(s.db_path)


apply_opt = click.option("--apply", is_flag=True, default=False,
                         help="Actually change files. Without it, only report what would happen.")
root_opt = click.option("--root", envvar="PHOTOHUB_ROOT", default=None, help="PhotoHub data root")
lib_opt = click.option("--library", envvar="PHOTO_LIBRARY_ROOT", default=None, help="Photo library root")


def _banner(apply: bool) -> None:
    if not apply:
        click.secho("DRY-RUN: nothing will be changed. Re-run with --apply to execute.", fg="yellow", err=True)


@click.group()
def main() -> None:
    """PhotoHub - Takeout import, dedupe and sync for Synology Photos."""


@main.command("init")
@root_opt
@lib_opt
def init_cmd(root, library):  # noqa: ANN001
    """Create folders and the database."""
    s, _ = _ctx(root, library)
    click.echo(f"root={s.root} library={s.library} db={s.db_path}")


@main.command("import-takeout")
@root_opt
@lib_opt
@apply_opt
@click.option("--no-wait", is_flag=True, help="Do not wait for ZIPs to be stable (manual runs)")
def import_takeout(root, library, apply, no_wait):  # noqa: ANN001
    """F1-F3: import Takeout ZIPs from inbox/takeout."""
    from .takeout import import_inbox

    _banner(apply)
    s, db = _ctx(root, library)
    reports = import_inbox(db, s, apply=apply, wait_stable=not no_wait)
    if not reports:
        click.echo("no (stable) archives in inbox")
    for r in reports:
        click.echo("already imported, moved to processed" if r.skipped_job and apply else
                   ("already imported" if r.skipped_job else r.summary()))
        for e in r.errors[:20]:
            click.echo(f"  error: {e}")
    sys.exit(1 if any(r.error for r in reports) else 0)


@main.command("scan")
@root_opt
@lib_opt
@apply_opt
@click.option("--limit", type=int, default=None, help="Stop after N files (resume later)")
def scan_cmd(root, library, apply, limit):  # noqa: ANN001
    """P2: index the library (+ PHOTOHUB_SCAN_EXTRA) and report duplicates. Read-only on photos."""
    from .scanner import scan, write_duplicate_report

    _banner(apply)
    s, db = _ctx(root, library)
    rep = scan(db, s, s.scan_roots, apply=apply, limit=limit)
    click.echo(rep.summary())
    if rep.duplicates:
        out = write_duplicate_report(rep, s.root / "reports" / "duplicates-latest.tsv")
        click.echo(f"duplicate report: {out}")
    for e in rep.errors[:20]:
        click.echo(f"  error: {e}")


@main.command("derive")
@root_opt
@lib_opt
@click.option("--limit", type=int, default=None)
def derive_cmd(root, library, limit):  # noqa: ANN001
    """F7: build missing thumbnails/previews (writes only to cache/)."""
    from .derivatives import build_all

    s, db = _ctx(root, library)
    ok, fail = build_all(db, s, limit=limit)
    click.echo(f"built={ok} failed={fail}")


@main.group("similar")
def similar_grp() -> None:
    """F2: similar photo groups."""


@similar_grp.command("refresh")
@root_opt
@lib_opt
def similar_refresh(root, library):  # noqa: ANN001
    from .similar import refresh

    s, db = _ctx(root, library)
    click.echo(f"new groups: {refresh(db, s)}")


@similar_grp.command("resolve")
@root_opt
@lib_opt
@apply_opt
@click.argument("group_id", type=int)
@click.option("--keep", "keep", multiple=True, type=int, required=True, help="media id to keep (repeatable)")
def similar_resolve(root, library, apply, group_id, keep):  # noqa: ANN001
    """Keep the given photos; move the rest of the group to trash/."""
    from .similar import resolve

    _banner(apply)
    s, db = _ctx(root, library)
    for a in resolve(db, s, group_id, set(keep), apply=apply):
        click.echo(a)


@main.command("outbox")
@root_opt
@lib_opt
@apply_opt
@click.option("--limit", type=int, default=None)
def outbox_cmd(root, library, apply, limit):  # noqa: ANN001
    """F4: hard-link photos into amazon-outbox/ for the PC Amazon Photos app."""
    from .storage import fill_outbox

    _banner(apply)
    s, db = _ctx(root, library)
    click.echo(fill_outbox(db, s, apply=apply, limit=limit))


@main.command("verify")
@root_opt
@lib_opt
@click.option("--limit", type=int, default=None)
def verify_cmd(root, library, limit):  # noqa: ANN001
    """F4: re-hash NAS files old enough to be Google deletion candidates (read-only)."""
    from .storage import verify_nas

    s, db = _ctx(root, library)
    click.echo(verify_nas(db, s, limit=limit))


@main.command("candidates")
@root_opt
@lib_opt
@click.option("--confirm", "confirm_id", type=int, default=None,
              help="Mark candidate ID as deleted from Google by you")
def candidates_cmd(root, library, confirm_id):  # noqa: ANN001
    """F4: months that may be deleted from Google Photos (PhotoHub never deletes them)."""
    from .storage import confirm_cleanup, google_candidates

    s, db = _ctx(root, library)
    if confirm_id is not None:
        click.echo(f"marked removed: {confirm_cleanup(db, s, confirm_id)}")
        return
    rows = google_candidates(db, s)
    if not rows:
        click.echo("no candidates")
    for r in rows:
        label = "exact" if r["match"] == "exact" else "storage-saver (check a few first)"
        done = f" confirmed {r['confirmed_at'][:10]}" if r["confirmed_at"] else ""
        click.echo(f"#{r['id']} {r['month']} {label}: photos={r['photos']} videos={r['videos']}{done}"
                   + (f"  NOTE: {r['note']}" if r["note"] else ""))


@main.command("health")
@root_opt
@lib_opt
def health_cmd(root, library):  # noqa: ANN001
    """F5: run health checks now and send pending notifications."""
    from .notify import flush, health_check

    s, db = _ctx(root, library)
    click.echo(f"raised: {health_check(db, s) or 'nothing'}; sent: {flush(db, s)}")


@main.command("notify-test")
@root_opt
@lib_opt
def notify_test(root, library):  # noqa: ANN001
    from .notify import send

    s, _ = _ctx(root, library)
    click.echo("sent" if send(s, "PhotoHub test", "Notification test from PhotoHub") else "not sent (check NTFY_URL/NTFY_TOPIC)")


@main.command("backup-db")
@root_opt
@lib_opt
def backup_db(root, library):  # noqa: ANN001
    s, db = _ctx(root, library)
    click.echo(db.backup(s.db_dir / "backup"))


@main.command("status")
@root_opt
@lib_opt
def status_cmd(root, library):  # noqa: ANN001
    """Counts per store and the latest jobs."""
    from sqlalchemy import func, select

    from .db import ImportJob, Media, kv_get
    from .storage import store_counts

    s, db = _ctx(root, library)
    with db.session() as ses:
        n = dict(ses.execute(select(Media.kind, func.count()).where(Media.status == "active")
                             .group_by(Media.kind)).all())
        jobs = list(ses.execute(select(ImportJob).order_by(ImportJob.id.desc()).limit(5)).scalars())
        click.echo(json.dumps({"media": n, "stores": store_counts(db),
                               "last_import_at": kv_get(ses, "last_import_at"),
                               "last_scan_at": kv_get(ses, "last_scan_at")}, ensure_ascii=False, indent=1))
        for j in jobs:
            click.echo(f"job #{j.id} {j.source} {j.state}: {j.message or ''}")


@main.group("synology")
def synology_grp() -> None:
    """Synology Photos API (D1) - verify on the real NAS before enabling."""


@synology_grp.command("probe")
@root_opt
@lib_opt
def synology_probe(root, library):  # noqa: ANN001
    """Log in, list the Photos APIs and the top folders, then log out. Changes nothing."""
    from .synology import Client

    s, _ = _ctx(root, library)
    c = Client(s)
    info = c.api_info()
    click.echo(f"{len(info)} Photos APIs: " + ", ".join(sorted(k for k in info if 'Album' in k or 'Index' in k)))
    c.login()
    try:
        click.echo(json.dumps(c.call("SYNO.FotoTeam.Browse.Folder", "list", 1, offset=0, limit=20, id=0),
                              ensure_ascii=False)[:2000])
    finally:
        c.logout()


@synology_grp.command("sync")
@root_opt
@lib_opt
@apply_opt
def synology_sync(root, library, apply):  # noqa: ANN001
    """Re-index and push albums (needs SYNOLOGY_API_ENABLED=true)."""
    from .synology import sync_albums

    _banner(apply)
    s, db = _ctx(root, library)
    click.echo(sync_albums(db, s, apply=apply))


@main.command("make-sample")
@click.argument("dest", type=click.Path())
@click.option("-n", "count", default=100, show_default=True)
def make_sample(dest, count):  # noqa: ANN001
    """Write a synthetic Takeout ZIP (no real photos) to try PhotoHub safely."""
    from .sample import default_items, write_takeout_zip

    click.echo(write_takeout_zip(Path(dest), default_items(count)))


@main.command("serve")
@root_opt
@lib_opt
def serve_cmd(root, library):  # noqa: ANN001
    """P3: run the resident service (scheduler + folder watch + API)."""
    from .daemon import serve

    s, db = _ctx(root, library)
    serve(s, db)


if __name__ == "__main__":
    main()
