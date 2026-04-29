#!/usr/bin/env python3
"""Structural validation for the protobuf-shaped protocol snapshot.

The snapshot is a verbatim copy of the cache-side
``ditto-protocol/schema/protocol-contract.json`` produced from
``ditto-protocol/proto/ditto.proto`` (proto3, package ``ditto.protocol.v1``).
The shape is::

    {
      "protocol_version": <int>,
      "source": <str>,
      "package": <str>,
      "enums":    { "<EnumName>":    ["<VALUE_NAME>", ...], ... },
      "messages": { "<MessageName>": [{"name", "type", "repeated", "tag", "oneof"}, ...], ... }
    }

This validator only checks that the required top-level keys exist and that
the load-bearing enums / messages are present and well-formed. It does
not enforce the value list — the cache-side drift gate is the source of
truth for the actual contract.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "contracts" / "protocol-contract.snapshot.json"

REQUIRED_ENUMS = {
    "NodeStatus",
    "ErrorCode",
}
REQUIRED_MESSAGES = {
    "Envelope",
    "ClientRequest",
    "ClientResponse",
    "ClusterMessage",
    "GossipMessage",
    "AdminRequest",
    "AdminResponse",
    "NodeStats",
    "NamespaceQuotaUsage",
    "NamespaceLatencySummary",
    "HotKeyUsage",
}
REQUIRED_FIELD_KEYS = {"name", "type", "repeated", "tag", "oneof"}


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

    if not isinstance(payload.get("source"), str) or not isinstance(payload.get("package"), str):
        print("snapshot must contain string fields: source, package")
        return 1

    enums = payload.get("enums")
    messages = payload.get("messages")
    if not isinstance(enums, dict) or not isinstance(messages, dict):
        print("snapshot must contain object fields: enums, messages")
        return 1

    missing_enums = sorted(REQUIRED_ENUMS - set(enums.keys()))
    missing_messages = sorted(REQUIRED_MESSAGES - set(messages.keys()))
    if missing_enums:
        print(f"snapshot missing enums: {missing_enums}")
        return 1
    if missing_messages:
        print(f"snapshot missing messages: {missing_messages}")
        return 1

    for name, values in enums.items():
        if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
            print(f"enum '{name}' must be a string array")
            return 1

    for name, fields in messages.items():
        if not isinstance(fields, list):
            print(f"message '{name}' must be an array of field objects")
            return 1
        for idx, field in enumerate(fields):
            if not isinstance(field, dict):
                print(f"message '{name}' field[{idx}] must be an object")
                return 1
            missing = REQUIRED_FIELD_KEYS - set(field.keys())
            if missing:
                print(f"message '{name}' field[{idx}] missing keys: {sorted(missing)}")
                return 1

    print(
        f"snapshot ok: protocol_version={payload['protocol_version']}, "
        f"package={payload['package']}, "
        f"error_codes={len(enums.get('ErrorCode', []))}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
