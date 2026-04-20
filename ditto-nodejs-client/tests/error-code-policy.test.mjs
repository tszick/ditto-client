import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

import { DittoError, DittoHttpClient } from '../dist/index.js';

test('http client preserves unknown server error code from payload', async () => {
  const server = http.createServer((req, res) => {
    if (req.url === '/key/x') {
      res.writeHead(409, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'NamespaceQuotaExceeded', message: 'quota hit' }));
      return;
    }
    res.writeHead(404);
    res.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address && typeof address === 'object');

  const client = new DittoHttpClient({ host: '127.0.0.1', port: address.port });
  try {
    await assert.rejects(
      client.get('x'),
      (err) => err instanceof DittoError
        && err.code === 'NamespaceQuotaExceeded'
        && err.message === 'quota hit',
    );
  } finally {
    server.close();
  }
});
