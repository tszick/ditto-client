# Ditto Client Release Guide (Dry-Run First)

This guide describes the release preparation flow for the client SDK repository.

## Goal

- Validate release readiness across Node.js, Python, Java, and Go without publishing.
- Generate a changelog preview and verify packaging steps.

## GitHub Actions dry-run workflow

Workflow: `.github/workflows/release-dry-run.yml`

- Trigger: manual (`workflow_dispatch`).
- Input:
  - `release_version` (for example `0.2.0`).

What it does:
- runs `scripts/release-dry-run.sh <release_version>`,
- generates `release-dry-run-changelog.md`,
- validates packaging/build steps per SDK:
  - Node.js: `npm pack --dry-run`,
  - Python: `python -m build`,
  - Java: `./gradlew clean jar`,
  - Go: `go test ./...`.

## Local dry-run (optional)

From repo root:

```bash
bash scripts/release-dry-run.sh 0.2.0
```

Output:
- current SDK version inventory,
- warning if Node/Python/Java versions are not aligned,
- changelog preview file: `release-dry-run-changelog.md`,
- planned manual bump targets and release tag name.

## Current release tag convention

- Client release tag pattern: `client-v<version>`
- Example: `client-v0.2.0`
