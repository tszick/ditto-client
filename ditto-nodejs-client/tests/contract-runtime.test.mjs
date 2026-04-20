import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';

import { DittoHttpClient } from '../dist/index.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const contractPath = path.resolve(__dirname, '../../contracts/core-ops.contract.json');
const contract = JSON.parse(fs.readFileSync(contractPath, 'utf8'));

test('core ops contract runtime (node http sdk)', async () => {
  const store = new Map();
  let version = 0;

  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost');
    if (req.method === 'GET' && url.pathname === '/ping') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ pong: true }));
      return;
    }

    if (url.pathname.startsWith('/key/')) {
      const key = decodeURIComponent(url.pathname.slice('/key/'.length));
      if (req.method === 'PUT') {
        let body = '';
        req.on('data', (c) => { body += c; });
        req.on('end', () => {
          version += 1;
          store.set(key, { value: body, version });
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ version }));
        });
        return;
      }
      if (req.method === 'GET') {
        const entry = store.get(key);
        if (!entry) {
          res.writeHead(404);
          res.end();
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(entry));
        return;
      }
      if (req.method === 'DELETE') {
        if (!store.has(key)) {
          res.writeHead(404);
          res.end();
          return;
        }
        store.delete(key);
        res.writeHead(204);
        res.end();
        return;
      }
    }

    if (req.method === 'POST' && url.pathname === '/keys/delete-by-pattern') {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => {
        const payload = JSON.parse(body || '{}');
        const prefix = String(payload.pattern ?? '').replace(/\*+$/, '');
        let deleted = 0;
        for (const key of [...store.keys()]) {
          if (key.startsWith(prefix)) {
            store.delete(key);
            deleted += 1;
          }
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ deleted }));
      });
      return;
    }

    res.writeHead(404);
    res.end();
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const addr = server.address();
  assert.ok(addr && typeof addr === 'object');
  const client = new DittoHttpClient({ host: '127.0.0.1', port: addr.port });

  try {
    for (const c of contract.cases) {
      if (c.operation === 'ping') {
        const pong = await client.ping();
        assert.equal(pong, c.expect.value);
        continue;
      }
      if (c.operation === 'set_get') {
        await client.set(c.inputs.key, c.inputs.value, c.inputs.ttl_secs);
        const got = await client.get(c.inputs.key);
        assert.ok(got);
        assert.equal(got.value.toString('utf8'), c.expect.value_equals);
        continue;
      }
      if (c.operation === 'delete') {
        const deleted = await client.delete(c.inputs.key);
        assert.equal(deleted, c.expect.value);
        continue;
      }
      if (c.operation === 'delete_by_pattern') {
        await client.set('contract:prefix:a', 'a');
        await client.set('contract:prefix:b', 'b');
        const out = await client.deleteByPattern(c.inputs.pattern);
        assert.ok(out.deleted >= c.expect.min);
      }
    }
  } finally {
    server.close();
  }
});
