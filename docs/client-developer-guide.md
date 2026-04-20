# Ditto Client Developer Guide

This guide documents the current state of Ditto client SDKs in this repository.

## Repositories and layout

- Node.js client: `ditto-nodejs-client`
- Java client: `ditto-java-client`
- Python client: `ditto-python-client`
- Go client: `ditto-go-client`

Shared behavior target:
- same core cache operations across SDKs,
- TCP binary and HTTP REST client support,
- pattern-based bulk operations on both protocols.

## Protocols

- TCP binary (`dittod` port 7777)
- HTTP/HTTPS REST (`dittod` port 7778)

## Feature matrix (current)

| Feature | Node.js | Java | Python | Go |
|---|---|---|---|---|
| TCP client | yes | yes | yes | yes |
| HTTP client | yes | yes | yes | yes |
| `ping/get/set/delete` | yes | yes | yes | yes |
| `deleteByPattern` / `delete_by_pattern` | yes | yes | yes | yes |
| `setTtlByPattern` / `set_ttl_by_pattern` | yes | yes | yes | yes |
| Namespace-aware operations | yes | yes | yes | yes |
| Strict mode (`key`/`namespace` validation) | yes | yes | yes | yes |
| Key watch/unwatch (TCP) | yes | yes | yes | yes |
| Auto reconnect (TCP) | yes | yes | yes | yes |

## API semantics

### Core operations

- `get(key)` returns value + version, or not-found (`null`/`None`).
- `set(key, value, ttl?)` writes key and returns version.
- `delete(key)` returns whether key existed.
- `ping()` checks liveness.

### TTL semantics

- Positive TTL: expires after given seconds.
- TTL omitted: no TTL change for existing key in set context.
- Pattern TTL APIs:
  - TTL `<= 0` or omitted means remove TTL from matched keys.

### Pattern operations

- Delete all keys matching glob-style pattern (`*` wildcard).
- Update TTL for all matched keys.

### Namespace semantics

- All SDKs support namespace-scoped cache operations.
- HTTP clients send namespace with `X-Ditto-Namespace` header.
- TCP clients encode namespace via protocol `Option<String>` field on request variants.
- Omitted/empty namespace falls back to server-side default namespace behavior.

### Strict mode semantics

- Each SDK has an opt-in strict mode for `get/set/delete`, pattern operations, and TCP watch key inputs.
- When enabled:
  - `key` must be non-empty and match `[A-Za-z0-9._:-]+`,
  - `pattern` must be non-empty and match `[A-Za-z0-9._:-*]+`,
  - `namespace` (if provided) must be non-empty, must not contain `::`, and must match `[A-Za-z0-9._:-]+`.
- Strict mode validation happens client-side before network I/O.

### Error code semantics

- HTTP SDKs prefer the server payload `error` code over coarse HTTP-status fallback mapping.
- Unknown/new server error codes are preserved as raw string values for forward compatibility.
- Known runtime codes still map to SDK-native enum/typed constants where applicable.

Examples of patterns:
- `user:*`
- `session:*:access`
- `tenant:42:*`

## Node.js notes

### TCP client extras

`DittoTcpClient` supports:
- optional `authToken`,
- optional `autoReconnect`,
- `watch(key, callback)` / `unwatch(key)`.

Watch callback receives `(value, version)` where `value=null` means key deleted.

For long-lived idle watch connections:
- if client-side socket inactivity timeout is enabled, it may close idle socket,
- use `requestTimeoutMs: 0` for persistent watch-only streams.

### HTTP client TLS options

- `tls: true` for HTTPS.
- secure default: certificate validation stays enabled.
- dev-only insecure bypass is explicit via `devInsecureTls: true` (or legacy `rejectUnauthorized: false`).

## Java notes

- `DittoTcpClient` and `DittoHttpClient` expose matching core operations.
- Pattern operations are available on both clients.
- TCP watch APIs are available: `watch(key)`, `waitForWatchEvent()`, `unwatch(key)`.
- TCP optional reconnect retry is available via `new DittoTcpClient(host, port, authToken, strictMode, autoReconnect)`.
- HTTP TLS secure default is enabled when `tls(true)`; dev-only insecure mode is explicit via `.devInsecureTls(true)`.
- Exceptions are surfaced as `IOException`, `InterruptedException`, or Ditto-specific exception types depending on layer.

## Python notes

- Synchronous API.
- Context manager support for TCP client.
- Pattern operations are available on both HTTP and TCP clients.
- TCP watch APIs are available: `watch(key)`, `wait_watch_event()`, `unwatch(key)`.
- TCP optional reconnect retry is available via `auto_reconnect=True`.
- HTTP TLS secure default is enabled when `tls=True`; dev-only insecure mode is explicit via `dev_insecure_tls=True`.

## Go notes

- Synchronous API for HTTP and TCP clients.
- Namespace-aware helpers are available for both protocols.
- Strict mode is available via `StrictMode: true` in client options.
- HTTP TLS verification is secure-by-default when `TLS: true`.
- Dev-only insecure mode is explicit via `DevInsecureTLS: true` (legacy `InsecureSkipVerify: true` remains supported).
- TCP watch APIs are available: `Watch(key)`, `WaitWatchEvent()`, `Unwatch(key)`.
- TCP optional reconnect retry is available via `AutoReconnect: true` in `TCPClientOptions`.

## Watch flow examples

### Java

```java
try (DittoTcpClient tcp = new DittoTcpClient("localhost", 7777, null, false, true)) {
    tcp.connect();
    tcp.watch("k");
    tcp.set("k", "value");
    DittoWatchEvent ev = tcp.waitForWatchEvent();
    tcp.unwatch("k");
}
```

### Python

