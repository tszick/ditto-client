# Ditto Client Developer Guide

This guide documents the current state of Ditto client SDKs in this repository.

## Repositories and layout

- Node.js client: `ditto-nodejs-client`
- Java client: `ditto-java-client`
- Python client: `ditto-python-client`
- Go client: `ditto-go-client`
- Rust client: `ditto-rust-client`

Shared behavior target:
- same core cache operations across SDKs,
- TCP binary and HTTP REST client support,
- pattern-based bulk operations on both protocols.

## Protocols

- TCP binary (`dittod` port 7777) — protobuf-encoded `Envelope` messages,
  4-byte big-endian length-prefixed. The wire contract is defined in
  `ditto-cache/ditto-protocol/proto/ditto.proto` (proto3, package
  `ditto.protocol.v1`); this `.proto` file is the source of truth across
  every SDK. The earlier bincode 1.x wire format was retired during the
  protobuf migration — pre-migration SDK builds cannot talk to a `dittod`
  built after the migration commit.
- HTTP/HTTPS REST (`dittod` port 7778) — JSON over HTTP, unaffected by the
  TCP wire-format change.

## Feature matrix (current)

| Feature | Node.js | Java | Python | Go | Rust |
|---|---|---|---|---|---|
| TCP client | yes | yes | yes | yes | yes |
| HTTP client | yes | yes | yes | yes | yes |
| `ping/get/set/delete` | yes | yes | yes | yes | yes |
| `deleteByPattern` / `delete_by_pattern` | yes | yes | yes | yes | yes |
| `setTtlByPattern` / `set_ttl_by_pattern` | yes | yes | yes | yes | yes |
| Namespace-aware operations | yes | yes | yes | yes | yes |
| Strict mode (`key`/`namespace` validation) | yes | yes | yes | yes | yes |
| Key watch/unwatch (TCP) | yes | yes | yes | yes | yes |
| Auto reconnect (TCP) | yes | yes | yes | yes | yes |

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
- TCP clients encode namespace as the protobuf `OptionalString namespace` sub-message on request variants (omitted when blank).
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
- insecure TLS bypass options are rejected; use a trusted certificate instead.

## Java notes

- `DittoTcpClient` and `DittoHttpClient` expose matching core operations.
- Pattern operations are available on both clients.
- TCP watch APIs are available: `watch(key)`, `waitForWatchEvent()`, `unwatch(key)`.
- TCP optional reconnect retry is available via `new DittoTcpClient(host, port, authToken, strictMode, autoReconnect)`.
- HTTP TLS secure default is enabled when `tls(true)`; insecure TLS bypass options are rejected.
- Exceptions are surfaced as `IOException`, `InterruptedException`, or Ditto-specific exception types depending on layer.

## Python notes

- Synchronous API.
- Context manager support for TCP client.
- Pattern operations are available on both HTTP and TCP clients.
- TCP watch APIs are available: `watch(key)`, `wait_watch_event()`, `unwatch(key)`.
- TCP optional reconnect retry is available via `auto_reconnect=True`.
- HTTP TLS secure default is enabled when `tls=True`; `dev_insecure_tls=True` is local-development only and emits a warning.

## Go notes

- Synchronous API for HTTP and TCP clients.
- Namespace-aware helpers are available for both protocols.
- Strict mode is available via `StrictMode: true` in client options.
- HTTP TLS verification is secure-by-default when `TLS: true`.
- Dev-only insecure mode is explicit via `DevInsecureTLS: true` (legacy `InsecureSkipVerify: true` remains supported) and emits a warning.
- TCP watch APIs are available: `Watch(key)`, `WaitWatchEvent()`, `Unwatch(key)`.
- TCP optional reconnect retry is available via `AutoReconnect: true` in `TCPClientOptions`.

## Rust notes

- Async Tokio-based API for HTTP and TCP clients.
- Namespace-aware helpers are available for both protocols via optional namespace arguments.
- Strict mode is available via `strict_mode: true` in client options.
- HTTP TLS verification is secure-by-default when `tls: true`.
- Dev-only insecure mode is explicit via `dev_insecure_tls: true` and emits a warning.

## Production transport policy

- Production clients should use HTTPS with certificate verification enabled.
- Raw TCP authenticates with a token but does not encrypt tokens or cache payloads.
- Use raw TCP only on loopback, private trusted networks, or an encrypted underlay such as a service mesh, VPN, or tunnel.
- Production examples should enable strict client-side validation where consumer key formats allow it.
- TCP watch APIs are available: `watch(key, namespace)`, `wait_watch_event()`, `unwatch(key, namespace)`.
- TCP optional reconnect retry is available via `auto_reconnect: true` in `TcpClientOptions`.

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

### Rust

```rust
let tcp = ditto_rust_client::DittoTcpClient::new(ditto_rust_client::TcpClientOptions {
    auto_reconnect: true,
    ..ditto_rust_client::TcpClientOptions::default()
});
tcp.connect().await?;
tcp.watch("k", None).await?;
tcp.set_string("k", "value", None, None).await?;
let ev = tcp.wait_watch_event().await?;
tcp.unwatch("k", None).await?;
tcp.close().await?;
let _ = ev;
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

### Rust client

```bash
cd ditto-client/ditto-rust-client
cargo test
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

### Rust integration tests

```bash
cd ditto-docker
docker compose -f clients/rust/docker-compose.yml up --build --abort-on-container-exit
docker compose -f clients/rust/docker-compose.yml down
```

Pass condition:
- test containers exit with code `0`.

## Contract runtime parity

- `contracts/core-ops.contract.json` defines the shared runtime cases.
- SDK lanes execute runtime adapter tests against this contract:
  - Node: `tests/contract-runtime.test.mjs`
  - Go: `contract_runner_test.go`
  - Python: `tests/test_contract_runtime.py`
  - Java: `DittoContractRuntimeSmokeTest`
  - Rust: `tests/contract_runtime.rs`
- Schema-first protocol snapshot is tracked in `contracts/protocol-contract.snapshot.json`.
  - Protocol parity gate workflow: `.github/workflows/protocol-parity.yml`
  - checks snapshot drift against `ditto-cache/ditto-protocol/schema/protocol-contract.json`,
  - validates snapshot structure,
  - validates contract JSON specs,
  - checks SDK known error-code sets against protocol `ErrorCode` enum, including Rust.
  - current snapshot includes `NamespaceQuotaExceeded` parity alignment across SDKs.

## Compatibility expectations

When introducing protocol-level changes:

1. Update `ditto-protocol` enums and wire behavior.
2. Update all five SDKs (Node/Java/Python/Go/Rust).
3. Update docker integration tests under `ditto-docker/clients/*`.
4. Re-run all client docker suites.
5. Update this guide.

## Common pitfalls

- Path confusion: client integration compose files are in `ditto-docker`, not `ditto-client`.
- Self-signed TLS in dev: expected; keep strict verification in production.
- Watch traffic: server may stay idle for long periods by design; avoid client idle timeouts for watch-only sessions.
