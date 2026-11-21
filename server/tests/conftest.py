import os
import shutil
from pathlib import Path

import pytest

from photohub.config import Settings
from photohub.db import Database

GPTH = os.environ.get("GPTH_BIN") or shutil.which("gpth") or ("/opt/gpth/gpth" if Path("/opt/gpth/gpth").exists() else None)

needs_gpth = pytest.mark.skipif(GPTH is None, reason="GPTH binary not available")
needs_exiftool = pytest.mark.skipif(shutil.which("exiftool") is None, reason="exiftool not installed")
needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg not installed")


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    s = Settings(root=tmp_path / "photohub", library=tmp_path / "photo", gpth_bin=GPTH or "gpth",
                 stable_seconds=0, workers=2, api_token="test-token")
    s.ensure_dirs()
    return s


@pytest.fixture
def db(settings: Settings) -> Database:
    return Database(settings.db_path)
