"""Build the offline place-name table used by photohub.places (run while building the image).

Input (GeoNames, CC BY 4.0): cities1000.txt, admin1CodesASCII.txt, countryInfo.txt
Output: places.tsv  -> lat, lon, country code, admin1 (prefecture) name, place name
Japanese names are used where GeoNames has them (Japan: 東京都 / 渋谷区 ...).
"""
import re
import sys
from pathlib import Path

JA = re.compile(r"[぀-ヿ]")          # kana: surely Japanese
CJK = re.compile(r"[㐀-鿿]")

PREF = {  # GeoNames ascii admin1 name -> Japanese prefecture
    "hokkaido": "北海道", "aomori": "青森県", "iwate": "岩手県", "miyagi": "宮城県", "akita": "秋田県", "yamagata": "山形県",
    "fukushima": "福島県", "ibaraki": "茨城県", "tochigi": "栃木県", "gunma": "群馬県", "saitama": "埼玉県", "chiba": "千葉県",
    "tokyo": "東京都", "kanagawa": "神奈川県", "niigata": "新潟県", "toyama": "富山県", "ishikawa": "石川県", "fukui": "福井県",
    "yamanashi": "山梨県", "nagano": "長野県", "gifu": "岐阜県", "shizuoka": "静岡県", "aichi": "愛知県", "mie": "三重県",
    "shiga": "滋賀県", "kyoto": "京都府", "osaka": "大阪府", "hyogo": "兵庫県", "nara": "奈良県", "wakayama": "和歌山県",
    "tottori": "鳥取県", "shimane": "島根県", "okayama": "岡山県", "hiroshima": "広島県", "yamaguchi": "山口県",
    "tokushima": "徳島県", "kagawa": "香川県", "ehime": "愛媛県", "kochi": "高知県", "fukuoka": "福岡県", "saga": "佐賀県",
    "nagasaki": "長崎県", "kumamoto": "熊本県", "oita": "大分県", "miyazaki": "宮崎県", "kagoshima": "鹿児島県", "okinawa": "沖縄県",
}


def ja_name(alternates: str) -> str | None:
    """The Japanese written name: 渋谷区 rather than シブヤ (reading) or a Chinese name."""
    names = [n for n in alternates.split(",") if n]
    for n in names:            # kanji ending like a Japanese municipality
        if CJK.search(n) and n[-1] in "市区町村郡島":
            return n
    for n in names:            # kanji with kana (e.g. 霞ケ浦)
        if CJK.search(n) and JA.search(n):
            return n
    for n in names:            # kana only
        if JA.search(n):
            return n
    return None


def main(src: Path, out: Path) -> None:
    admin1 = {}
    for line in (src / "admin1CodesASCII.txt").read_text(encoding="utf-8").splitlines():
        code, name, ascii_, _ = (line.split("\t") + ["", "", "", ""])[:4]
        cc = code.split(".")[0]
        key = ascii_.lower().replace(" prefecture", "").replace("-", "").strip()
        admin1[code] = PREF.get(key, name) if cc == "JP" else name
    countries = {}
    for line in (src / "countryInfo.txt").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            f = line.split("\t")
            countries[f[0]] = f[4]
    n = 0
    with out.open("w", encoding="utf-8") as w:
        for line in (src / "cities1000.txt").open(encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            name, alternates, lat, lon, cc, a1 = f[1], f[3], f[4], f[5], f[8], f[10]
            label = (ja_name(alternates) if cc == "JP" else None) or name
            region = admin1.get(f"{cc}.{a1}", "") if cc == "JP" else countries.get(cc, cc)
            w.write(f"{float(lat):.4f}\t{float(lon):.4f}\t{cc}\t{region}\t{label}\n")
            n += 1
    print(f"{n} places -> {out}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
