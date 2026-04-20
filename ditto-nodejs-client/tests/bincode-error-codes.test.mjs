import test from 'node:test';
import assert from 'node:assert/strict';

import { decodeResponse } from '../dist/bincode.js';

function encodeErrorVariant(codeIdx, message = 'x') {
  const msg = Buffer.from(message, 'utf8');
  const payload = Buffer.allocUnsafe(4 + 4 + 8 + msg.length);
  let off = 0;
  payload.writeUInt32LE(6, off); off += 4; // ClientResponse::Error
  payload.writeUInt32LE(codeIdx, off); off += 4;
  payload.writeBigUInt64LE(BigInt(msg.length), off); off += 8;
  msg.copy(payload, off);
  return payload;
}

test('bincode error code mapping includes namespace quota and auth indices', () => {
  const quota = decodeResponse(encodeErrorVariant(9));
  assert.equal(quota.type, 'Error');
  assert.equal(quota.code, 'NamespaceQuotaExceeded');

  const auth = decodeResponse(encodeErrorVariant(10));
  assert.equal(auth.type, 'Error');
  assert.equal(auth.code, 'AuthFailed');
});

