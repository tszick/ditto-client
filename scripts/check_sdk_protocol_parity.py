#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "contracts" / "protocol-contract.snapshot.json"

NODE_TYPES = ROOT / "ditto-nodejs-client" / "src" / "types.ts"
GO_ERRORS = ROOT / "ditto-go-client" / "errors.go"
PY_TYPES = ROOT / "ditto-python-client" / "src" / "ditto_client" / "types.py"
JAVA_ERRORS = ROOT / "ditto-java-client" / "src" / "main" / "java" / "io" / "ditto" / "client" / "DittoErrorCode.java"


def to_upper_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()


def parse_node_codes(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(re.findall(r"\|\s*'([A-Za-z0-9_]+)'", text))


def parse_go_codes(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(re.findall(r'=\s*"([A-Za-z0-9_]+)"', text))


def parse_python_codes(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(re.findall(r'=\s*"([A-Za-z0-9_]+)"', text))


def parse_java_constants(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    body_match = re.search(r"enum\s+DittoErrorCode\s*\{(?P<body>.*?)\;", text, re.S)
    if not body_match:
        return set()
    body = body_match.group("body")
    return {token.strip() for token in body.split(",") if token.strip()}


def main() -> int:
    payload = json.loads(SNAPSHOT.read_text(encoding="utf-8-sig"))
    required = set(payload["enums"]["ErrorCode"])

    node = parse_node_codes(NODE_TYPES)
    go = parse_go_codes(GO_ERRORS)
    py = parse_python_codes(PY_TYPES)
    java = parse_java_constants(JAVA_ERRORS)

    issues: list[str] = []
    for code in sorted(required):
        if code not in node:
            issues.append(f"node missing error code: {code}")
        if code not in go:
            issues.append(f"go missing error code: {code}")
        if code not in py:
            issues.append(f"python missing error code: {code}")
        if to_upper_snake(code) not in java:
            issues.append(f"java missing error code constant: {to_upper_snake(code)}")

    if issues:
        print("sdk error-code parity check failed:")
        for issue in issues:
            print(f"- {issue}")
        return 1

    print(f"sdk error-code parity ok for {len(required)} server codes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
