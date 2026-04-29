import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { Buffer } from 'node:buffer';

import { DittoTcpClient } from '../dist/index.js';
import {
  REQUEST_FIELDS,
  RESPONSE_FIELDS,
  decodeClientRequestVariant,
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
