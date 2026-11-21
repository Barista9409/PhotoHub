import subprocess
from pathlib import Path

from photohub.hashing import content_hash, file_sha256, hamming, phash, pixel_sha256, video_stream_sha256
from photohub.metadata import parse_exif_datetime, read_many, to_utc
from photohub.sample import make_image

from .conftest import needs_exiftool, needs_ffmpeg


def test_pixel_hash_ignores_metadata(tmp_path: Path):
    p = tmp_path / "a.jpg"
    p.write_bytes(make_image(1))
    before_file, before_px = file_sha256(p), pixel_sha256(p)
    # rewrite metadata only
    from PIL import Image
    with Image.open(p) as img:
        exif = img.getexif()
    exif[0x010E] = "changed description"
    data = bytearray(p.read_bytes())
    # insert an APP1 segment by re-saving with exif and the same quantisation via exiftool if present
    import shutil
    if shutil.which("exiftool"):
        subprocess.run(["exiftool", "-q", "-overwrite_original", "-ImageDescription=x", "-GPSLatitude=35",
                        "-GPSLatitudeRef=N", str(p)], check=True)
        assert file_sha256(p) != before_file
        assert pixel_sha256(p) == before_px
    assert bytes(data)  # sanity


def test_pixel_hash_differs_for_different_images(tmp_path: Path):
    a, b = tmp_path / "a.jpg", tmp_path / "b.jpg"
    a.write_bytes(make_image(1))
    b.write_bytes(make_image(2))
    assert pixel_sha256(a) != pixel_sha256(b)


def test_phash_near_for_reencoded_copy(tmp_path: Path):
    a, b = tmp_path / "a.jpg", tmp_path / "b.jpg"
    a.write_bytes(make_image(5, quality=95))
    from PIL import Image
    with Image.open(a) as img:
        img.resize((320, 240)).save(b, "JPEG", quality=60)
    assert pixel_sha256(a) != pixel_sha256(b)
    assert hamming(phash(a), phash(b)) <= 6
    c = tmp_path / "c.jpg"
    c.write_bytes(make_image(77))
    assert hamming(phash(a), phash(c)) > 10


@needs_ffmpeg
@needs_exiftool
def test_video_hash_ignores_container_metadata(tmp_path: Path):
    v = tmp_path / "v.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10",
                    "-pix_fmt", "yuv420p", str(v)], check=True)
    h1, f1 = video_stream_sha256(v), file_sha256(v)
    subprocess.run(["exiftool", "-q", "-overwrite_original", "-QuickTime:CreateDate=2020:01:02 03:04:05",
                    str(v)], check=True)
    assert file_sha256(v) != f1
    assert video_stream_sha256(v) == h1
    assert content_hash(v, "video", file_sha256(v)) == h1


def test_raw_uses_file_hash(tmp_path: Path):
    r = tmp_path / "x.dng"
    r.write_bytes(b"not really raw")
    fh = file_sha256(r)
    assert content_hash(r, "photo", fh) == "f:" + fh


def test_parse_datetime_and_offsets():
    dt, off = parse_exif_datetime("2023:08:08 16:07:00+09:00")
    assert off == 540 and to_utc(dt, off).hour == 7
    dt, off = parse_exif_datetime("2023:08:08 07:07:00")
    assert off is None and dt.hour == 7
    assert parse_exif_datetime("0000:00:00 00:00:00") == (None, None)


@needs_exiftool
def test_read_many_local_time_without_offset_uses_default(tmp_path: Path):
    p = tmp_path / "a.jpg"
    p.write_bytes(make_image(3))
    subprocess.run(["exiftool", "-q", "-overwrite_original", "-DateTimeOriginal=2024:05:01 12:00:00", str(p)], check=True)
    md = read_many([p], {p: "photo"}, default_tz=540)[p]
    assert md.taken_at.hour == 3 and md.tz_offset == 540
