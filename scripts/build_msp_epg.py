#!/usr/bin/env python3
"""Build Twin Cities (ZIP 54002) EPG JSON from free epg.pw US XMLTV feed.

Downloads (or reuses local) epg_US.xml.gz, streams programmes for mapped
OTA channels, and writes epg/msp-epg.json for the Fire TV Channel Guide app.
"""

from __future__ import annotations

import argparse
import gzip
import html
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Optional, TextIO
from xml.etree.ElementTree import iterparse

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = REPO_ROOT / "epg" / "msp-epg.json"
EPG_URL = "https://epg.pw/xmltv/epg_US.xml.gz"
LOCAL_XML = Path("/tmp/epg_us.xml")
LOCAL_GZ = Path("/tmp/epg_us.xml.gz")

HALF_HOUR_MS = 30 * 60 * 1000
DAY_MS = 24 * 60 * 60 * 1000
GUIDE_DAYS = 14

# epg.pw channel id → our Channel id
EPG_ID_TO_OURS: Dict[str, str] = {
    "465936": "ch-21",  # 2.1 KTCA / TPT2
    "469181": "ch-41",  # 4.1 WCCO
    "469016": "ch-51",  # 5.1 KSTP
    "466500": "ch-52",  # 5.2 KSTC / 45TV
    "468858": "ch-91",  # 9.1 KMSP
    "468898": "ch-92",  # 9.2 WFTC
    "468830": "ch-111",  # 11.1 KARE
    "466053": "ch-231",  # 23.1 WUCW
    "469106": "ch-411",  # 41.1 KPXM
}

# Full Twin Cities lineup matching SampleEpgData (include subs with no programmes)
CHANNELS: List[dict] = [
    {
        "id": "ch-21",
        "number": "2.1",
        "callSign": "TPT2",
        "name": "TPT 2",
        "network": "PBS",
        "favorite": True,
        "categories": ["Kids", "TV Shows", "Movies"],
    },
    {
        "id": "ch-22",
        "number": "2.2",
        "callSign": "TPTMN",
        "name": "Minnesota Channel",
        "network": "PBS",
        "favorite": True,
        "categories": ["TV Shows"],
    },
    {
        "id": "ch-23",
        "number": "2.3",
        "callSign": "TPTLIFE",
        "name": "TPT Life",
        "network": "PBS",
        "favorite": False,
        "categories": ["TV Shows"],
    },
    {
        "id": "ch-24",
        "number": "2.4",
        "callSign": "TPTKIDS",
        "name": "PBS Kids",
        "network": "PBS Kids",
        "favorite": False,
        "categories": ["Kids"],
    },
    {
        "id": "ch-25",
        "number": "2.5",
        "callSign": "TPTNOW",
        "name": "TPT Now / Weather",
        "network": "Weather",
        "favorite": False,
        "categories": ["News"],
    },
    {
        "id": "ch-41",
        "number": "4.1",
        "callSign": "WCCO",
        "name": "WCCO 4",
        "network": "CBS",
        "favorite": True,
        "categories": ["News", "Sports", "TV Shows"],
    },
    {
        "id": "ch-42",
        "number": "4.2",
        "callSign": "START",
        "name": "Start TV",
        "network": "Start TV",
        "favorite": False,
        "categories": ["TV Shows", "Movies"],
    },
    {
        "id": "ch-43",
        "number": "4.3",
        "callSign": "DABL",
        "name": "Dabl",
        "network": "Dabl",
        "favorite": False,
        "categories": ["TV Shows"],
    },
    {
        "id": "ch-44",
        "number": "4.4",
        "callSign": "FAVE",
        "name": "Fave TV",
        "network": "Fave TV",
        "favorite": False,
        "categories": ["TV Shows"],
    },
    {
        "id": "ch-51",
        "number": "5.1",
        "callSign": "KSTP",
        "name": "KSTP 5",
        "network": "ABC",
        "favorite": True,
        "categories": ["News", "TV Shows"],
    },
    {
        "id": "ch-52",
        "number": "5.2",
        "callSign": "45TV",
        "name": "45TV",
        "network": "Independent",
        "favorite": False,
        "categories": ["TV Shows", "Movies"],
    },
    {
        "id": "ch-53",
        "number": "5.3",
        "callSign": "METV",
        "name": "MeTV",
        "network": "MeTV",
        "favorite": False,
        "categories": ["TV Shows", "Movies"],
    },
    {
        "id": "ch-54",
        "number": "5.4",
        "callSign": "GETTV",
        "name": "getTV",
        "network": "getTV",
        "favorite": False,
        "categories": ["TV Shows", "Movies"],
    },
    {
        "id": "ch-55",
        "number": "5.5",
        "callSign": "DEFY",
        "name": "Defy TV",
        "network": "Defy",
        "favorite": False,
        "categories": ["TV Shows"],
    },
    {
        "id": "ch-57",
        "number": "5.7",
        "callSign": "HI",
        "name": "Heroes & Icons",
        "network": "H&I",
        "favorite": False,
        "categories": ["TV Shows", "Movies"],
    },
    {
        "id": "ch-91",
        "number": "9.1",
        "callSign": "KMSP",
        "name": "FOX 9",
        "network": "FOX",
        "favorite": True,
        "categories": ["News", "Sports", "TV Shows"],
    },
    {
        "id": "ch-92",
        "number": "9.2",
        "callSign": "WFTC",
        "name": "FOX 9+",
        "network": "MyNetwork",
        "favorite": False,
        "categories": ["TV Shows", "Sports"],
    },
    {
        "id": "ch-111",
        "number": "11.1",
        "callSign": "KARE",
        "name": "KARE 11",
        "network": "NBC",
        "favorite": True,
        "categories": ["News", "Sports", "TV Shows"],
    },
    {
        "id": "ch-112",
        "number": "11.2",
        "callSign": "COURT",
        "name": "Court TV",
        "network": "Court TV",
        "favorite": False,
        "categories": ["TV Shows"],
    },
    {
        "id": "ch-113",
        "number": "11.3",
        "callSign": "TRUE",
        "name": "True Crime Network",
        "network": "True Crime",
        "favorite": False,
        "categories": ["TV Shows"],
    },
    {
        "id": "ch-231",
        "number": "23.1",
        "callSign": "WUCW",
        "name": "The CW Twin Cities",
        "network": "CW",
        "favorite": True,
        "categories": ["TV Shows", "Sports"],
    },
    {
        "id": "ch-232",
        "number": "23.2",
        "callSign": "COMET",
        "name": "Comet",
        "network": "Comet",
        "favorite": False,
        "categories": ["Movies", "TV Shows"],
    },
    {
        "id": "ch-411",
        "number": "41.1",
        "callSign": "KPXM",
        "name": "ION",
        "network": "ION",
        "favorite": False,
        "categories": ["TV Shows", "Movies"],
    },
    {
        "id": "ch-412",
        "number": "41.2",
        "callSign": "BOUNCE",
        "name": "Bounce",
        "network": "Bounce",
        "favorite": False,
        "categories": ["TV Shows", "Movies"],
    },
]


