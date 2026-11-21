"""XMP sidecars (spec §5): albums and descriptions next to each photo, so the DB can be
rebuilt from the library alone. Sidecar name: "<file name>.xmp" (e.g. IMG_1.HEIC.xmp) so a
Live Photo's HEIC and MOV never share one.

We only ever write sidecars that we created ourselves (marked with the photohub namespace);
a foreign .xmp is left alone.
"""
from __future__ import annotations

import os
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from xml.sax.saxutils import escape

NS_PH = "https://photohub.local/ns/1.0/"


def sidecar_path(media_path: Path) -> Path:
    return media_path.with_name(media_path.name + ".xmp")


def render(*, content_hash: str, albums: list[str], description: str | None,
           taken_at: datetime | None, tz_offset: int | None) -> str:
    bag = "".join(f"<rdf:li>{escape(a)}</rdf:li>" for a in sorted(set(albums)))
    desc = (f"<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">{escape(description)}"
            f"</rdf:li></rdf:Alt></dc:description>") if description else ""
    date = ""
    if taken_at is not None:
        date = f"<photohub:TakenAtUTC>{taken_at.isoformat()}Z</photohub:TakenAtUTC>"
        if tz_offset is not None:
            date += f"<photohub:TzOffsetMinutes>{tz_offset}</photohub:TzOffsetMinutes>"
    return (
        '<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>\n'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/">\n'
        ' <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
        '  <rdf:Description rdf:about=""\n'
        '    xmlns:dc="http://purl.org/dc/elements/1.1/"\n'
        f'    xmlns:photohub="{NS_PH}">\n'
        f"   <photohub:ContentHash>{escape(content_hash)}</photohub:ContentHash>\n"
        f"   {date}\n"
        f"   <photohub:Albums><rdf:Bag>{bag}</rdf:Bag></photohub:Albums>\n"
        f"   <dc:subject><rdf:Bag>{bag}</rdf:Bag></dc:subject>\n"
        f"   {desc}\n"
        "  </rdf:Description>\n"
        " </rdf:RDF>\n"
        "</x:xmpmeta>\n"
        '<?xpacket end="w"?>\n'
    )


def is_ours(path: Path) -> bool:
    try:
        return NS_PH in path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False


def write(media_path: Path, **kw) -> bool:  # noqa: ANN003
    sc = sidecar_path(media_path)
    if sc.exists() and not is_ours(sc):
        return False
    text = render(**kw)
    if sc.exists() and sc.read_text(encoding="utf-8") == text:
        return True
    tmp = sc.with_name("." + sc.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, sc)
    return True


def read(sc: Path) -> dict:
    """Parse one of our sidecars (used to rebuild the DB)."""
    ns = {"rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "photohub": NS_PH,
          "dc": "http://purl.org/dc/elements/1.1/"}
    root = ET.fromstring(sc.read_text(encoding="utf-8").split("?>", 1)[1].rsplit("<?xpacket", 1)[0])
    d = root.find(".//rdf:Description", ns)
    out: dict = {"albums": [], "description": None, "content_hash": None}
    if d is None:
        return out
    ch = d.find("photohub:ContentHash", ns)
    out["content_hash"] = ch.text if ch is not None else None
    out["albums"] = [li.text or "" for li in d.findall("photohub:Albums/rdf:Bag/rdf:li", ns)]
    desc = d.find("dc:description/rdf:Alt/rdf:li", ns)
    out["description"] = desc.text if desc is not None else None
    return out
