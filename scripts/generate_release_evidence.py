#!/usr/bin/env python3
"""Generate checksum, provenance, and minimal SBOM evidence for client artifacts."""

from __future__ import annotations

import argparse
import datetime as dt
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path


def fail(message: str) -> None:
    print(f"release evidence generation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_value(args: list[str]) -> str | None:
    try:
        return subprocess.check_output(["git", *args], text=True, stderr=subprocess.DEVNULL).strip()
    except Exception:
        return None


def collect_artifacts(root: Path, patterns: list[str]) -> list[Path]:
    found: list[Path] = []
    for pattern in patterns:
        found.extend(root.glob(pattern))
    files = sorted(path for path in found if path.is_file())
    if not files:
        fail(f"no artifact files matched: {', '.join(patterns)}")
    return files


def node_packages(root: Path) -> list[dict]:
    pkg = read_json(root / "ditto-nodejs-client/package.json")
    packages = [package_entry(pkg["name"], pkg["version"], "npm", root / "ditto-nodejs-client/package.json")]
    for section in ("dependencies", "devDependencies", "optionalDependencies", "peerDependencies"):
        for name, version in sorted((pkg.get(section) or {}).items()):
            packages.append(package_entry(name, version, "npm", root / "ditto-nodejs-client/package.json", section))
    return packages


def python_packages(root: Path) -> list[dict]:
    pyproject = read_text(root / "ditto-python-client/pyproject.toml")
    name = regex(pyproject, r'^name\s*=\s*"([^"]+)"', "Python package name")
    version = regex(pyproject, r'^version\s*=\s*"([^"]+)"', "Python package version")
    packages = [package_entry(name, version, "pypi", root / "ditto-python-client/pyproject.toml")]
    deps_match = re.search(r"^dependencies\s*=\s*\[(.*?)\]", pyproject, re.MULTILINE | re.DOTALL)
    if deps_match:
        for dep in re.findall(r'"([^"]+)"', deps_match.group(1)):
            packages.append(package_entry(dep, "declared", "pypi", root / "ditto-python-client/pyproject.toml", "dependencies"))
    return packages


def java_packages(root: Path) -> list[dict]:
    build = read_text(root / "ditto-java-client/build.gradle.kts")
    group = regex(build, r'^group\s*=\s*"([^"]+)"', "Java group")
    version = regex(build, r'^version\s*=\s*"([^"]+)"', "Java version")
    packages = [package_entry(f"{group}:ditto-java-client", version, "maven", root / "ditto-java-client/build.gradle.kts")]
    for notation in re.findall(r'(?:implementation|testImplementation|testRuntimeOnly)\("([^"]+)"\)', build):
        packages.append(package_entry(notation, "declared", "maven", root / "ditto-java-client/build.gradle.kts", "gradle"))
    return packages


def rust_packages(root: Path) -> list[dict]:
    cargo = read_text(root / "ditto-rust-client/Cargo.toml")
    name = regex(cargo, r'^name\s*=\s*"([^"]+)"', "Rust package name")
    version = regex(cargo, r'^version\s*=\s*"([^"]+)"', "Rust package version")
    packages = [package_entry(name, version, "cargo", root / "ditto-rust-client/Cargo.toml")]
    lock_path = root / "ditto-rust-client/Cargo.lock"
    if lock_path.exists():
        lock = read_text(lock_path)
        for block in lock.split("[[package]]"):
            dep_name = re.search(r'\nname\s*=\s*"([^"]+)"', block)
            dep_version = re.search(r'\nversion\s*=\s*"([^"]+)"', block)
            if dep_name and dep_version and dep_name.group(1) != name:
                packages.append(package_entry(dep_name.group(1), dep_version.group(1), "cargo", lock_path, "Cargo.lock"))
    return packages


def go_packages(root: Path) -> list[dict]:
    gomod_path = root / "ditto-go-client/go.mod"
    gomod = read_text(gomod_path)
    module = regex(gomod, r"^module\s+(\S+)", "Go module")
    goversion = regex(gomod, r"^go\s+(\S+)", "Go version")
    packages = [package_entry(module, f"go {goversion}", "go", gomod_path)]
    for module_name, version in re.findall(r"^\s*([A-Za-z0-9_./:-]+)\s+(v\S+)", gomod, re.MULTILINE):
        packages.append(package_entry(module_name, version, "go", gomod_path, "require"))
    return packages


def regex(text: str, pattern: str, label: str) -> str:
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        fail(f"could not parse {label}")
    return match.group(1)


def package_entry(name: str, version: str, ecosystem: str, source: Path, relationship: str = "root") -> dict:
    return {
        "name": name,
        "version": version,
        "ecosystem": ecosystem,
        "relationship": relationship,
        "source": str(source.as_posix()),
    }


def sbom_packages(root: Path, sdk: str) -> list[dict]:
    match sdk:
        case "nodejs":
            return node_packages(root)
        case "python":
            return python_packages(root)
        case "java":
            return java_packages(root)
        case "rust":
            return rust_packages(root)
        case "go":
            return go_packages(root)
        case _:
            fail(f"unsupported sdk: {sdk}")


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def spdx_id(value: str) -> str:
    clean = re.sub(r"[^A-Za-z0-9.-]", "-", value)
    return f"SPDXRef-Package-{clean.strip('-') or 'unknown'}"


def package_to_spdx(pkg: dict) -> dict:
    return {
        "SPDXID": spdx_id(f"{pkg['ecosystem']}-{pkg['name']}"),
        "name": pkg["name"],
        "versionInfo": pkg["version"],
        "downloadLocation": "NOASSERTION",
        "filesAnalyzed": False,
        "supplier": "NOASSERTION",
        "comment": f"ecosystem={pkg['ecosystem']}; relationship={pkg['relationship']}; source={pkg['source']}",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", required=True, choices=["nodejs", "python", "java", "rust", "go"])
    parser.add_argument("--artifact", action="append", default=[], help="Artifact glob relative to repo root")
    parser.add_argument("--output-dir", default=None)
    args = parser.parse_args()

    root = Path.cwd()
    manifest = read_json(root / "release-manifest.json")
    out_dir = Path(args.output_dir) if args.output_dir else root / "release-evidence" / args.sdk
    out_dir.mkdir(parents=True, exist_ok=True)

    artifacts = collect_artifacts(root, args.artifact) if args.artifact else []
    artifact_rows = []
    for path in artifacts:
        rel = path.relative_to(root).as_posix()
        artifact_rows.append(
            {
                "path": rel,
                "size_bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )

    (out_dir / "checksums.sha256").write_text(
        "".join(f"{row['sha256']}  {row['path']}\n" for row in artifact_rows),
        encoding="utf-8",
    )

    generated_at = dt.datetime.now(dt.UTC).replace(microsecond=0).isoformat()
    commit = os.environ.get("GITHUB_SHA") or git_value(["rev-parse", "HEAD"])
    branch = os.environ.get("GITHUB_REF_NAME") or git_value(["branch", "--show-current"])
    provenance = {
        "sdk": args.sdk,
        "release_version": manifest["release_version"],
        "tag": manifest["tag"],
        "generated_at": generated_at,
        "source": {
            "commit": commit,
            "branch": branch,
            "repository": os.environ.get("GITHUB_REPOSITORY"),
            "workflow": os.environ.get("GITHUB_WORKFLOW"),
            "run_id": os.environ.get("GITHUB_RUN_ID"),
        },
        "manifest": {
            "path": "release-manifest.json",
            "sha256": sha256_file(root / "release-manifest.json"),
        },
        "protocol_snapshot": manifest["protocol_snapshot"],
        "artifacts": artifact_rows,
    }
    write_json(out_dir / "provenance.json", provenance)

    packages = sbom_packages(root, args.sdk)
    sbom = {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": f"SPDXRef-DOCUMENT-ditto-client-{args.sdk}",
        "name": f"ditto-client-{args.sdk}-{manifest['release_version']}",
        "documentNamespace": (
            f"https://github.com/tszick/ditto-client/spdx/"
            f"{args.sdk}/{manifest['release_version']}/{hashlib.sha256((args.sdk + generated_at).encode()).hexdigest()[:16]}"
        ),
        "creationInfo": {
            "created": generated_at,
            "creators": ["Tool: ditto-client/scripts/generate_release_evidence.py"],
        },
        "packages": [package_to_spdx(pkg) for pkg in packages],
    }
    write_json(out_dir / "sbom.json", sbom)

    print(f"release evidence generated: {out_dir.as_posix()}")
    for row in artifact_rows:
        print(f"  {row['sha256']}  {row['path']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
