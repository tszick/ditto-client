# ditto-client

Node.js client library for [Ditto](https://github.com/yourorg/ditto) — a distributed in-memory cache.

Two client classes are provided:

| Class | Port | Protocol |
|-------|------|----------|
| `DittoHttpClient` | 7778 | JSON REST over HTTP(S) |
| `DittoTcpClient`  | 7777 | bincode binary over TCP |

**Requirements:** Node.js ≥ 22

---

## Installation

```bash
npm install ditto-client
```

---

## Quick start – HTTP client

```typescript
import { DittoHttpClient } from 'ditto-client';

const client = new DittoHttpClient({ host: 'localhost', port: 7778 });

await client.set('user:1', 'Alice', 3600);       // set with TTL
const result = await client.get('user:1');        // → { value: Buffer, version: 1 }
console.log(result?.value.toString());            // → "Alice"
await client.delete('user:1');
```

---

## Quick start – TCP client

```typescript
import { DittoTcpClient } from 'ditto-client';

const client = new DittoTcpClient({ host: 'localhost', port: 7777 });
await client.connect();

await client.set('session:abc', 'tok_xyz', 300);
const result = await client.get('session:abc');
console.log(result?.value.toString());            // → "tok_xyz"

await client.close();
```

---

## API Reference

### `DittoHttpClient`

#### Constructor

```typescript
new DittoHttpClient(opts?: {
  host?:               string;   // default: 'localhost'
  port?:               number;   // default: 7778
  tls?:                boolean;  // default: false
  username?:           string;   // HTTP Basic Auth
  password?:           string;
  rejectUnauthorized?: boolean;  // default: true (set false for self-signed certs)
})
```

#### Methods

| Method | Description | Returns |
|--------|-------------|---------|
| `ping()` | Health check | `Promise<boolean>` |
| `get(key)` | Get a value | `Promise<DittoGetResult \| null>` |
| `set(key, value, ttlSecs?)` | Set a value with optional TTL | `Promise<DittoSetResult>` |
| `delete(key)` | Delete a key | `Promise<boolean>` |
| `stats()` | Cache statistics | `Promise<DittoStatsResult>` |
| `close()` | No-op (HTTP is stateless) | `void` |

---

### `DittoTcpClient`

#### Constructor

```typescript
new DittoTcpClient(opts?: {
  host?: string;  // default: 'localhost'
  port?: number;  // default: 7777
})
```

#### Methods

| Method | Description | Returns |
|--------|-------------|---------|
| `connect()` | Open the TCP connection | `Promise<void>` |
| `ping()` | Health check | `Promise<boolean>` |
| `get(key)` | Get a value | `Promise<DittoGetResult \| null>` |
| `set(key, value, ttlSecs?)` | Set a value with optional TTL | `Promise<DittoSetResult>` |
| `delete(key)` | Delete a key | `Promise<boolean>` |
| `close()` | Close the TCP connection | `Promise<void>` |

---

### Common types

```typescript
interface DittoGetResult {
  value:   Buffer;   // raw stored bytes
  version: number;   // monotonically increasing write counter
}

interface DittoSetResult {
  version: number;
}

interface DittoStatsResult {
  node_id:                 string;
  status:                  string;   // 'Active' | 'Syncing' | 'Offline' | 'Inactive'
  is_primary:              boolean;
  committed_index:         number;
  key_count:               number;
  memory_used_bytes:       number;
  memory_max_bytes:        number;
  evictions:               number;
  hit_count:               number;
  miss_count:              number;
  uptime_secs:             number;
  compression_enabled:     boolean;
  node_name:               string;
  // ... (see full type in types.ts)
}

type DittoErrorCode =
  | 'NodeInactive' | 'NoQuorum' | 'KeyNotFound'
  | 'InternalError' | 'WriteTimeout' | 'ValueTooLarge' | 'KeyLimitReached';

class DittoError extends Error {
  code: DittoErrorCode;
}
```

---

## Error handling

All methods throw `DittoError` on server-side errors. The `code` property maps
to the server's `ErrorCode` enum:

```typescript
import { DittoHttpClient, DittoError } from 'ditto-client';

const client = new DittoHttpClient({ host: 'localhost' });

try {
  await client.set('key', 'value');
} catch (err) {
  if (err instanceof DittoError) {
    console.error(err.code, err.message);
    // e.g. 'NodeInactive' | 'NoQuorum' | 'ValueTooLarge' ...
  }
}
```

---

## HTTPS / TLS

```typescript
const client = new DittoHttpClient({
  host:               'my-node.example.com',
  port:               7778,
  tls:                true,
  username:           'ditto',
  password:           'mypassword',
  rejectUnauthorized: false,  // set false if using self-signed certs
});
```

---

## Docker test environment

A self-contained Docker Compose test environment lives at
`docker/clients/nodejs/` (relative to the repository root).

It starts a single dittod node and runs both test apps against it:

```bash
cd ditto/docker/clients/nodejs
docker compose up --build --abort-on-container-exit
```

The first run compiles the Rust binaries (~3–5 min), subsequent runs use
Docker's build cache.
