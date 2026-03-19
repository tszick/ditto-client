# Ditto Cache Clients

Client libraries for the [Ditto distributed cache](https://github.com/tszick/ditto-cache). All three clients expose the same API over two protocols: **HTTP REST** and **TCP binary** (Bincode 1.x framing).

---

## Clients

### Java — `ditto-java-client`

Requires Java 11+. Built with Gradle.

```java
// HTTP
DittoHttpClient http = new DittoHttpClient.Builder().host("localhost").port(7778).build();
http.set("foo", "bar", 60);
DittoGetResult r = http.get("foo");

// TCP
DittoTcpClient tcp = new DittoTcpClient.Builder().host("localhost").port(7777).build();
tcp.set("foo", "bar", 0);
tcp.delete("foo");
```

Both clients are thread-safe. HTTP uses Java's built-in `HttpClient`; TCP uses a persistent socket connection.

---

### Node.js — `ditto-nodejs-client`

Requires Node.js ≥ 22. TypeScript source, ships compiled JS + type declarations.

```ts
import { DittoTcpClient } from "ditto-client";

const client = new DittoTcpClient({ host: "localhost", port: 7777 });
await client.set("foo", "bar", 60);
const result = await client.get("foo"); // { value: Buffer, version: bigint } | null
await client.close();
```

All methods are `async`. The TCP client queues concurrent requests internally.

---

### Python — `ditto-python-client`

Requires Python ≥ 3.11. No external dependencies (stdlib only).

```python
from ditto_client import DittoTcpClient

with DittoTcpClient(host="localhost", port=7777) as client:
    client.set("foo", "bar", ttl_secs=60)
    result = client.get("foo")  # DittoGetResult(value=b"bar", version=1) | None
```

Synchronous blocking API. Thread-safe via internal lock. Context manager supported.

---

## API reference

All clients implement the same operations:

| Method | Description |
|--------|-------------|
| `ping()` | Check node liveness |
| `get(key)` | Get value + version, or `null`/`None` if missing |
| `set(key, value, ttl)` | Set value; `ttl=0` means no expiry |
| `delete(key)` | Delete key, returns bool |
| `stats()` | Cache statistics — HTTP client only |

## Protocols

| Protocol | Default port | Auth |
|----------|-------------|------|
| HTTP REST | 7778 | Basic auth (username/password) |
| TCP binary | 7777 | Auth token |

Both protocols support TLS. The TCP protocol uses Bincode 1.x encoding with a 4-byte big-endian frame length prefix.
