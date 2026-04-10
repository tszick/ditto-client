import test from 'node:test';
import assert from 'node:assert/strict';

import { DittoHttpClient, DittoTcpClient } from '../dist/index.js';

test('http strict mode rejects invalid pattern inputs', async () => {
  const client = new DittoHttpClient({ strictMode: true });
  await assert.rejects(
    client.deleteByPattern('bad pattern*'),
    /Invalid deleteByPattern request: pattern contains unsupported characters/,
  );
  await assert.rejects(
    client.setTtlByPattern('ok:*', 60, '   '),
    /Invalid setTtlByPattern request: namespace must not be blank when provided/,
  );
  await assert.rejects(
    client.deleteByPattern('tenant:*', 'alpha::beta'),
    /Invalid deleteByPattern request: namespace must not contain '::'/,
  );
});

test('tcp strict mode validates watch and pattern inputs before network io', async () => {
  const client = new DittoTcpClient({ strictMode: true });
  await assert.rejects(
    client.watch(' ', () => {}),
    /Invalid watch request: key must not be empty/,
  );
  await assert.rejects(
    client.deleteByPattern('bad pattern*'),
    /Invalid deleteByPattern request: pattern contains unsupported characters/,
  );
  await assert.rejects(
    client.watch('watch:key', () => {}, 'bad::ns'),
    /Invalid watch request: namespace must not contain '::'/,
  );
});
