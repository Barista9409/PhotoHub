"""Repairing arrival-like dates from file names and file times; setting a date by hand."""
import os
import time
from datetime import datetime
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import select

from photohub.api import create_app
from photohub.dates import date_from_name
from photohub.db import Media
from photohub.sample import make_image
from photohub.scanner import scan
from photohub.users import add_user, scan_roots


def test_date_from_name():
    assert date_from_name("Screenshot_20240812-153012_LINE.png") == datetime(2024, 8, 12, 15, 30, 12)
    assert date_from_name("IMG_20230812_143012.jpg") == datetime(2023, 8, 12, 14, 30, 12)
    assert date_from_name("PXL_20231105_091500123.jpg") == datetime(2023, 11, 5, 9, 15, 0)
    assert date_from_name("2021-05-03 10.11.12.png") == datetime(2021, 5, 3, 10, 11, 12)
    assert date_from_name("IMG_8682.PNG") is None
    assert date_from_name("photo_12345678.jpg") is None


def test_repair_and_set_by_hand(settings, db):
    import io
    from PIL import Image
    u, token = add_user(db, settings, "reo")
    root = Path(u.personal_root) / "MobileBackup"
    root.mkdir(parents=True)

    def png(name, seed):
        buf = io.BytesIO()
        Image.new("RGB", (30, 20), (seed, 10, 10)).save(buf, "PNG")
        (root / name).write_bytes(buf.getvalue())
        return root / name
    png("Screenshot_20240812-153012.png", 1)
    old = png("saved.png", 2)
    t = datetime(2018, 3, 4, 5, 6).timestamp()
    nodate = png("IMG_8682.PNG", 3)
    scan(db, settings, scan_roots(db, settings), apply=True)
    os.utime(old, (t, t))  # e.g. Synology Photos' backup kept the original time
    with db.session() as s:  # all three look "arrival-like": dated the day they were indexed
        for m in s.execute(select(Media)).scalars():
            m.taken_at = m.created_at
    c = TestClient(create_app(settings, db))
    h = {"Authorization": f"Bearer {token}"}
    r = c.post("/api/media/repair-dates", headers=h).json()
    assert r == {"from_name": 1, "from_inside": 0, "from_file": 1, "no_clue": 1}
    with db.session() as s:
        by = {Path(m.nas_path).name: m for m in s.execute(select(Media)).scalars()}
        assert by["Screenshot_20240812-153012.png"].taken_at == datetime(2024, 8, 12, 6, 30, 12)  # 15:30 JST
        assert by["saved.png"].taken_at.year == 2018
        hash_nodate = by["IMG_8682.PNG"].content_hash
    assert c.post("/api/media/set-date", json={"hashes": [hash_nodate], "date": "2016-07-20"}, headers=h).json()["changed"] == 1
    with db.session() as s:
        assert s.execute(select(Media).where(Media.content_hash == hash_nodate)).scalar_one().taken_at == datetime(2016, 7, 20, 3, 0)


def test_date_inside_png(settings, tmp_path):
    """A PNG with a creation time written inside (no EXIF date)."""
    import shutil
    import subprocess
    import pytest
    from PIL import Image
    from photohub.dates import date_inside
    if not shutil.which(settings.exiftool_bin):
        pytest.skip("exiftool not installed")
    p = tmp_path / "x.png"
    Image.new("RGB", (10, 10)).save(p)
    subprocess.run([settings.exiftool_bin, "-q", "-overwrite_original", "-PNG:CreationTime=2019:06:07 08:09:10", str(p)], check=True)
    assert date_inside(settings, p) == datetime(2019, 6, 7, 8, 9, 10)