```python
from ditto_client import DittoTcpClient

with DittoTcpClient(host="localhost", port=7777, auto_reconnect=True) as tcp:
    tcp.watch("k")
    tcp.set("k", "value")
    ev = tcp.wait_watch_event()
    tcp.unwatch("k")
```

### Go

```go
tcp := ditto.NewTCPClient(ditto.TCPClientOptions{
    Host: "localhost",
    Port: 7777,
    AutoReconnect: true,
})
_ = tcp.Connect()
_ = tcp.Watch("k")
_, _ = tcp.SetString("k", "value")
ev, _ := tcp.WaitWatchEvent()
_ = tcp.Unwatch("k")
_ = tcp.Close()
_ = ev
```

## Local development

### Node.js client

```bash
cd ditto-client/ditto-nodejs-client
npm install
npm run build
npm run test:integration
```

### Java client

```bash
cd ditto-client/ditto-java-client
./gradlew test
```

### Python client

```bash
cd ditto-client/ditto-python-client
python -m unittest discover -s tests -v
```

(Exact available test tasks depend on local setup; docker tests below are the canonical integration path used in this workspace.)

## Docker integration tests

Client integration environments are under `ditto-docker/clients/*`.
Each compose file starts:
- one `dittod` test node,
- one HTTP test container,
- one TCP test container.

### Node.js integration tests

```bash
cd ditto-docker
docker compose -f clients/nodejs/docker-compose.yml up --build --abort-on-container-exit
docker compose -f clients/nodejs/docker-compose.yml down
```

### Java integration tests

```bash
cd ditto-docker
docker compose -f clients/java/docker-compose.yml up --build --abort-on-container-exit
docker compose -f clients/java/docker-compose.yml down
```

### Python integration tests

```bash
cd ditto-docker
docker compose -f clients/python/docker-compose.yml up --build --abort-on-container-exit
docker compose -f clients/python/docker-compose.yml down
```

Pass condition:
- test containers exit with code `0`.

## Release dry-run

- Workflow: `.github/workflows/release-dry-run.yml` (manual trigger with `release_version` input).
- Local helper: `scripts/release-dry-run.sh <release-version>`.
- The dry-run flow:
  - generates changelog preview (`release-dry-run-changelog.md`),
  - validates package/jar build steps across Node/Python/Java,
  - runs Go test sanity before release tagging.
- See details in `docs/client-release-guide.md`.

## Coverage reporting (Phase A)

- Workflow: `.github/workflows/coverage-report.yml`
- Purpose: generate multi-language coverage reports on PR/push/manual runs without coverage-gate failure yet.
- Artifacts:
  - Node.js coverage output (`coverage-node.txt`),
  - Go coverage (`coverage.out`, `coverage-go.txt`),
  - Python coverage (`.coverage`, `coverage.xml`, `coverage-python.txt`),
  - Java JaCoCo XML/HTML report.
- PR no-regression checks (Phase C entry):
  - Node.js line coverage compared against base branch,
  - Go statement coverage compared against base branch.

## Contract runtime parity

- `contracts/core-ops.contract.json` defines the shared runtime cases.
- All SDK lanes execute runtime adapter tests against this contract in CI:
  - Node: `tests/contract-runtime.test.mjs`
  - Go: `contract_runner_test.go`
  - Python: `tests/test_contract_runtime.py`
  - Java: `DittoContractRuntimeSmokeTest`

## Compatibility expectations

When introducing protocol-level changes:

1. Update `ditto-protocol` enums and wire behavior.
2. Update all four SDKs (Node/Java/Python/Go).
3. Update docker integration tests under `ditto-docker/clients/*`.
4. Re-run all client docker suites.
5. Update this guide.

## Common pitfalls

- Path confusion: client integration compose files are in `ditto-docker`, not `ditto-client`.
- Self-signed TLS in dev: expected; keep strict verification in production.
- Watch traffic: server may stay idle for long periods by design; avoid client idle timeouts for watch-only sessions.

## Backlog status

- Completed (2026-04-09):
  - guide sync after parity rollout,
  - docker integration parity for watch flow,
  - reconnect regression coverage in Java/Python/Go,
  - client README consistency pass,
  - optional strict docker watch assertion mode (`DITTO_STRICT_WATCH_ASSERT`) with best-effort default.

- Completed (2026-04-10):
  - dependency pass:
    - `ditto-cache` workspace dependencies verified up-to-date (`cargo outdated -R`),
    - Node client dev dependency refresh: `@types/node` -> `25.6.0`,
    - Java client dependency verified current: `jackson-databind` latest `2.21.2`,
    - Python/Go clients verified as stdlib-only/no third-party runtime deps.
  - client parity hardening:
    - Go HTTP error mapping now prefers server payload error code (`error`) over coarse HTTP status fallback,
    - Go namespace header handling now trims whitespace and suppresses blank namespace headers consistently.
  - parity regression coverage:
    - Go tests added for payload-priority error mapping and namespace header trimming behavior.
  - strict-mode parity follow-up:
    - aligned strict validation across Node/Java/Python/Go for `deleteByPattern` and `setTtlByPattern`,
    - aligned TCP watch/unwatch strict key+namespace validation behavior,
    - added strict validation regression tests in Node/Python/Java/Go.
  - release automation prep:
    - added manual release dry-run workflow (`.github/workflows/release-dry-run.yml`),
    - added release planning script (`scripts/release-dry-run.sh`),
    - added release guide (`docs/client-release-guide.md`).
  - Sprint 5 / Phase A kickoff:
    - added coverage-report workflow (`.github/workflows/coverage-report.yml`),
    - enabled Java JaCoCo report generation in Gradle (`test` + `jacocoTestReport`).
