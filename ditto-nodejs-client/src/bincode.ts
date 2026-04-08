/**
 * Minimal bincode 1.x encoder/decoder for the Ditto wire protocol.
 *
 * Bincode 1.x default rules:
 *   - integers:       little-endian
 *   - enum variant:   u32 LE (declaration order index)
 *   - String/Bytes:   u64 LE length prefix + raw bytes
 *   - Option<T>:      u8 (0 = None, 1 = Some) + T if Some
 *   - bool:           u8
 *
 * Wire framing (added by tcp_server.rs / encode()):
 *   - 4-byte big-endian length prefix before the bincode payload
 */

import type { DittoErrorCode } from './types.js';

// ---------------------------------------------------------------------------
// ClientRequest encoding
// ---------------------------------------------------------------------------

/** Get { key, namespace } – variant 0 */
export function encodeGet(key: string, namespace?: string): Buffer {
  const keyBuf = Buffer.from(key, 'utf8');
  const nsSize = optionStringSize(namespace);
  const buf    = Buffer.allocUnsafe(4 + 8 + keyBuf.length + nsSize);
  let   off    = 0;
  buf.writeUInt32LE(0, off);           off += 4;  // variant: Get
  writeu64LE(buf, keyBuf.length, off); off += 8;
  keyBuf.copy(buf, off);               off += keyBuf.length;
  off = writeOptionString(buf, off, namespace);
  return frame(buf);
}

/** Set { key, value, ttl_secs, namespace } – variant 1 */
export function encodeSet(key: string, value: Buffer, ttlSecs?: number, namespace?: string): Buffer {
  const keyBuf = Buffer.from(key, 'utf8');
  const hasTtl = ttlSecs !== undefined && ttlSecs > 0;
  const size   = 4 + 8 + keyBuf.length + 8 + value.length + 1 + (hasTtl ? 8 : 0) + optionStringSize(namespace);
  const buf    = Buffer.allocUnsafe(size);
  let   off    = 0;

  buf.writeUInt32LE(1, off);           off += 4;  // variant: Set
  writeu64LE(buf, keyBuf.length, off); off += 8;
  keyBuf.copy(buf, off);               off += keyBuf.length;
  writeu64LE(buf, value.length, off);  off += 8;
  value.copy(buf, off);                off += value.length;
  buf.writeUInt8(hasTtl ? 1 : 0, off); off += 1;
  if (hasTtl) {
    writeu64LE(buf, ttlSecs!, off);    off += 8;
  }
  off = writeOptionString(buf, off, namespace);
  return frame(buf);
}

/** Delete { key, namespace } – variant 2 */
export function encodeDelete(key: string, namespace?: string): Buffer {
  const keyBuf = Buffer.from(key, 'utf8');
  const nsSize = optionStringSize(namespace);
  const buf    = Buffer.allocUnsafe(4 + 8 + keyBuf.length + nsSize);
  let   off    = 0;
  buf.writeUInt32LE(2, off);           off += 4;  // variant: Delete
  writeu64LE(buf, keyBuf.length, off); off += 8;
  keyBuf.copy(buf, off);               off += keyBuf.length;
  off = writeOptionString(buf, off, namespace);
  return frame(buf);
}

/** Ping – variant 3 */
export function encodePing(): Buffer {
  const buf = Buffer.allocUnsafe(4);
  buf.writeUInt32LE(3, 0);
  return frame(buf);
}

/** Watch { key, namespace } – variant 5 (DITTO-02) */
export function encodeWatch(key: string, namespace?: string): Buffer {
  const keyBuf = Buffer.from(key, 'utf8');
  const nsSize = optionStringSize(namespace);
  const buf    = Buffer.allocUnsafe(4 + 8 + keyBuf.length + nsSize);
  let   off    = 0;
  buf.writeUInt32LE(5, off);           off += 4;  // variant: Watch
  writeu64LE(buf, keyBuf.length, off); off += 8;
  keyBuf.copy(buf, off);               off += keyBuf.length;
  off = writeOptionString(buf, off, namespace);
  return frame(buf);
}

