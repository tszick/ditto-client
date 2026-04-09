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

- Each SDK has an opt-in strict mode for `get/set/delete`.
- When enabled:
  - `key` must be non-empty and match `[A-Za-z0-9._:-]+`,
  - `namespace` (if provided) must be non-empty, must not contain `::`, and must match `[A-Za-z0-9._:-]+`.
- Strict mode validation happens client-side before network I/O.

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
- `rejectUnauthorized: false` only for dev/self-signed environments.

## Java notes

- `DittoTcpClient` and `DittoHttpClient` expose matching core operations.
- Pattern operations are available on both clients.
- TCP watch APIs are available: `watch(key)`, `waitForWatchEvent()`, `unwatch(key)`.
- TCP optional reconnect retry is available via `new DittoTcpClient(host, port, authToken, strictMode, autoReconnect)`.
- Exceptions are surfaced as `IOException`, `InterruptedException`, or Ditto-specific exception types depending on layer.

## Python notes

- Synchronous API.
- Context manager support for TCP client.
- Pattern operations are available on both HTTP and TCP clients.
- TCP watch APIs are available: `watch(key)`, `wait_watch_event()`, `unwatch(key)`.
- TCP optional reconnect retry is available via `auto_reconnect=True`.

## Go notes

- Synchronous API for HTTP and TCP clients.
- Namespace-aware helpers are available for both protocols.
- Strict mode is available via `StrictMode: true` in client options.
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
  - client README consistency pass.
- Next candidate:
  - optional stricter docker watch assertion mode for environments where key namespace prefix is deterministic.
