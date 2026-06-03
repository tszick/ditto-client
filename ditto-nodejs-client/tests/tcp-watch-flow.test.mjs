import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { Buffer } from 'node:buffer';

import { DittoTcpClient } from '../dist/index.js';
import {
  REQUEST_FIELDS,
  RESPONSE_FIELDS,
  decodeClientRequestVariant,
  decodeKeyNamespaceInner,
  encodeVersionResponseInner,
  encodeWatchEventInner,
  frameClientResponse,
} from '../dist/wire.js';

test('tcp watch/set/event/unwatch flow (protobuf wire)', async () => {
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

        const { field } = decodeClientRequestVariant(payload);

        if (stage === 0) {
          assert.equal(field, REQUEST_FIELDS.WATCH);
          socket.write(frameClientResponse(RESPONSE_FIELDS.WATCHING, Buffer.alloc(0)));
          stage = 1;
          continue;
        }
        if (stage === 1) {
          assert.equal(field, REQUEST_FIELDS.SET);
          socket.write(frameClientResponse(RESPONSE_FIELDS.OK, encodeVersionResponseInner(1)));
          socket.write(frameClientResponse(
            RESPONSE_FIELDS.WATCH_EVENT,
            encodeWatchEventInner('k', Buffer.from('value', 'utf8'), 2),
          ));
          stage = 2;
          continue;
        }
        if (stage === 2) {
          assert.equal(field, REQUEST_FIELDS.UNWATCH);
          socket.write(frameClientResponse(RESPONSE_FIELDS.UNWATCHED, Buffer.alloc(0)));
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

test('tcp watch routes and reconnects namespaced subscriptions independently', async () => {
  const requests = [];
  let activeSocket;
  let connectionCount = 0;
  let recvBuf = Buffer.alloc(0);

  const server = net.createServer((socket) => {
    connectionCount += 1;
    activeSocket = socket;
    socket.on('data', (chunk) => {
      recvBuf = Buffer.concat([recvBuf, chunk]);
      while (recvBuf.length >= 4) {
        const n = recvBuf.readUInt32BE(0);
        if (recvBuf.length < 4 + n) break;
        const payload = recvBuf.subarray(4, 4 + n);
        recvBuf = recvBuf.subarray(4 + n);

        const { field, inner } = decodeClientRequestVariant(payload);
        if (field === REQUEST_FIELDS.WATCH || field === REQUEST_FIELDS.UNWATCH) {
          requests.push({ field, ...decodeKeyNamespaceInner(inner) });
        }
        if (field === REQUEST_FIELDS.WATCH) {
          socket.write(frameClientResponse(RESPONSE_FIELDS.WATCHING, Buffer.alloc(0)));
        } else if (field === REQUEST_FIELDS.UNWATCH) {
          socket.write(frameClientResponse(RESPONSE_FIELDS.UNWATCHED, Buffer.alloc(0)));
        }
      }
    });
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address && typeof address !== 'string');

  const events = [];
  const client = new DittoTcpClient({
    host: '127.0.0.1',
    port: address.port,
    autoReconnect: true,
    baseBackoffMs: 1,
    maxBackoffMs: 1,
  });
  try {
    await client.connect();
    await client.watch('k', (value, version) => {
      events.push({ tenant: 'a', value: value?.toString('utf8'), version });
    }, 'tenant-a');
    await client.watch('k', (value, version) => {
      events.push({ tenant: 'b', value: value?.toString('utf8'), version });
    }, 'tenant-b');

    activeSocket.write(frameClientResponse(
      RESPONSE_FIELDS.WATCH_EVENT,
      encodeWatchEventInner('tenant-b::k', Buffer.from('value-b', 'utf8'), 2),
    ));
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.deepEqual(events, [{ tenant: 'b', value: 'value-b', version: 2 }]);

    activeSocket.destroy();
    await waitFor(() => connectionCount >= 2);
    await waitFor(() => requests.filter((req) => req.field === REQUEST_FIELDS.WATCH).length >= 4);

    const watchRequests = requests.filter((req) => req.field === REQUEST_FIELDS.WATCH);
    assert.deepEqual(watchRequests.slice(-2).map(({ key, namespace }) => ({ key, namespace })), [
      { key: 'k', namespace: 'tenant-a' },
      { key: 'k', namespace: 'tenant-b' },
    ]);

    await client.unwatch('k', 'tenant-b');
    const lastRequest = requests.at(-1);
    assert.deepEqual(
      { field: lastRequest.field, key: lastRequest.key, namespace: lastRequest.namespace },
      { field: REQUEST_FIELDS.UNWATCH, key: 'k', namespace: 'tenant-b' },
    );
  } finally {
    await client.close().catch(() => {});
    await new Promise((resolve) => server.close(resolve));
  }
});

async function waitFor(predicate) {
  const deadline = Date.now() + 1000;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.ok(predicate());
}
