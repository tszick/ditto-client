import test from 'node:test';
import assert from 'node:assert/strict';

import {
  decodeResponse,
  encodeErrorResponseInner,
  frameClientResponse,
  RESPONSE_FIELDS,
} from '../dist/wire.js';

/** Build a server-style framed Envelope { ClientResponse { Error { code, message } } }
 *  and decode it through the SDK. */
function decodeServerError(codeIdx, message = 'x') {
  const inner = encodeErrorResponseInner(codeIdx, message);
  const framed = frameClientResponse(RESPONSE_FIELDS.ERROR, inner);
  // frameClientResponse prepends a 4-byte BE length; decodeResponse expects
  // the inner Envelope payload, so strip the length prefix here.
  const payload = framed.subarray(4);
  return decodeResponse(payload);
}

test('error code mapping covers namespace quota and auth indices', () => {
  const quota = decodeServerError(9);
  assert.equal(quota.type, 'Error');
  assert.equal(quota.code, 'NamespaceQuotaExceeded');

  const auth = decodeServerError(10);
  assert.equal(auth.type, 'Error');
  assert.equal(auth.code, 'AuthFailed');
});

test('error code mapping falls back to InternalError for unknown indices', () => {
  const unknown = decodeServerError(99, 'mystery');
  assert.equal(unknown.type, 'Error');
  assert.equal(unknown.code, 'InternalError');
  assert.equal(unknown.message, 'mystery');
});
