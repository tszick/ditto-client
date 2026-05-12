#!/usr/bin/env python3
"""Validate documented client transport security policy."""

from __future__ import annotations

import json
import sys
from pathlib import Path


REQUIRED_INSECURE_TLS_POLICY = {
    "nodejs": "rejected",
    "java": "rejected",
    "go": "env-gated-dev-only",
    "python": "env-gated-dev-only",
    "rust": "env-gated-dev-only",
}


def fail(message: str) -> None:
    print(f"client security policy validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def require_contains(path: Path, needle: str) -> None:
    if needle not in read(path):
        fail(f"{path} is missing required policy marker: {needle!r}")


def main() -> int:
    root = Path.cwd()
    manifest = json.loads(read(root / "release-manifest.json"))
    policy = manifest.get("security_policy") or {}
    production = policy.get("production") or {}
    insecure_tls = policy.get("insecure_tls") or {}

    if production.get("strict_mode_recommended") is not True:
        fail("production.strict_mode_recommended must be true")
    if production.get("http_tls_verification_required") is not True:
        fail("production.http_tls_verification_required must be true")
    if production.get("tcp_plaintext_remote_policy") != "loopback-private-or-encrypted-underlay-only":
        fail("unexpected production TCP plaintext policy")

    for sdk, expected in REQUIRED_INSECURE_TLS_POLICY.items():
        actual = insecure_tls.get(sdk)
        if actual != expected:
            fail(f"insecure_tls.{sdk} expected {expected!r}, got {actual!r}")

    require_contains(
        root / "ditto-nodejs-client/src/http-client-base.ts",
        "devInsecureTls(true) is insecure and is no longer supported",
    )
    require_contains(
        root / "ditto-java-client/src/main/java/io/ditto/client/DittoHttpClientBase.java",
        "devInsecureTls(true) is insecure and is no longer supported",
    )
    require_contains(
        root / "ditto-go-client/http_client.go",
        "DITTO_CLIENT_ALLOW_INSECURE_TLS_DEV",
    )
    require_contains(
        root / "ditto-python-client/src/ditto_client/http_client_base.py",
        "DITTO_CLIENT_ALLOW_INSECURE_TLS_DEV",
    )
    require_contains(
        root / "ditto-rust-client/src/http_client.rs",
        "DITTO_CLIENT_ALLOW_INSECURE_TLS_DEV",
    )
    require_contains(
        root / "README.md",
        "Production clients should use HTTPS with certificate verification enabled",
    )
    require_contains(
        root / "README.md",
        "Raw TCP does not encrypt tokens or cache payloads",
    )

    print("client security policy OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