/** Unwatch { key, namespace } – variant 6 (DITTO-02) */
export function encodeUnwatch(key: string, namespace?: string): Buffer {
  const keyBuf = Buffer.from(key, 'utf8');
  const nsSize = optionStringSize(namespace);
  const buf    = Buffer.allocUnsafe(4 + 8 + keyBuf.length + nsSize);
  let   off    = 0;
  buf.writeUInt32LE(6, off);           off += 4;  // variant: Unwatch
  writeu64LE(buf, keyBuf.length, off); off += 8;
  keyBuf.copy(buf, off);               off += keyBuf.length;
  off = writeOptionString(buf, off, namespace);
  return frame(buf);
}

/** DeleteByPattern { pattern, namespace } – variant 7 */
export function encodeDeleteByPattern(pattern: string, namespace?: string): Buffer {
  const patternBuf = Buffer.from(pattern, 'utf8');
  const nsSize     = optionStringSize(namespace);
  const buf        = Buffer.allocUnsafe(4 + 8 + patternBuf.length + nsSize);
  let   off        = 0;
  buf.writeUInt32LE(7, off);               off += 4;  // variant: DeleteByPattern
  writeu64LE(buf, patternBuf.length, off); off += 8;
  patternBuf.copy(buf, off);               off += patternBuf.length;
  off = writeOptionString(buf, off, namespace);
  return frame(buf);
}

/** SetTtlByPattern { pattern, ttl_secs, namespace } – variant 8 */
export function encodeSetTtlByPattern(pattern: string, ttlSecs?: number, namespace?: string): Buffer {
  const patternBuf = Buffer.from(pattern, 'utf8');
  const hasTtl     = ttlSecs !== undefined && ttlSecs > 0;
  const size       = 4 + 8 + patternBuf.length + 1 + (hasTtl ? 8 : 0) + optionStringSize(namespace);
  const buf        = Buffer.allocUnsafe(size);
  let   off        = 0;
  buf.writeUInt32LE(8, off);               off += 4;  // variant: SetTtlByPattern
  writeu64LE(buf, patternBuf.length, off); off += 8;
  patternBuf.copy(buf, off);               off += patternBuf.length;
  buf.writeUInt8(hasTtl ? 1 : 0, off);     off += 1;
  if (hasTtl) {
    writeu64LE(buf, ttlSecs!, off);        off += 8;
  }
  off = writeOptionString(buf, off, namespace);
  return frame(buf);
}

/** Auth { token } – variant 4 */
export function encodeAuth(token: string): Buffer {
  const tokenBuf = Buffer.from(token, 'utf8');
  const buf      = Buffer.allocUnsafe(4 + 8 + tokenBuf.length);
  let   off      = 0;
  buf.writeUInt32LE(4, off);          off += 4;  // variant: Auth
  writeu64LE(buf, tokenBuf.length, off); off += 8;
  tokenBuf.copy(buf, off);
  return frame(buf);
}

// ---------------------------------------------------------------------------
// ClientResponse decoding
// ---------------------------------------------------------------------------

export type ClientResponse =
  | { type: 'Value';      key: string; value: Buffer; version: number }
  | { type: 'Ok';         version: number }
  | { type: 'Deleted' }
  | { type: 'NotFound' }
  | { type: 'Pong' }
  | { type: 'AuthOk' }
  | { type: 'Error';      code: DittoErrorCode; message: string }
  // DITTO-02
  | { type: 'Watching' }
  | { type: 'Unwatched' }
  | { type: 'WatchEvent'; key: string; value: Buffer | null; version: number }
  | { type: 'PatternDeleted'; deleted: number }
  | { type: 'PatternTtlUpdated'; updated: number };

const ERROR_CODE_NAMES: DittoErrorCode[] = [
  'NodeInactive',
  'NoQuorum',
  'KeyNotFound',
  'InternalError',
  'WriteTimeout',
  'ValueTooLarge',
  'KeyLimitReached',
  'RateLimited',
  'CircuitOpen',
  'AuthFailed',
];

