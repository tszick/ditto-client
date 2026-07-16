#!/usr/bin/env python3
"""Validate cross-SDK consistency markers that are easy to drift."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path.cwd()


def fail(message: str) -> None:
    print(f"SDK consistency validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require_contains(path: str, needle: str) -> None:
    if needle not in read(path):
        fail(f"{path} is missing {needle!r}")


def require_regex(path: str, pattern: str) -> None:
    if re.search(pattern, read(path)) is None:
        fail(f"{path} is missing pattern {pattern!r}")


def require_not_contains(path: str, needle: str) -> None:
    if needle in read(path):
        fail(f"{path} must not contain {needle!r}")


def main() -> int:
    policy = json.loads(read("release/sdk-consistency-policy.json"))
    gate = policy["insecure_tls"]["dev_env_gate"]

    if policy["strict_validation"]["current_default"] is not False:
        fail("strict_validation.current_default must remain false until the breaking-release switch")
    if policy["strict_validation"]["production_recommended"] is not True:
        fail("strict_validation.production_recommended must be true")
    if policy["tcp_reconnect"]["default_enabled"] is not False:
        fail("tcp_reconnect.default_enabled must be false")
    if policy["http_retry"]["mutating_retries_default"] is not False:
        fail("http_retry.mutating_retries_default must be false")

    for path, marker in [
        ("ditto-nodejs-client/src/http-client-base.ts", "opts.strictMode ?? false"),
        ("ditto-go-client/http_client.go", "strictMode: opts.StrictMode"),
        ("ditto-python-client/src/ditto_client/http_client_base.py", "strict_mode: bool = False"),
        ("ditto-java-client/src/main/java/io/ditto/client/DittoHttpClientBase.java", "boolean strictMode = false"),
        ("ditto-rust-client/src/http_client.rs", "strict_mode: false"),
    ]:
        require_contains(path, marker)

    for path, marker in [
        ("ditto-python-client/src/ditto_client/tcp_client.py", "auto_reconnect: bool = False"),
        ("ditto-java-client/src/main/java/io/ditto/client/DittoTcpClient.java", "this(host, port, authToken, strictMode, false)"),
        ("ditto-rust-client/src/tcp_client.rs", "auto_reconnect: false"),
    ]:
        require_contains(path, marker)

    require_regex("ditto-nodejs-client/src/tcp-client.ts", r"opts\.autoReconnect\s*\?\?\s*false")
    require_regex("ditto-go-client/tcp_client.go", r"autoReconnect:\s*opts\.AutoReconnect")

    for path in [
        "ditto-go-client/http_client.go",
        "ditto-python-client/src/ditto_client/http_client_base.py",
        "ditto-rust-client/src/http_client.rs",
    ]:
        require_contains(path, gate)

    require_contains("ditto-nodejs-client/src/http-client-base.ts", "retryMethods ?? ['GET', 'DELETE']")
    require_not_contains("ditto-nodejs-client/src/http-client-base.ts", "retryMethods ?? ['GET', 'PUT'")
    require_not_contains("ditto-nodejs-client/src/http-client-base.ts", "retryMethods ?? ['GET', 'POST'")

    print("SDK consistency OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
