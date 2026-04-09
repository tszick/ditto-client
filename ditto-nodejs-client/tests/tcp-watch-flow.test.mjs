import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { Buffer } from 'node:buffer';

import { DittoTcpClient } from '../dist/index.js';

function writeU64LE(value) {
  const b = Buffer.allocUnsafe(8);
  b.writeBigUInt64LE(BigInt(value), 0);
  return b;
}

function frame(payload) {
  const head = Buffer.allocUnsafe(4);
  head.writeUInt32BE(payload.length, 0);
  return Buffer.concat([head, payload]);
}

function payloadSimple(variant) {
  const b = Buffer.allocUnsafe(4);
  b.writeUInt32LE(variant, 0);
  return b;
}

function payloadOk(version) {
  return Buffer.concat([Buffer.from([1, 0, 0, 0]), writeU64LE(version)]);
}

function payloadWatchEvent(key, value, version) {
  const keyBuf = Buffer.from(key, 'utf8');
  const valBuf = Buffer.from(value, 'utf8');
  return Buffer.concat([
    Buffer.from([9, 0, 0, 0]),
    writeU64LE(keyBuf.length),
    keyBuf,
    Buffer.from([1]),
    writeU64LE(valBuf.length),
    valBuf,
    writeU64LE(version),
  ]);
}

test('tcp watch/set/event/unwatch flow', async () => {
  let stage = 0;
  let recvBuf = Buffer.alloc(0);

  const server = net.createServer((socket) => {
    socket.on('data', (chunk) => {
      recvBuf = Buffer.concat([recvBuf, chunk]);
      while (recvBuf.length >= 4) {
        const n = recvBuf.readUInt32BE(0);
        if (recvBuf.length < 4 + n) break;
        const payload = recvBuf.subarray(4, 4 + n);
        recvBuf = recvBuf.subarray(4 + n);
        const variant = payload.readUInt32LE(0);
        if (stage === 0) {
          assert.equal(variant, 5);
          socket.write(frame(payloadSimple(7)));
          stage = 1;
          continue;
        }
        if (stage === 1) {
          assert.equal(variant, 1);
          socket.write(frame(payloadOk(1)));
          socket.write(frame(payloadWatchEvent('k', 'value', 2)));
          stage = 2;
          continue;
        }
        if (stage === 2) {
          assert.equal(variant, 6);
          socket.write(frame(payloadSimple(8)));
          stage = 3;
        }
      }
    });
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address && typeof address !== 'string');

  let resolveEvent;
  const eventPromise = new Promise((resolve) => {
    resolveEvent = resolve;
  });

  const client = new DittoTcpClient({ host: '127.0.0.1', port: address.port });
  try {
    await client.connect();
    await client.watch('k', (value, version) => {
      resolveEvent({ value, version });
    });
    const setRes = await client.set('k', 'value');
    assert.equal(setRes.version, 1);

    const event = await Promise.race([
      eventPromise,
      new Promise((resolve) => setTimeout(() => resolve(null), 1000)),
    ]);
    assert.ok(event);
    assert.equal(event.version, 2);
    assert.equal(event.value.toString('utf8'), 'value');

    await client.unwatch('k');
    assert.equal(stage, 3);
  } finally {
    await client.close().catch(() => {});
    await new Promise((resolve) => server.close(resolve));
  }
});
