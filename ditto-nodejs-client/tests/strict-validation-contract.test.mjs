import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  normalizeNamespace,
  validateCoreInputs,
  validatePatternInputs,
} from '../dist/client-internal.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const contractPath = path.resolve(__dirname, '../../contracts/strict-validation.contract.json');
const contract = JSON.parse(fs.readFileSync(contractPath, 'utf8'));

test('strict validation contract (node shared validation helpers)', () => {
  for (const c of contract.cases) {
    const kind = c.inputs.kind;
    const strictMode = c.inputs.strict_mode;
    const namespace = c.inputs.namespace ?? undefined;

    if (kind === 'core') {
      if (c.expect.valid) {
        assert.doesNotThrow(() => validateCoreInputs(strictMode, c.inputs.op, c.inputs.key, namespace));
      } else {
        assert.throws(
          () => validateCoreInputs(strictMode, c.inputs.op, c.inputs.key, namespace),
          new RegExp(escapeRegExp(c.expect.error_contains)),
        );
      }
      continue;
    }

    if (kind === 'pattern') {
      if (c.expect.valid) {
        assert.doesNotThrow(() => validatePatternInputs(strictMode, c.inputs.op, c.inputs.pattern, namespace));
      } else {
        assert.throws(
          () => validatePatternInputs(strictMode, c.inputs.op, c.inputs.pattern, namespace),
          new RegExp(escapeRegExp(c.expect.error_contains)),
        );
      }
      continue;
    }

    if (kind === 'normalize_namespace') {
      if (strictMode && c.expect.error_contains) {
        assert.throws(
          () => validateCoreInputs(true, 'get', 'alpha:key', namespace),
          new RegExp(escapeRegExp(c.expect.error_contains)),
        );
        continue;
      }

      assert.equal(normalizeNamespace(namespace), c.expect.normalized ?? undefined);
      continue;
    }

    assert.fail(`Unsupported contract kind: ${kind}`);
  }
});

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
