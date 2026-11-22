"""Offline place names: table build and nearest-town lookup."""
from pathlib import Path

from photohub import places


def _gn(tmp: Path) -> Path:
    (tmp / "admin1CodesASCII.txt").write_text("JP.40\tTokyo\tTokyo\t1850144\nFR.11\tÎle-de-France\tIle-de-France\t3012874\n", encoding="utf-8")
    (tmp / "countryInfo.txt").write_text("#ISO\tISO3\tnum\tfips\tCountry\nJP\tJPN\t392\tJA\tJapan\nFR\tFRA\t250\tFR\tFrance\n", encoding="utf-8")
    row = lambda gid, name, alt, lat, lon, cc, a1: "\t".join([gid, name, name, alt, lat, lon, "P", "PPLA", cc, "", a1] + [""] * 8) + "\n"
    (tmp / "cities1000.txt").write_text(
        row("1", "Shibuya", "Shibuya,シブヤ,渋谷区", "35.6640", "139.6982", "JP", "40")
        + row("2", "Paris", "Paris,パリ", "48.8534", "2.3488", "FR", "11"), encoding="utf-8")
    return tmp


def test_build_and_lookup(tmp_path):
    import importlib.util
    spec = importlib.util.spec_from_file_location("bp", Path(__file__).parents[1] / "tools" / "build_places.py")
    bp = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bp)
    out = tmp_path / "places.tsv"
    bp.main(_gn(tmp_path), out)
    places._grid = None
    assert places.name_for(35.66, 139.70, out) == "東京都 渋谷区"
    assert places.name_for(48.86, 2.35, out) == "Paris, France"
    assert places.name_for(0.0, 0.0, out) is None  # nowhere near a town
    places._grid = None
