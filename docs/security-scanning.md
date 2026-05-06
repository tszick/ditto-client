# Security scanning

Ditto client uses repository-level scanners only. Container image scanning is
intentionally out of scope because the Ditto runtime is consumed inside Tinyme,
where the runtime environment is already scanned.

## GitHub Actions

`.github/workflows/security-scan.yml` runs:

- Gitleaks secret scanning.
- Trivy filesystem scanning for HIGH and CRITICAL vulnerabilities and
  misconfigurations.
- Node.js `npm audit`.
- Go `govulncheck`.
- Rust `cargo audit`.
- Python `pip-audit`.
- Nuclei DAST only when manually started with an explicitly authorized
  `dast_target_url`.

Java dependency manifests are covered by the repo-level Trivy filesystem scan.
If Java grows more external dependencies, add a dedicated Gradle dependency
audit plugin before making Java security gating stricter.

The Nuclei job disables interactsh and excludes intrusive/fuzz templates.

## Local reproduction

```bash
docker run --rm -v "$(pwd):/repo" zricethezav/gitleaks:latest \
  detect --source=/repo --no-banner --redact

docker run --rm -v "$(pwd):/repo" aquasec/trivy:latest \
  fs /repo --scanners vuln,misconfig --severity HIGH,CRITICAL \
  --ignore-unfixed

cd ditto-nodejs-client && npm audit --audit-level=high
cd ../ditto-go-client && govulncheck ./...
cd ../ditto-rust-client && cargo audit
cd ../ditto-python-client && pip-audit .
```

DAST must only be run against an authorized local or dev Ditto endpoint:

```bash
docker run --rm --network host projectdiscovery/nuclei:latest \
  -target http://127.0.0.1:7781/ \
  -severity medium,high,critical \
  -tags cve,exposure,misconfig,xss,sqli,ssrf,redirect \
  -no-interactsh -exclude-tags fuzz,intrusive \
  -rate-limit 50 -timeout 10
```
