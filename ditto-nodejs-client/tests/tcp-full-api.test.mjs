import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';

import { DittoTcpClient } from '../dist/index.js';
import {
  REQUEST_FIELDS,
  RESPONSE_FIELDS,
  decodeClientRequestVariant,
  encodeCounterResponseInner,
  encodeSetNxResponseInner,
  frameClientResponse,
  encodeVersionResponseInner,
} from '../dist/wire.js';

function readVariant(socket) {
  return new Promise((resolve, reject) => {
    let buf = Buffer.alloc(0);
    const onData = (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (buf.length < 4) return;
      const len = buf.readUInt32BE(0);
      if (buf.length < 4 + len) return;
      socket.off('data', onData);
      try {
        resolve(decodeClientRequestVariant(buf.subarray(4, 4 + len)).field);
      } catch (err) {
        reject(err);
      }
    };
    socket.on('data', onData);
    socket.once('error', reject);
  });
}

test('tcp client exercises auth get delete pattern and ttl commands', async () => {
  const expected = [
    REQUEST_FIELDS.AUTH,
    REQUEST_FIELDS.SET,
    REQUEST_FIELDS.GET,
    REQUEST_FIELDS.DELETE,
    REQUEST_FIELDS.DELETE_BY_PATTERN,
    REQUEST_FIELDS.SET_TTL_BY_PATTERN,
    REQUEST_FIELDS.SET_NX,
    REQUEST_FIELDS.INCR,
  ];
  const responses = new Map([
    [REQUEST_FIELDS.AUTH, frameClientResponse(RESPONSE_FIELDS.AUTH_OK, Buffer.alloc(0))],
    [REQUEST_FIELDS.SET, frameClientResponse(RESPONSE_FIELDS.OK, encodeVersionResponseInner(7))],
    [REQUEST_FIELDS.GET, frameClientResponse(RESPONSE_FIELDS.VALUE, Buffer.alloc(0))],
    [REQUEST_FIELDS.DELETE, frameClientResponse(RESPONSE_FIELDS.DELETED, Buffer.alloc(0))],
    [REQUEST_FIELDS.DELETE_BY_PATTERN, frameClientResponse(RESPONSE_FIELDS.PATTERN_DELETED, Buffer.alloc(0))],
    [REQUEST_FIELDS.SET_TTL_BY_PATTERN, frameClientResponse(RESPONSE_FIELDS.PATTERN_TTL_UPDATED, Buffer.alloc(0))],
    [REQUEST_FIELDS.SET_NX, frameClientResponse(RESPONSE_FIELDS.SET_NX, encodeSetNxResponseInner(true, 9n))],
    [REQUEST_FIELDS.INCR, frameClientResponse(RESPONSE_FIELDS.COUNTER, encodeCounterResponseInner(11n, 12n))],
  ]);

  const server = net.createServer(async (socket) => {
    try {
      for (const want of expected) {
        const got = await readVariant(socket);
        assert.equal(got, want);
        socket.write(responses.get(got));
      }
    } finally {
      socket.end();
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;

  const client = new DittoTcpClient({
    host: '127.0.0.1',
    port,
    authToken: 'token',
    strictMode: true,
  });
  try {
    await client.connect();
    assert.equal((await client.set('ns-key', 'value', 30, 'tenant-a')).version, 7);
    assert.deepEqual(await client.get('ns-key', 'tenant-a'), { value: Buffer.alloc(0), version: 0 });
    assert.equal(await client.delete('ns-key', 'tenant-a'), true);
    assert.deepEqual(await client.deleteByPattern('tenant:*', 'tenant-a'), { deleted: 0 });
    assert.deepEqual(await client.setTtlByPattern('tenant:*', 45, 'tenant-a'), { updated: 0 });
    assert.deepEqual(await client.setNX('lease-key', Buffer.from([1, 2, 3]), 30, 'tenant-a'), {
      created: true,
      version: 9n,
    });
    assert.deepEqual(await client.incr('counter', { delta: 11n, namespace: 'tenant-a' }), {
      value: 11n,
      version: 12n,
    });
  } finally {
    await client.close();
    await new Promise((resolve) => server.close(resolve));
  }
});
