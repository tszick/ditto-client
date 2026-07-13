import { DittoError } from './types.js';

const STRICT_TOKEN_RE = /^[A-Za-z0-9._:-]+$/;
const STRICT_PATTERN_RE = /^[A-Za-z0-9._:\-*]+$/;

export type StrictCoreOperation =
  | 'delete'
  | 'get'
  | 'incr'
  | 'set'
  | 'setNX'
  | 'unwatch'
  | 'watch';

export type StrictPatternOperation = 'deleteByPattern' | 'setTtlByPattern';

export function validateCoreInputs(
  strictMode: boolean,
  op: StrictCoreOperation,
  key: string,
  namespace?: string,
): void {
  if (!strictMode) return;
  const keyTrimmed = key.trim();
  if (keyTrimmed.length === 0) {
    throw new Error(`Invalid ${op} request: key must not be empty.`);
  }
  if (!STRICT_TOKEN_RE.test(key)) {
    throw new Error(
      `Invalid ${op} request: key contains unsupported characters. Allowed: [A-Za-z0-9._:-]`,
    );
  }
  validateNamespace(op, namespace);
}

export function validatePatternInputs(
  strictMode: boolean,
  op: StrictPatternOperation,
  pattern: string,
  namespace?: string,
): void {
  if (!strictMode) return;
  const patternTrimmed = pattern.trim();
  if (patternTrimmed.length === 0) {
    throw new Error(`Invalid ${op} request: pattern must not be empty.`);
  }
  if (!STRICT_PATTERN_RE.test(patternTrimmed)) {
    throw new Error(
      `Invalid ${op} request: pattern contains unsupported characters. Allowed: [A-Za-z0-9._:-*]`,
    );
  }
  validateNamespace(op, namespace);
}

export function normalizeNamespace(namespace?: string): string | undefined {
  const trimmed = namespace?.trim();
  return trimmed && trimmed.length > 0 ? trimmed : undefined;
}

export function watchCallbackKey(key: string, namespace?: string): string {
  return namespace ? `${namespace}::${key}` : key;
}

export async function unsupportedAtomicHttpError(
  resp: Response,
  operation: 'SET_NX' | 'INCR',
): Promise<Error> {
  try {
    const body = await resp.json() as { error?: string; message?: string };
    if (body.error === 'UnsupportedRequest') {
      return new DittoError('UnsupportedRequest', body.message ?? 'UnsupportedRequest');
    }
  } catch {
    // fall through to normalized message below
  }
  return unsupportedAtomicOperationError(operation);
}

export function normalizeAtomicUnsupportedError(
  error: unknown,
  operation: 'SET_NX' | 'INCR',
): Error {
  if (error instanceof DittoError) {
    return error;
  }
  const message = error instanceof Error ? error.message : String(error);
  const normalized = message.toLowerCase();
  if (
    normalized.includes('unsupported')
    || normalized.includes('protocol')
    || normalized.includes('decode')
    || normalized.includes('missing client_response')
    || normalized.includes('oneof has no active field')
    || normalized.includes('unexpected response')
    || normalized.includes('socket hang up')
    || normalized.includes('econnreset')
    || normalized.includes('unexpected eof')
  ) {
    return unsupportedAtomicOperationError(operation);
  }
  return error instanceof Error ? error : new Error(message);
}

function unsupportedAtomicOperationError(operation: 'SET_NX' | 'INCR'): DittoError {
  return new DittoError(
    'UnsupportedRequest',
    `UnsupportedRequest: server does not support ${operation}. Upgrade dittod to a version with atomic primitives.`,
  );
}

function validateNamespace(op: StrictCoreOperation | StrictPatternOperation, namespace?: string): void {
  if (namespace === undefined) return;
  const nsTrimmed = namespace.trim();
  if (nsTrimmed.length === 0) {
    throw new Error(`Invalid ${op} request: namespace must not be blank when provided.`);
  }
  if (nsTrimmed.includes('::')) {
    throw new Error(`Invalid ${op} request: namespace must not contain '::'.`);
  }
  if (!STRICT_TOKEN_RE.test(nsTrimmed)) {
    throw new Error(
      `Invalid ${op} request: namespace contains unsupported characters. Allowed: [A-Za-z0-9._:-]`,
    );
  }
}
