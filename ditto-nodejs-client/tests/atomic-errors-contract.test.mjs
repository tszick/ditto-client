import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  normalizeAtomicUnsupportedError,
  unsupportedAtomicHttpError,
} from '../dist/client-internal.js';
import { DittoError } from '../dist/types.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const contractPath = path.resolve(__dirname, '../../contracts/atomic-errors.contract.json');
const contract = JSON.parse(fs.readFileSync(contractPath, 'utf8'));

test('atomic error normalization contract (node shared helpers)', async () => {
  for (const c of contract.cases) {
    if (c.operation === 'normalize_http_atomic_error') {
      const response = new Response(c.inputs.body, {
        status: c.inputs.status,
        headers: { 'content-type': 'application/json' },
      });
      const err = await unsupportedAtomicHttpError(response, c.inputs.operation_name);
      assertAtomicError(err, c.expect);
      continue;
    }

    if (c.operation === 'normalize_tcp_atomic_error') {
      const input = c.inputs.error_kind === 'ditto'
        ? new DittoError(c.inputs.error_code, c.inputs.error_message)
        : new Error(c.inputs.error_message);
      const err = normalizeAtomicUnsupportedError(input, c.inputs.operation_name);
      assertAtomicError(err, c.expect);
      continue;
    }

    assert.fail(`Unsupported contract operation: ${c.operation}`);
  }
});

function assertAtomicError(error, expect) {
  assert.ok(error instanceof DittoError || error instanceof Error);
  assert.equal(error.code ?? error.Code, expect.code);
  assert.match(error.message, new RegExp(escapeRegExp(expect.message_contains)));
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
