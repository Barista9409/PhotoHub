"""File operations that follow the safety rules (spec §7).

  * never overwrite an existing file
  * copy -> verify by hash -> only then touch the source
  * dry_run: compute and report, change nothing
"""
from __future__ import annotations

import os
import shutil
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

from .hashing import file_sha256


class SafetyError(RuntimeError):
    pass


@dataclass
class PlanResult:
    path: Path
    already_present: bool  # an identical file is already there (idempotent re-run)


def library_dir(library: Path, taken_utc: datetime | None, tz_offset: int | None,
                fallback: datetime) -> Path:
    when = taken_utc or fallback
    if tz_offset is not None:
        when = when + timedelta(minutes=tz_offset)
    return library / f"{when:%Y}" / f"{when:%Y-%m}"


def plan_destination(dest_dir: Path, name: str, file_hash: str) -> PlanResult:
    """Pick a non-colliding name. Collision -> append the first 8 chars of the hash."""
    cand = dest_dir / name
    if not cand.exists():
        return PlanResult(cand, False)
    if cand.is_file() and file_sha256(cand) == file_hash:
        return PlanResult(cand, True)
    p = Path(name)
    cand2 = dest_dir / f"{p.stem}_{file_hash[:8]}{p.suffix}"
    if not cand2.exists():
        return PlanResult(cand2, False)
    if cand2.is_file() and file_sha256(cand2) == file_hash:
        return PlanResult(cand2, True)
    for i in range(2, 1000):
        cand3 = dest_dir / f"{p.stem}_{file_hash[:8]}_{i}{p.suffix}"
        if not cand3.exists():
            return PlanResult(cand3, False)
        if file_sha256(cand3) == file_hash:
            return PlanResult(cand3, True)
    raise SafetyError(f"too many name collisions for {name} in {dest_dir}")


def copy_verified(src: Path, dst: Path, expected_hash: str | None = None) -> None:
    """Copy src to dst (which must not exist), verify, keep src untouched."""
    if dst.exists():
        raise SafetyError(f"refusing to overwrite {dst}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    expected = expected_hash or file_sha256(src)
    tmp = dst.with_name(f".{dst.name}.photohub-tmp")
    if tmp.exists():
        tmp.unlink()  # our own leftover from an interrupted run
    shutil.copy2(src, tmp)
    got = file_sha256(tmp)
    if got != expected:
        tmp.unlink()
        raise SafetyError(f"hash mismatch after copy {src} -> {dst}")
    if dst.exists():
        tmp.unlink()
        raise SafetyError(f"refusing to overwrite {dst}")
    os.replace(tmp, dst)


def move_verified(src: Path, dst: Path, expected_hash: str | None = None) -> None:
    """Move src to dst (must not exist). Same filesystem: atomic rename (data untouched).
    Otherwise copy -> verify -> remove the source."""
    if dst.exists():
        raise SafetyError(f"refusing to overwrite {dst}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    try:
        same_fs = os.stat(src).st_dev == os.stat(dst.parent).st_dev
    except OSError:
        same_fs = False
    if same_fs:
        if expected_hash and file_sha256(src) != expected_hash:
            raise SafetyError(f"source changed before move: {src}")
        os.rename(src, dst)
        return
    copy_verified(src, dst, expected_hash)
    src.unlink()


def unique_in(dir_: Path, name: str) -> Path:
    cand = dir_ / name
    i = 1
    while cand.exists():
        p = Path(name)
        cand = dir_ / f"{p.stem}.{i}{p.suffix}"
        i += 1
    return cand
