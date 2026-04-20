#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "contracts" / "protocol-contract.snapshot.json"

REQUIRED_ENUMS = {
    "ClientRequest",
    "ClientResponse",
    "ErrorCode",
    "ClusterMessage",
    "GossipMessage",
    "AdminRequest",
    "AdminResponse",
    "NodeStatus",
}
REQUIRED_STRUCTS = {
    "NamespaceQuotaUsage",
    "NamespaceLatencySummary",
    "HotKeyUsage",
    "NodeStats",
}


def main() -> int:
    if not SNAPSHOT.exists():
        print(f"missing snapshot: {SNAPSHOT}")
        return 1

    try:
        payload = json.loads(SNAPSHOT.read_text(encoding="utf-8-sig"))
    except Exception as exc:
        print(f"invalid snapshot json: {exc}")
        return 1

    if not isinstance(payload.get("protocol_version"), int):
        print("snapshot must contain integer protocol_version")
        return 1

    enums = payload.get("enums")
    structs = payload.get("structs")
    if not isinstance(enums, dict) or not isinstance(structs, dict):
        print("snapshot must contain object fields: enums, structs")
        return 1

    missing_enums = sorted(REQUIRED_ENUMS - set(enums.keys()))
    missing_structs = sorted(REQUIRED_STRUCTS - set(structs.keys()))
    if missing_enums:
        print(f"snapshot missing enums: {missing_enums}")
        return 1
    if missing_structs:
        print(f"snapshot missing structs: {missing_structs}")
        return 1

    for name, values in enums.items():
        if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
            print(f"enum '{name}' must be a string array")
            return 1
    for name, fields in structs.items():
        if not isinstance(fields, list) or not all(isinstance(v, str) for v in fields):
            print(f"struct '{name}' must be a string array")
            return 1

    print(
        f"snapshot ok: protocol_version={payload['protocol_version']}, "
        f"error_codes={len(enums.get('ErrorCode', []))}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
