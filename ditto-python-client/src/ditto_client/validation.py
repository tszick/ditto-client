from __future__ import annotations

import re

STRICT_TOKEN_RE = re.compile(r"^[A-Za-z0-9._:-]+$")


def validate_core_inputs(strict_mode: bool, op: str, key: str, namespace: str | None) -> None:
    if not strict_mode:
        return
    if key is None or key.strip() == "":
        raise ValueError(f"Invalid {op} request: key must not be empty.")
    if STRICT_TOKEN_RE.fullmatch(key) is None:
        raise ValueError(
            f"Invalid {op} request: key contains unsupported characters. Allowed: [A-Za-z0-9._:-]"
        )
    if namespace is None:
        return
    ns = namespace.strip()
    if ns == "":
        raise ValueError(f"Invalid {op} request: namespace must not be blank when provided.")
    if "::" in ns:
        raise ValueError(f"Invalid {op} request: namespace must not contain '::'.")
    if STRICT_TOKEN_RE.fullmatch(ns) is None:
        raise ValueError(
            f"Invalid {op} request: namespace contains unsupported characters. Allowed: [A-Za-z0-9._:-]"
        )
