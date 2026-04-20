#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT.parent / "ditto-cache" / "ditto-protocol" / "schema" / "protocol-contract.json"
TARGET = ROOT / "contracts" / "protocol-contract.snapshot.json"


def normalize(data: dict) -> str:
    return json.dumps(data, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync protocol contract snapshot from ditto-cache")
    parser.add_argument("--source", default=str(DEFAULT_SOURCE), help="source protocol contract path")
    parser.add_argument("--check", action="store_true", help="check snapshot against source without writing")
    args = parser.parse_args()

    source = Path(args.source)
    if not source.exists():
        print(f"source not found: {source}")
        return 1

    src_data = json.loads(source.read_text(encoding="utf-8-sig"))
    src_norm = normalize(src_data)

    if args.check:
        if not TARGET.exists():
            print(f"snapshot missing: {TARGET}")
            return 1
        cur_data = json.loads(TARGET.read_text(encoding="utf-8-sig"))
        cur_norm = normalize(cur_data)
        if cur_norm != src_norm:
            print("protocol snapshot drift detected; run sync_protocol_snapshot.py")
            return 1
        print("protocol snapshot check OK")
        return 0

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(src_norm, encoding="utf-8")
    print(f"snapshot updated: {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
