import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import * as tls from 'node:tls';

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

const TEST_CERT_PEM = `-----BEGIN CERTIFICATE-----
MIIC9TCCAd2gAwIBAgIIOXPkpWh9diYwDQYJKoZIhvcNAQELBQAwFDESMBAGA1UE
AxMJbG9jYWxob3N0MB4XDTI2MDcxMzEzMjEzM1oXDTM2MDcxNDEzMjEzM1owFDES
MBAGA1UEAxMJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKC
AQEApmcjL3G350q/SFngflGZi3qTfDpy5Q8tK68wcLrLcGCnuZpdv2g6UdVmBKNL
hzmJsAbqiCGcaAkfRo+xm/hYjDUql+6jv3LFsGbbW5glAJEbIcj263QQAocphuVy
IvfE55k1lWdiccdqDwuIoQmNckYjDn9z+hYk1dOYhiOaYuozvFQEF3OgGJo3UDqK
HZMQr0eI24rndPUuFhvB3a/YGMHeN7X/MlbESHOFGmXMZSA8iU0i/kkm1+02Yl2V
/wTokZalWjmTJKeQGgWerIKX7fhuv+zaphT/zHKmV/1g5Fl5D4dpTMNc/FrMn+kg
YPk12smM3kF48GAFOY8lGg319QIDAQABo0swSTAaBgNVHREEEzARgglsb2NhbGhv
c3SHBH8AAAEwCQYDVR0TBAIwADALBgNVHQ8EBAMCBaAwEwYDVR0lBAwwCgYIKwYB
BQUHAwEwDQYJKoZIhvcNAQELBQADggEBACNrpzen22KHlH8u7PM7tDRrRqrVp0y5
18TnSFG2t5slqec6LHCS+zHnQ/Hn2oAfkMv9GBgNv0a3T42GCBTTRlbOnrnm9DLQ
dL/scMXsbgUXpj4FuX+y+Jzm6MA6Mme65dcxq+VQr5QJ0PaqueSU4zFQ/I2T9qd5
hLijGyTO6yQ1iEodKz1eH4rBmUZKYXvMqzWlGGItpfZ5EZokQeRUx0Z+QFjKC7dm
oHeKuRZ68UucVGCpiMd1TXtAmS2M8XL2vIMG0DPkyChnHG7418mTQOyOrI61TaaN
aVIq+wmgqO6Td5tP3qBUCrb0YLVDTRCM6P55/kf3MGggQEB78KyqxS4=
-----END CERTIFICATE-----`;

const TEST_PFX_BASE64 = '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';

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

test('tcp client can connect over TLS with CA verification', async () => {
  const server = tls.createServer({
    pfx: Buffer.from(TEST_PFX_BASE64, 'base64'),
    passphrase: 'ditto-test',
  }, async (socket) => {
    try {
      assert.equal(await readVariant(socket), REQUEST_FIELDS.AUTH);
      socket.write(frameClientResponse(RESPONSE_FIELDS.AUTH_OK, Buffer.alloc(0)));
      assert.equal(await readVariant(socket), REQUEST_FIELDS.PING);
      socket.write(frameClientResponse(RESPONSE_FIELDS.PONG, Buffer.alloc(0)));
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
    tls: true,
    tlsCaCert: TEST_CERT_PEM,
    tlsServerName: 'localhost',
  });
  try {
    await client.connect();
    assert.equal(await client.ping(), true);
  } finally {
    await client.close();
    await new Promise((resolve) => server.close(resolve));
  }
});

test('tcp client keeps idle TLS connection open when request timeout is short', async () => {
  let connectionCount = 0;
  const server = tls.createServer({
    pfx: Buffer.from(TEST_PFX_BASE64, 'base64'),
    passphrase: 'ditto-test',
  }, async (socket) => {
    connectionCount += 1;
    try {
      assert.equal(await readVariant(socket), REQUEST_FIELDS.AUTH);
      socket.write(frameClientResponse(RESPONSE_FIELDS.AUTH_OK, Buffer.alloc(0)));
      assert.equal(await readVariant(socket), REQUEST_FIELDS.PING);
      socket.write(frameClientResponse(RESPONSE_FIELDS.PONG, Buffer.alloc(0)));
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
    tls: true,
    tlsCaCert: TEST_CERT_PEM,
    tlsServerName: 'localhost',
    autoReconnect: true,
    requestTimeoutMs: 50,
  });
  try {
    await client.connect();
    await new Promise((resolve) => setTimeout(resolve, 150));
    assert.equal(connectionCount, 1);
    assert.equal(await client.ping(), true);
    assert.equal(connectionCount, 1);
  } finally {
    await client.close().catch(() => {});
    await new Promise((resolve) => server.close(resolve));
  }
});

test('tcp client times out a slow request without tearing down the connection', async () => {
  let connectionCount = 0;
  let pingCount = 0;

  const server = net.createServer(async (socket) => {
    connectionCount += 1;
    try {
      pingCount += 1;
      assert.equal(await readVariant(socket), REQUEST_FIELDS.PING);
      setTimeout(() => {
        socket.write(frameClientResponse(RESPONSE_FIELDS.PONG, Buffer.alloc(0)));
      }, 100);

      assert.equal(await readVariant(socket), REQUEST_FIELDS.PING);
      socket.write(frameClientResponse(RESPONSE_FIELDS.PONG, Buffer.alloc(0)));
    } finally {
      socket.end();
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;

  const client = new DittoTcpClient({
    host: '127.0.0.1',
    port,
    requestTimeoutMs: 25,
  });
  try {
    await client.connect();
    await assert.rejects(client.ping(), /Request timeout after 25ms/);
    await new Promise((resolve) => setTimeout(resolve, 150));
    assert.equal(await client.ping(), true);
    assert.equal(connectionCount, 1);
    assert.equal(pingCount, 1);
  } finally {
    await client.close().catch(() => {});
    await new Promise((resolve) => server.close(resolve));
  }
});
