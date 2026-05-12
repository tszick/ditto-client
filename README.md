# Ditto Cache Clients

Client libraries for the [Ditto distributed cache](https://github.com/tszick/ditto-cache).

This repository currently contains five client implementations:

- Go (`ditto-go-client`)
- Java (`ditto-java-client`)
- Node.js (`ditto-nodejs-client`)
- Python (`ditto-python-client`)
- Rust (`ditto-rust-client`)

---

## Clients

### Go - `ditto-go-client`

Requires Go 1.26.0+.

```go
httpClient := ditto.NewHTTPClient(ditto.HTTPClientOptions{Host: "localhost", Port: 7778})
stats, _ := httpClient.Stats()

tcp := ditto.NewTCPClient(ditto.TCPClientOptions{Host: "localhost", Port: 7777})
_ = tcp.Connect()
_, _ = tcp.SetString("foo", "bar", 60)
```

Supports HTTP and TCP clients, including pattern operations.
TCP also supports `watch/unwatch`, `WaitWatchEvent()`, strict mode and optional one-shot auto reconnect (`AutoReconnect: true`).

---

### Java - `ditto-java-client`

Requires Java 21. Built with Gradle.

```java
// HTTP
DittoHttpClient http = new DittoHttpClient.Builder().host("localhost").port(7778).build();
http.set("foo", "bar", 60);
DittoGetResult r = http.get("foo");

// TCP
DittoTcpClient tcp = new DittoTcpClient("localhost", 7777, null, false, true); // autoReconnect=true
tcp.connect();
tcp.set("foo", "bar", 0);
tcp.delete("foo");
```

Both clients are thread-safe. HTTP uses Java's built-in `HttpClient`; TCP uses a persistent socket connection and supports `watch/unwatch` + `waitForWatchEvent()`.

---

### Node.js - `ditto-nodejs-client`

Requires Node.js >= 24. TypeScript source, ships compiled JS + type declarations.

```ts
import { DittoTcpClient } from "ditto-client";

const client = new DittoTcpClient({ host: "localhost", port: 7777 });
await client.set("foo", "bar", 60);
const result = await client.get("foo"); // { value: Buffer, version: bigint } | null
await client.close();
```

All methods are `async`. The TCP client queues concurrent requests internally.
TCP also supports `watch/unwatch`, optional reconnect queue (`autoReconnect`) and reconnect backoff settings.

---

### Rust - `ditto-rust-client`

Requires Rust 1.95+. Async Tokio-based client using `reqwest` for HTTP.

```rust
use ditto_rust_client::{DittoHttpClient, HttpClientOptions};

#[tokio::main]
async fn main() -> ditto_rust_client::Result<()> {
    let client = DittoHttpClient::new(HttpClientOptions::default())?;
    client.set_string("foo", "bar", Some(60), None).await?;
    let result = client.get("foo", None).await?;
    Ok(())
}
```

Supports HTTP and TCP clients, namespace-aware operations, strict mode,
pattern operations, TCP watch/unwatch, and optional TCP reconnect retry.

---

### Python - `ditto-python-client`

Requires Python >= 3.11. No external dependencies (stdlib only).

```python
from ditto_client import DittoTcpClient

with DittoTcpClient(host="localhost", port=7777) as client:
    client.set("foo", "bar", ttl_secs=60)
    result = client.get("foo")  # DittoGetResult(value=b"bar", version=1) | None
```

Synchronous blocking API. Thread-safe via internal lock. Context manager supported.
TCP also supports `watch/unwatch`, `wait_watch_event()` and optional one-shot auto reconnect (`auto_reconnect=True`).

---

## API Reference

Core operations available across clients:

| Method | Description |
|--------|-------------|
| `ping()` | Check node liveness |
| `get(key, namespace?)` | Get value + version, or `null`/`None` if missing |
| `set(key, value, ttl, namespace?)` | Set value; `ttl=0` means no expiry |
| `delete(key, namespace?)` | Delete key, returns bool |
| `deleteByPattern(pattern, namespace?)` | Delete keys by glob pattern |
| `setTtlByPattern(pattern, ttl, namespace?)` | Update TTL by glob pattern |
| `watch(key, ...)` | Subscribe to key updates (TCP clients) |
| `unwatch(key, namespace?)` | Cancel key update subscription (TCP clients) |
| `wait watch event` (`waitForWatchEvent` / `wait_watch_event` / `WaitWatchEvent`) | Block for next watch event frame (TCP clients) |
| `stats()` | Cache statistics - HTTP client only |

Some clients also expose pattern operations (`delete-by-pattern`, `set-ttl-by-pattern`) and protocol-specific features.

Namespace support is available across all clients:

- HTTP: `X-Ditto-Namespace` request header
- TCP: optional `namespace` sub-message encoded in the protobuf request envelope

---

## Server Compatibility Notes

Recent Ditto server releases added major runtime capabilities:

- persistence policy gates (backup/export/import default OFF),
- read-repair and anti-entropy reconciliation,
- mixed-version probe counters for rolling upgrades,
- tenant namespace isolation and per-namespace quotas.

Client impact:

- server protocol supports namespace-aware operations (`namespace` field on TCP, `X-Ditto-Namespace` header on HTTP),
- server may return newer error codes (for example `NamespaceQuotaExceeded`),
- clients should handle unknown/new error codes gracefully.
- SDKs preserve server payload error codes when present (including unknown/new values) for forward-compatible handling.

---

## Protocols

| Protocol | Default port | Auth |
|----------|-------------|------|
| HTTP REST | 7778 | Basic auth (username/password) |
| TCP binary | 7777 | Auth token |

Production clients should use HTTPS with certificate verification enabled and strict client-side validation where possible.

HTTP supports TLS. TLS policy is secure-by-default across SDKs:

- Node.js and Java reject insecure TLS bypass options.
- Go, Python, and Rust still expose dev-only insecure TLS bypasses for local/self-signed testing. These emit warnings and must not be used in production.

The TCP protocol uses protobuf `Envelope` messages with a 4-byte big-endian frame length prefix. Raw TCP does not encrypt tokens or cache payloads. Use raw TCP only on loopback, private trusted networks, or an encrypted underlay such as a service mesh, VPN, or tunnel.

## Quick test commands

```bash
cd ditto-client/ditto-go-client && go test ./...
cd ditto-client/ditto-rust-client && cargo test
cd ditto-client/ditto-python-client && python -m unittest discover -s tests -v
cd ditto-client/ditto-java-client && ./gradlew test --console=plain
cd ditto-client/ditto-nodejs-client && npm run test:integration
cd ditto-client && python contracts/validate_contracts.py
cd ditto-client && python scripts/validate_protocol_snapshot.py
cd ditto-client && python scripts/check_sdk_protocol_parity.py
```

## Cross-SDK contract specs

Contract specs live in `contracts/` and define language-agnostic behavior
expectations for parity runners. CI validates JSON structure with
`contracts/validate_contracts.py`, and all SDK lanes execute runtime contract
checks against `contracts/core-ops.contract.json`.

Protocol schema-first snapshot:
- Canonical snapshot file: `contracts/protocol-contract.snapshot.json`
- Local sync helper (workspace sibling): `python scripts/sync_protocol_snapshot.py`
- CI parity gate workflow: `.github/workflows/protocol-parity.yml`
  - checks snapshot drift against `ditto-cache/ditto-protocol/schema/protocol-contract.json`,
  - validates snapshot structure,
  - validates contract specs,
  - checks SDK known error-code parity against the protocol snapshot.
  - current committed parity includes `NamespaceQuotaExceeded`.

Coverage status:
- `.github/workflows/coverage-report.yml` publishes multi-language coverage artifacts.
- Current CI gate state is conservative required no-regression:
  - enforced PR no-regression checks for Node.js, Go, Python, and Java coverage,
  - Rust coverage artifacts are published,
  - absolute release-candidate thresholds are enforced by `Client Coverage Threshold Summary`.
- Threshold policy lives in `release/coverage-threshold-policy.json`; exceptions must be explicit, approved, and time-limited.

Release and review notes are kept outside the public `docs/` tree.
