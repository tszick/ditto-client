# Ditto Cache Clients

Client libraries for the [Ditto distributed cache](https://github.com/tszick/ditto-cache).

This repository currently contains four client implementations:

- Go (`ditto-go-client`)
- Java (`ditto-java-client`)
- Node.js (`ditto-nodejs-client`)
- Python (`ditto-python-client`)

---

## Clients

### Go - `ditto-go-client`

Requires Go 1.22+.

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

Requires Node.js >= 22. TypeScript source, ships compiled JS + type declarations.

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
- TCP: optional `namespace` field encoded as bincode `Option<String>`

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

---

## Protocols

| Protocol | Default port | Auth |
|----------|-------------|------|
| HTTP REST | 7778 | Basic auth (username/password) |
| TCP binary | 7777 | Auth token |

Both protocols support TLS. The TCP protocol uses Bincode 1.x encoding with a 4-byte big-endian frame length prefix.

## Quick test commands

```bash
cd ditto-client/ditto-go-client && go test ./...
cd ditto-client/ditto-python-client && python -m unittest discover -s tests -v
cd ditto-client/ditto-java-client && ./gradlew test --console=plain
cd ditto-client/ditto-nodejs-client && npm run test:integration
cd ditto-client && python contracts/validate_contracts.py
```

## Cross-SDK contract specs

Contract specs live in `contracts/` and define language-agnostic behavior
expectations for parity runners. CI validates JSON structure with
`contracts/validate_contracts.py`, and Go SDK currently executes the core
suite via `ditto-go-client/contract_runner_test.go`.