export function decodeResponse(buf: Buffer): ClientResponse {
  let off     = 0;
  const variant = buf.readUInt32LE(off); off += 4;

  switch (variant) {
    case 0: { // Value { key, value, version }
      const keyLen  = readu64LE(buf, off); off += 8;
      const key     = buf.subarray(off, off + keyLen).toString('utf8'); off += keyLen;
      const valLen  = readu64LE(buf, off); off += 8;
      const value   = Buffer.from(buf.subarray(off, off + valLen)); off += valLen;
      const version = readu64LE(buf, off);
      return { type: 'Value', key, value, version };
    }
    case 1: { // Ok { version }
      const version = readu64LE(buf, off);
      return { type: 'Ok', version };
    }
    case 2: return { type: 'Deleted' };
    case 3: return { type: 'NotFound' };
    case 4: return { type: 'Pong' };
    case 5: return { type: 'AuthOk' };
    case 6: { // Error { code, message }
      const codeIdx = buf.readUInt32LE(off); off += 4;
      const msgLen  = readu64LE(buf, off); off += 8;
      const message = buf.subarray(off, off + msgLen).toString('utf8');
      const code    = ERROR_CODE_NAMES[codeIdx] ?? 'InternalError';
      return { type: 'Error', code, message };
    }
    // DITTO-02 variants
    case 7: return { type: 'Watching' };
    case 8: return { type: 'Unwatched' };
    case 9: { // WatchEvent { key, value: Option<Bytes>, version }
      const keyLen = readu64LE(buf, off); off += 8;
      const key    = buf.subarray(off, off + keyLen).toString('utf8'); off += keyLen;
      // Option<Bytes>: 1-byte discriminant (0=None, 1=Some) + u64 len + bytes if Some
      const hasValue = buf.readUInt8(off); off += 1;
      let value: Buffer | null = null;
      if (hasValue === 1) {
        const valLen = readu64LE(buf, off); off += 8;
        value = Buffer.from(buf.subarray(off, off + valLen));
      }
      const version = readu64LE(buf, off);
      return { type: 'WatchEvent', key, value, version };
    }
    case 10: { // PatternDeleted { deleted }
      const deleted = readu64LE(buf, off);
      return { type: 'PatternDeleted', deleted };
    }
    case 11: { // PatternTtlUpdated { updated }
      const updated = readu64LE(buf, off);
      return { type: 'PatternTtlUpdated', updated };
    }
    default:
      throw new Error(`Unknown ClientResponse variant: ${variant}`);
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Prepend 4-byte big-endian length prefix. */
function frame(payload: Buffer): Buffer {
  const len = Buffer.allocUnsafe(4);
  len.writeUInt32BE(payload.length, 0);
  return Buffer.concat([len, payload]);
}

/** Write a 53-bit-safe u64 as LE (JS can't handle true u64 precisely > 2^53). */
function writeu64LE(buf: Buffer, value: number, offset: number): void {
  buf.writeBigUInt64LE(BigInt(value), offset);
}

/** Read a u64 LE as a number (safe for lengths up to Number.MAX_SAFE_INTEGER). */
function readu64LE(buf: Buffer, offset: number): number {
  return Number(buf.readBigUInt64LE(offset));
}

function optionStringSize(namespace?: string): number {
  if (namespace === undefined || namespace === null || namespace.trim() === '') return 1;
  return 1 + 8 + Buffer.byteLength(namespace, 'utf8');
}

function writeOptionString(buf: Buffer, offset: number, namespace?: string): number {
  if (namespace === undefined || namespace === null || namespace.trim() === '') {
    buf.writeUInt8(0, offset);
    return offset + 1;
  }
  const nsBuf = Buffer.from(namespace, 'utf8');
  buf.writeUInt8(1, offset);
  offset += 1;
  writeu64LE(buf, nsBuf.length, offset);
  offset += 8;
  nsBuf.copy(buf, offset);
  return offset + nsBuf.length;
}
