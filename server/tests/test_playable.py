"""Phone-friendly video copies made ahead of time."""
import shutil
import subprocess

import pytest
from sqlalchemy import select

from photohub import playable
from photohub.db import Media


def _box(kind: bytes, payload: bytes) -> bytes:
    return (8 + len(payload)).to_bytes(4, "big") + kind + payload


def test_faststart_detection(tmp_path):
    late = tmp_path / "phone.mp4"
    late.write_bytes(_box(b"ftyp", b"isom" * 2) + _box(b"mdat", b"\0" * 1000) + _box(b"moov", b"x" * 50))
    early = tmp_path / "web.mp4"
    early.write_bytes(_box(b"ftyp", b"isom" * 2) + _box(b"moov", b"x" * 50) + _box(b"mdat", b"\0" * 1000))
    assert not playable.is_faststart(late)
    assert playable.is_faststart(early)


@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="ffmpeg not installed")
def test_prepare_all_makes_copies_for_late_index_videos(settings, db):
    src = settings.library / "VID_1.mp4"
    src.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", str(src)], check=True)   # index at the end (no faststart)
    with db.session() as s:
        s.add(Media(content_hash="v1", file_hash="f1", kind="video", bytes=src.stat().st_size, nas_path=str(src), status="active"))
    with db.session() as s:
        m = s.execute(select(Media)).scalar_one()
        s.expunge(m)
    assert playable.wants_copy(m)
    r = playable.prepare_all(db, settings)
    assert r["made"] == 1 and playable.is_faststart(playable.path_for(settings, m))
    assert playable.prepare_all(db, settings)["made"] == 0   # already there