def align_down_half_hour(epoch_ms: int) -> int:
    return epoch_ms - (epoch_ms % HALF_HOUR_MS)


def parse_xmltv_time(value: str) -> Optional[int]:
    """Parse XMLTV time like '20260906120000 +0000' to epoch milliseconds."""
    if not value:
        return None
    value = value.strip()
    # Prefer timezone-aware parse
    for fmt in ("%Y%m%d%H%M%S %z", "%Y%m%d%H%M%S"):
        try:
            dt = datetime.strptime(value, fmt)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return int(dt.timestamp() * 1000)
        except ValueError:
            continue
    return None


def unescape_text(text: Optional[str]) -> str:
    if not text:
        return ""
    return html.unescape(text).strip()


def first_text(elem, tag: str) -> str:
    node = elem.find(tag)
    if node is None or node.text is None:
        return ""
    return unescape_text(node.text)


def category_from_programme(elem) -> str:
    cats = [unescape_text(c.text) for c in elem.findall("category") if c.text]
    if not cats:
        return "TV Shows"
    joined = " ".join(cats).lower()
    if any(k in joined for k in ("news", "weather")):
        return "News"
    if any(k in joined for k in ("sport", "football", "baseball", "basketball", "soccer", "hockey")):
        return "Sports"
    if any(k in joined for k in ("movie", "film", "cinema")):
        return "Movies"
    if any(k in joined for k in ("kids", "children", "cartoon", "animation")):
        return "Kids"
    return cats[0] if cats else "TV Shows"


def rating_from_programme(elem) -> str:
    for rating in elem.findall("rating"):
        value = rating.findtext("value")
        if value:
            return unescape_text(value)
    return "TV-PG"


