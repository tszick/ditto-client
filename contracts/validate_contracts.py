#!/usr/bin/env python3
"""
Minimal contract-spec validator for cross-SDK parity suites.

This intentionally validates structure only (not runtime execution).
Adapter runners can consume these JSON files in later phases.
"""

from __future__ import annotations

import json
from pathlib import Path
import sys


REQUIRED_TOP_LEVEL = {"version", "suite", "cases"}
REQUIRED_CASE_FIELDS = {"id", "operation", "inputs", "expect"}


def validate_contract(path: Path) -> list[str]:
    issues: list[str] = []
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # pragma: no cover - CI validation path
        return [f"{path}: invalid JSON ({exc})"]

    missing_top = REQUIRED_TOP_LEVEL - set(payload.keys())
    if missing_top:
        issues.append(f"{path}: missing top-level fields: {sorted(missing_top)}")
        return issues

    if not isinstance(payload["cases"], list) or len(payload["cases"]) == 0:
        issues.append(f"{path}: 'cases' must be a non-empty array")
        return issues

    seen_ids: set[str] = set()
    for idx, case in enumerate(payload["cases"]):
        if not isinstance(case, dict):
            issues.append(f"{path}: case[{idx}] must be an object")
            continue
        missing_case = REQUIRED_CASE_FIELDS - set(case.keys())
        if missing_case:
            issues.append(f"{path}: case[{idx}] missing fields: {sorted(missing_case)}")
            continue
        case_id = str(case["id"])
        if case_id in seen_ids:
            issues.append(f"{path}: duplicate case id '{case_id}'")
        seen_ids.add(case_id)

    return issues


def main() -> int:
    root = Path(__file__).resolve().parent
    files = sorted(root.glob("*.contract.json"))
    if not files:
        print("No contract files found (*.contract.json).")
        return 1

    all_issues: list[str] = []
    for f in files:
        all_issues.extend(validate_contract(f))

    if all_issues:
        print("Contract validation failed:")
        for issue in all_issues:
            print(f"- {issue}")
        return 1

    print(f"Validated {len(files)} contract file(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
