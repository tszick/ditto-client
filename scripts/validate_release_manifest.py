#!/usr/bin/env python3
"""Validate Ditto client release manifest against SDK source files."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


SEMVER_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?$")


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def fail(message: str) -> None:
    print(f"release manifest validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_regex(path: Path, pattern: str, label: str) -> str:
    match = re.search(pattern, read_text(path), re.MULTILINE)
    if not match:
        fail(f"could not parse {label} from {path}")
    return match.group(1)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_release_version(manifest: dict, expected_version: str | None) -> str:
    release_version = str(manifest.get("release_version", "")).strip()
    if not SEMVER_RE.match(release_version):
        fail(f"release_version is not semver-like: {release_version!r}")
    expected_tag = f"client-v{release_version}"
    if manifest.get("tag") != expected_tag:
        fail(f"tag must be {expected_tag!r}, got {manifest.get('tag')!r}")
    if expected_version and release_version != expected_version:
        fail(f"manifest release_version {release_version!r} does not match expected {expected_version!r}")
    return release_version


def validate_protocol_snapshot(root: Path, manifest: dict) -> None:
    proto = manifest.get("protocol_snapshot") or {}
    rel_path = proto.get("path")
    expected_hash = str(proto.get("sha256", "")).lower()
    if not rel_path or not expected_hash:
        fail("protocol_snapshot.path and protocol_snapshot.sha256 are required")
    actual_hash = sha256_file(root / rel_path)
    if actual_hash != expected_hash:
        fail(
            "protocol snapshot hash mismatch: "
            f"{rel_path} expected {expected_hash}, got {actual_hash}"
        )


def validate_sdk_versions(root: Path, manifest: dict, release_version: str) -> None:
    sdks = manifest.get("sdks") or {}
    required = {"nodejs", "python", "java", "go", "rust"}
    missing = sorted(required - set(sdks))
    if missing:
        fail(f"missing SDK manifest entries: {', '.join(missing)}")

    node_path = root / sdks["nodejs"]["package_file"]
    node_pkg = load_json(node_path)
    if node_pkg.get("name") != sdks["nodejs"].get("package_name"):
        fail(f"Node package name mismatch in {node_path}")
    if node_pkg.get("version") != release_version:
        fail(f"Node version mismatch: expected {release_version}, got {node_pkg.get('version')}")

    python_path = root / sdks["python"]["package_file"]
    python_name = parse_regex(python_path, r'^name\s*=\s*"([^"]+)"', "Python package name")
    python_version = parse_regex(python_path, r'^version\s*=\s*"([^"]+)"', "Python version")
    if python_name != sdks["python"].get("package_name"):
        fail(f"Python package name mismatch in {python_path}")
    if python_version != release_version:
        fail(f"Python version mismatch: expected {release_version}, got {python_version}")

    java_path = root / sdks["java"]["package_file"]
    java_group = parse_regex(java_path, r'^group\s*=\s*"([^"]+)"', "Java group")
    java_version = parse_regex(java_path, r'^version\s*=\s*"([^"]+)"', "Java version")
    java_package = f"{java_group}:ditto-java-client"
    if java_package != sdks["java"].get("package_name"):
        fail(f"Java package name mismatch: expected {sdks['java'].get('package_name')}, got {java_package}")
    if java_version != release_version:
        fail(f"Java version mismatch: expected {release_version}, got {java_version}")

    rust_path = root / sdks["rust"]["package_file"]
    rust_name = parse_regex(rust_path, r'^name\s*=\s*"([^"]+)"', "Rust package name")
    rust_version = parse_regex(rust_path, r'^version\s*=\s*"([^"]+)"', "Rust version")
    if rust_name != sdks["rust"].get("package_name"):
        fail(f"Rust package name mismatch in {rust_path}")
    if rust_version != release_version:
        fail(f"Rust version mismatch: expected {release_version}, got {rust_version}")

    go_path = root / sdks["go"]["package_file"]
    go_module = parse_regex(go_path, r"^module\s+(\S+)", "Go module")
    if go_module != sdks["go"].get("module"):
        fail(f"Go module mismatch: expected {sdks['go'].get('module')}, got {go_module}")
    expected_go_tag = f"ditto-go-client/v{release_version}"
    if sdks["go"].get("tag") != expected_go_tag:
        fail(f"Go tag must be {expected_go_tag}")

    for sdk, entry in sdks.items():
        version = entry.get("version")
        if version is not None and version != release_version:
            fail(f"{sdk} manifest version mismatch: expected {release_version}, got {version}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="release-manifest.json")
    parser.add_argument("--expected-version")
    args = parser.parse_args()

    root = Path.cwd()
    manifest_path = root / args.manifest
    manifest = load_json(manifest_path)
    release_version = validate_release_version(manifest, args.expected_version)
    validate_protocol_snapshot(root, manifest)
    validate_sdk_versions(root, manifest, release_version)

    print(f"release manifest OK: {release_version}")
    print(f"protocol snapshot OK: {manifest['protocol_snapshot']['sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