def ensure_source(force_download: bool = False) -> Path:
    """Return path to decompressed XML, downloading gz if needed."""
    if not force_download and LOCAL_XML.exists() and LOCAL_XML.stat().st_size > 1_000_000:
        print(f"Using existing {LOCAL_XML} ({LOCAL_XML.stat().st_size} bytes)", flush=True)
        return LOCAL_XML

    gz_path = LOCAL_GZ
    if force_download or not gz_path.exists() or gz_path.stat().st_size < 1_000_000:
        print(f"Downloading {EPG_URL} → {gz_path}", flush=True)
        urllib.request.urlretrieve(EPG_URL, gz_path)
        print(f"Downloaded {gz_path.stat().st_size} bytes", flush=True)
    else:
        print(f"Using existing {gz_path}", flush=True)

    print(f"Decompressing → {LOCAL_XML}", flush=True)
    with gzip.open(gz_path, "rb") as src, open(LOCAL_XML, "wb") as dst:
        while True:
            chunk = src.read(1024 * 1024)
            if not chunk:
                break
            dst.write(chunk)
    print(f"Decompressed {LOCAL_XML.stat().st_size} bytes", flush=True)
    return LOCAL_XML


def open_xml(path: Path) -> TextIO:
    if str(path).endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return open(path, "r", encoding="utf-8", errors="replace")


def parse_programmes(xml_path: Path, window_start_ms: int, window_end_ms: int) -> List[dict]:
    wanted = set(EPG_ID_TO_OURS.keys())
    programs: List[dict] = []
    seq = 0
    matched = 0
    kept = 0

    print(f"Streaming parse {xml_path} for {len(wanted)} channels…", flush=True)
    with open_xml(xml_path) as fh:
        for event, elem in iterparse(fh, events=("end",)):
            if elem.tag != "programme":
                if elem.tag == "channel":
                    elem.clear()
                continue

            epg_ch = elem.get("channel")
            if epg_ch not in wanted:
                elem.clear()
                continue

            matched += 1
            start_ms = parse_xmltv_time(elem.get("start") or "")
            end_ms = parse_xmltv_time(elem.get("stop") or "")
            if start_ms is None or end_ms is None or end_ms <= start_ms:
                elem.clear()
                continue

            # Keep programmes that overlap the guide window (no past-only slots)
            if end_ms <= window_start_ms or start_ms >= window_end_ms:
                elem.clear()
                continue

            our_id = EPG_ID_TO_OURS[epg_ch]
            title = first_text(elem, "title") or "Unknown"
            desc = first_text(elem, "desc")
            seq += 1
            kept += 1
            programs.append(
                {
                    "id": f"epg-{seq}-{our_id}",
                    "channelId": our_id,
                    "title": title,
                    "description": desc,
                    "startEpochMs": start_ms,
                    "endEpochMs": end_ms,
                    "rating": rating_from_programme(elem),
                    "hd": True,
                    "category": category_from_programme(elem),
                }
            )
            elem.clear()

    programs.sort(key=lambda p: (p["channelId"], p["startEpochMs"]))
    print(f"Matched {matched} programmes for mapped channels; kept {kept} in window", flush=True)
    return programs


def build(force_download: bool = False, out_path: Path = DEFAULT_OUT) -> Path:
    now_ms = int(time.time() * 1000)
    window_start = align_down_half_hour(now_ms)
    window_end = window_start + GUIDE_DAYS * DAY_MS

    xml_path = ensure_source(force_download=force_download)
    programs = parse_programmes(xml_path, window_start, window_end)

    payload = {
        "generatedAtMs": now_ms,
        "source": EPG_URL,
        "zip": "54002",
        "channels": CHANNELS,
        "programs": programs,
    }

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))

    by_ch: Dict[str, int] = {}
    for p in programs:
        by_ch[p["channelId"]] = by_ch.get(p["channelId"], 0) + 1
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes)", flush=True)
    print(f"Programs per channel: {json.dumps(by_ch, sort_keys=True)}", flush=True)
    print(
        f"Window {datetime.fromtimestamp(window_start/1000, tz=timezone.utc).isoformat()} → "
        f"{datetime.fromtimestamp(window_end/1000, tz=timezone.utc).isoformat()} UTC",
        flush=True,
    )
    return out_path


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--force-download",
        action="store_true",
        help="Re-download epg_US.xml.gz even if /tmp cache exists",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=DEFAULT_OUT,
        help=f"Output JSON path (default: {DEFAULT_OUT})",
    )
    args = parser.parse_args(list(argv) if argv is not None else None)
    build(force_download=args.force_download, out_path=args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
