import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

import { DittoHttpClient, DittoError } from '../dist/index.js';

function listen(handler) {
  const server = http.createServer(handler);
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      resolve({ server, port: address.port });
    });
  });
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  });
}

test('http client exercises full endpoint surface and retry path', async () => {
  let pingAttempts = 0;
  const seen = [];
  const { server, port } = await listen(async (req, res) => {
    seen.push(`${req.method} ${req.url}`);
    assert.equal(req.headers.authorization, `Basic ${Buffer.from('ditto:secret').toString('base64')}`);

    if (req.method === 'GET' && req.url === '/ping') {
      pingAttempts += 1;
      if (pingAttempts === 1) {
        res.writeHead(503, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'NodeInactive', message: 'warming up' }));
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ pong: true }));
      return;
    }

    if (req.method === 'PUT' && req.url === '/key/ns-key?ttl=30') {
      assert.equal(req.headers['x-ditto-namespace'], 'tenant-a');
      assert.equal(req.headers['content-type'], 'text/plain');
      assert.equal(await readBody(req), 'value');
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ version: 7 }));
      return;
    }

    if (req.method === 'GET' && req.url === '/key/ns-key') {
      assert.equal(req.headers['x-ditto-namespace'], 'tenant-a');
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ value: 'value', version: 7 }));
      return;
    }

    if (req.method === 'DELETE' && req.url === '/key/ns-key') {
      res.writeHead(204);
      res.end();
      return;
    }

    if (req.method === 'DELETE' && req.url === '/key/missing') {
      res.writeHead(404);
      res.end();
      return;
    }

    if (req.method === 'POST' && req.url === '/keys/delete-by-pattern') {
      assert.equal(req.headers['x-ditto-namespace'], 'tenant-a');
      assert.deepEqual(JSON.parse(await readBody(req)), { pattern: 'tenant:*' });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ deleted: 3 }));
      return;
    }

    if (req.method === 'POST' && req.url === '/keys/ttl-by-pattern') {
      assert.deepEqual(JSON.parse(await readBody(req)), { pattern: 'tenant:*', ttl_secs: 45 });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ updated: 4 }));
      return;
    }

    if (req.method === 'GET' && req.url === '/stats') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        node_id: 'n1',
        status: 'active',
        is_primary: true,
        key_count: 2,
      }));
      return;
    }

    if (req.method === 'GET' && req.url === '/key/failing') {
      res.writeHead(429, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'RateLimited', message: 'slow down' }));
      return;
    }

    res.writeHead(500);
    res.end(`unexpected ${req.method} ${req.url}`);
  });

  const client = new DittoHttpClient({
    host: '127.0.0.1',
    port,
    username: 'ditto',
    password: 'secret',
    retryBaseBackoffMs: 1,
    retryJitterMs: 0,
    strictMode: true,
  });
  try {
    assert.equal(await client.ping(), true);
    assert.equal((await client.set('ns-key', 'value', 30, 'tenant-a')).version, 7);
    const got = await client.get('ns-key', 'tenant-a');
    assert.equal(got.value.toString('utf8'), 'value');
    assert.equal(got.version, 7);
    assert.equal(await client.delete('ns-key'), true);
    assert.equal(await client.delete('missing'), false);
    assert.deepEqual(await client.deleteByPattern('tenant:*', 'tenant-a'), { deleted: 3 });
    assert.deepEqual(await client.setTtlByPattern('tenant:*', 45), { updated: 4 });
    assert.equal((await client.stats()).node_id, 'n1');
    await assert.rejects(client.get('failing'), (err) => err instanceof DittoError && err.code === 'RateLimited');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }

  assert.equal(pingAttempts, 2);
  assert.ok(seen.length >= 10);
});
