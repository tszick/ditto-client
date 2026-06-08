/**
 * Protobuf wire encoder/decoder for the Ditto client TCP protocol.
 *
 * Source of truth: `ditto-protocol/proto/ditto.proto` (proto3, package
 * `ditto.protocol.v1`). This file is hand-rolled to avoid a codegen step;
 * field numbers below MUST stay in sync with `ditto.proto`.
 *
 * Wire framing (added by tcp_server.rs / encode()):
 *   - 4-byte big-endian payload-length prefix before each protobuf message
 *
 * Each outbound payload is an `Envelope` with `version = PROTOCOL_VERSION` and
 * the active `client_request` oneof variant. Each inbound payload is an
 * `Envelope` carrying a `client_response` variant.
 */

import type { DittoErrorCode } from './types.js';

const PROTOCOL_VERSION = 1;

// --- Field numbers -----------------------------------------------------------
// Envelope
const ENV_VERSION         = 1;
const ENV_CLIENT_REQUEST  = 2;
const ENV_CLIENT_RESPONSE = 3;

// ClientRequest.oneof request
const REQ_GET                 = 1;
const REQ_SET                 = 2;
const REQ_DELETE              = 3;
const REQ_PING                = 4;
const REQ_AUTH                = 5;
const REQ_WATCH               = 6;
const REQ_UNWATCH             = 7;
const REQ_DELETE_BY_PATTERN   = 8;
const REQ_SET_TTL_BY_PATTERN  = 9;
const REQ_SET_NX              = 10;
const REQ_INCR                = 11;

// ClientResponse.oneof response
const RESP_VALUE                = 1;
const RESP_OK                   = 2;
const RESP_DELETED              = 3;
const RESP_NOT_FOUND            = 4;
const RESP_PONG                 = 5;
const RESP_AUTH_OK              = 6;
const RESP_ERROR                = 7;
const RESP_WATCHING             = 8;
const RESP_UNWATCHED            = 9;
const RESP_WATCH_EVENT          = 10;
const RESP_PATTERN_DELETED      = 11;
const RESP_PATTERN_TTL_UPDATED  = 12;
const RESP_SET_NX              = 13;
const RESP_COUNTER             = 14;

// Wire types
const WT_VARINT = 0;
const WT_LD     = 2;

// Inner message field numbers (KeyNamespace, SetRequest, …)
const KN_KEY        = 1;
const KN_NAMESPACE  = 2;
const PN_PATTERN    = 1;
const PN_NAMESPACE  = 2;
const SR_KEY        = 1;
const SR_VALUE      = 2;
const SR_TTL_SECS   = 3;
const SR_NAMESPACE  = 4;
const STBP_PATTERN  = 1;
const STBP_TTL_SECS = 2;
const STBP_NAMESPACE = 3;
const INCR_KEY = 1;
const INCR_DELTA = 2;
const INCR_TTL_SECS_ON_CREATE = 3;
const INCR_NAMESPACE = 4;
const AUTH_TOKEN    = 1;
const VAL_KEY       = 1;
const VAL_VALUE     = 2;
const VAL_VERSION   = 3;
const VR_VERSION    = 1;
const SNX_CREATED   = 1;
const SNX_VERSION   = 2;
const CTR_VALUE     = 1;
const CTR_VERSION   = 2;
const ERR_CODE      = 1;
const ERR_MESSAGE   = 2;
const WE_KEY        = 1;
const WE_VALUE      = 2;
const WE_VERSION    = 3;
const COUNT_FIELD   = 1;
const OPT_VALUE     = 1; // OptionalString.value, OptionalBytes.value, OptionalUint64.value

// Maps ditto.proto enum ErrorCode index -> SDK string name.
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
  'NamespaceQuotaExceeded',
  'AuthFailed',
  'UnsupportedRequest',
  'TypeMismatch',
  'Overflow',
];

// ---------------------------------------------------------------------------
// Writer
// ---------------------------------------------------------------------------

class Writer {
  private chunks: Buffer[] = [];

  varint(v: number | bigint): void {
    let n = typeof v === 'bigint' ? v : BigInt(v);
    if (n < 0n) throw new Error('negative varint not supported');
    const out: number[] = [];
    while (n >= 0x80n) {
      out.push(Number(n & 0x7Fn) | 0x80);
      n >>= 7n;
    }
    out.push(Number(n));
    this.chunks.push(Buffer.from(out));
  }

  tag(field: number, wireType: number): void {
    this.varint((field << 3) | wireType);
  }

  /** Encode a varint-typed field (skip if value is the proto3 default 0). */
  uint64Field(field: number, value: number | bigint): void {
    const isZero = typeof value === 'bigint' ? value === 0n : value === 0;
    if (isZero) return;
    this.tag(field, WT_VARINT);
    this.varint(value);
  }

  int64Field(field: number, value: number | bigint): void {
    const bigintValue = typeof value === 'bigint' ? value : BigInt(value);
    this.tag(field, WT_VARINT);
    this.varint(BigInt.asUintN(64, bigintValue));
  }

  /** Encode an enum/uint32-typed field (skip if value is 0). */
  enumField(field: number, value: number): void {
    if (value === 0) return;
    this.tag(field, WT_VARINT);
    this.varint(value);
  }

  /** Encode a length-delimited field (skip if payload is empty). */
  ldField(field: number, payload: Buffer): void {
    if (payload.length === 0) return;
    this.tag(field, WT_LD);
    this.varint(payload.length);
    this.chunks.push(payload);
  }

  /** Encode a string field. Empty strings are omitted (proto3 default). */
  stringField(field: number, value: string): void {
    if (value.length === 0) return;
    const buf = Buffer.from(value, 'utf8');
    this.tag(field, WT_LD);
    this.varint(buf.length);
    this.chunks.push(buf);
  }

  /** Encode a bytes field. Empty bytes are omitted (proto3 default). */
  bytesField(field: number, value: Buffer): void {
    if (value.length === 0) return;
    this.tag(field, WT_LD);
    this.varint(value.length);
    this.chunks.push(value);
  }

  /** Always emit a length-delimited field (used for sub-messages whose
   *  presence — even empty — carries oneof selection). */
  ldFieldAlways(field: number, payload: Buffer): void {
    this.tag(field, WT_LD);
    this.varint(payload.length);
    if (payload.length > 0) this.chunks.push(payload);
  }

  finish(): Buffer {
    return Buffer.concat(this.chunks);
  }
}

// ---------------------------------------------------------------------------
// Reader
// ---------------------------------------------------------------------------

class Reader {
  constructor(
    private readonly buf: Buffer,
    private off: number = 0,
    private readonly end: number = buf.length,
  ) {}

  remaining(): number {
    return this.end - this.off;
  }

  readVarint(): bigint {
    let result = 0n;
    let shift = 0n;
    while (this.off < this.end) {
      const b = this.buf.readUInt8(this.off++);
      result |= BigInt(b & 0x7F) << shift;
      if ((b & 0x80) === 0) return result;
      shift += 7n;
      if (shift > 70n) throw new Error('varint too long');
    }
    throw new Error('truncated varint');
  }

  readVarintAsNumber(): number {
    const v = this.readVarint();
    if (v > BigInt(Number.MAX_SAFE_INTEGER)) {
      throw new Error(`varint ${v} exceeds Number.MAX_SAFE_INTEGER`);
    }
    return Number(v);
  }

  readInt64(): bigint {
    return BigInt.asIntN(64, this.readVarint());
  }

  readTag(): { field: number; wire: number } {
    const t = this.readVarintAsNumber();
    return { field: t >>> 3, wire: t & 0x7 };
  }

  readLD(): Buffer {
    const len = this.readVarintAsNumber();
    if (this.off + len > this.end) throw new Error('truncated length-delimited field');
    const out = this.buf.subarray(this.off, this.off + len);
    this.off += len;
    return out;
  }

  /** Skip a field whose tag has already been read. */
  skip(wire: number): void {
    switch (wire) {
      case WT_VARINT: this.readVarint(); return;
      case WT_LD:     this.readLD(); return;
      case 1: this.off += 8; return; // fixed64
      case 5: this.off += 4; return; // fixed32
      default: throw new Error(`unsupported wire type: ${wire}`);
    }
  }
}

// ---------------------------------------------------------------------------
// Inner message encoders (used inside ClientRequest oneof variants)
// ---------------------------------------------------------------------------

function encodeOptionalString(value: string): Buffer {
  // OptionalString { string value = 1; }
  const w = new Writer();
  w.stringField(OPT_VALUE, value);
  return w.finish();
}

function encodeOptionalUint64(value: number | bigint): Buffer {
  const w = new Writer();
  w.uint64Field(OPT_VALUE, value);
  return w.finish();
}

function hasNamespace(ns?: string): ns is string {
  return ns !== undefined && ns !== null && ns.trim() !== '';
}

function encodeKeyNamespace(key: string, namespace?: string): Buffer {
  const w = new Writer();
  w.stringField(KN_KEY, key);
  if (hasNamespace(namespace)) {
    w.ldField(KN_NAMESPACE, encodeOptionalString(namespace));
  }
  return w.finish();
}

function encodePatternNamespace(pattern: string, namespace?: string): Buffer {
  const w = new Writer();
  w.stringField(PN_PATTERN, pattern);
  if (hasNamespace(namespace)) {
    w.ldField(PN_NAMESPACE, encodeOptionalString(namespace));
  }
  return w.finish();
}

function encodeSetRequest(
  key: string,
  value: Buffer,
  ttlSecs?: number,
  namespace?: string,
): Buffer {
  const w = new Writer();
  w.stringField(SR_KEY, key);
  w.bytesField(SR_VALUE, value);
  if (ttlSecs !== undefined && ttlSecs > 0) {
    w.ldField(SR_TTL_SECS, encodeOptionalUint64(ttlSecs));
  }
  if (hasNamespace(namespace)) {
    w.ldField(SR_NAMESPACE, encodeOptionalString(namespace));
  }
  return w.finish();
}

function encodeSetTtlByPatternRequest(
  pattern: string,
  ttlSecs?: number,
  namespace?: string,
): Buffer {
  const w = new Writer();
  w.stringField(STBP_PATTERN, pattern);
  if (ttlSecs !== undefined && ttlSecs > 0) {
    w.ldField(STBP_TTL_SECS, encodeOptionalUint64(ttlSecs));
  }
  if (hasNamespace(namespace)) {
    w.ldField(STBP_NAMESPACE, encodeOptionalString(namespace));
  }
  return w.finish();
}

function encodeIncrRequest(
  key: string,
  delta?: bigint | number,
  ttlSecsOnCreate?: number,
  namespace?: string,
): Buffer {
  const w = new Writer();
  w.stringField(INCR_KEY, key);
  if (delta !== undefined) {
    w.int64Field(INCR_DELTA, delta);
  }
  if (ttlSecsOnCreate !== undefined && ttlSecsOnCreate > 0) {
    w.ldField(INCR_TTL_SECS_ON_CREATE, encodeOptionalUint64(ttlSecsOnCreate));
  }
  if (hasNamespace(namespace)) {
    w.ldField(INCR_NAMESPACE, encodeOptionalString(namespace));
  }
  return w.finish();
}

function encodeAuthRequest(token: string): Buffer {
  const w = new Writer();
  w.stringField(AUTH_TOKEN, token);
  return w.finish();
}

// ---------------------------------------------------------------------------
// ClientRequest oneof + Envelope wrapping
// ---------------------------------------------------------------------------

/** Wrap an inner ClientRequest oneof variant payload in Envelope + length frame. */
function wrapClientRequest(variantField: number, inner: Buffer): Buffer {
  // ClientRequest { oneof request { ... } }
  const reqWriter = new Writer();
  reqWriter.ldFieldAlways(variantField, inner);
  const requestBytes = reqWriter.finish();

  // Envelope { uint32 version = 1; oneof payload { ClientRequest client_request = 2; ... } }
  const envWriter = new Writer();
  envWriter.enumField(ENV_VERSION, PROTOCOL_VERSION);
  envWriter.ldFieldAlways(ENV_CLIENT_REQUEST, requestBytes);
  const envelopeBytes = envWriter.finish();

  // 4-byte BE length prefix.
  const out = Buffer.allocUnsafe(4 + envelopeBytes.length);
  out.writeUInt32BE(envelopeBytes.length, 0);
  envelopeBytes.copy(out, 4);
  return out;
}

// --- Public encoders --------------------------------------------------------

export function encodeGet(key: string, namespace?: string): Buffer {
  return wrapClientRequest(REQ_GET, encodeKeyNamespace(key, namespace));
}

export function encodeSet(
  key: string,
  value: Buffer,
  ttlSecs?: number,
  namespace?: string,
): Buffer {
  return wrapClientRequest(REQ_SET, encodeSetRequest(key, value, ttlSecs, namespace));
}

export function encodeDelete(key: string, namespace?: string): Buffer {
  return wrapClientRequest(REQ_DELETE, encodeKeyNamespace(key, namespace));
}

export function encodePing(): Buffer {
  return wrapClientRequest(REQ_PING, Buffer.alloc(0));
}

export function encodeAuth(token: string): Buffer {
  return wrapClientRequest(REQ_AUTH, encodeAuthRequest(token));
}

export function encodeWatch(key: string, namespace?: string): Buffer {
  return wrapClientRequest(REQ_WATCH, encodeKeyNamespace(key, namespace));
}

export function encodeUnwatch(key: string, namespace?: string): Buffer {
  return wrapClientRequest(REQ_UNWATCH, encodeKeyNamespace(key, namespace));
}

export function encodeDeleteByPattern(pattern: string, namespace?: string): Buffer {
  return wrapClientRequest(REQ_DELETE_BY_PATTERN, encodePatternNamespace(pattern, namespace));
}

export function encodeSetTtlByPattern(
  pattern: string,
  ttlSecs?: number,
  namespace?: string,
): Buffer {
  return wrapClientRequest(
    REQ_SET_TTL_BY_PATTERN,
    encodeSetTtlByPatternRequest(pattern, ttlSecs, namespace),
  );
}

export function encodeSetNX(
  key: string,
  value: Buffer,
  ttlSecs?: number,
  namespace?: string,
): Buffer {
  return wrapClientRequest(REQ_SET_NX, encodeSetRequest(key, value, ttlSecs, namespace));
}

export function encodeIncr(
  key: string,
  delta?: bigint | number,
  ttlSecsOnCreate?: number,
  namespace?: string,
): Buffer {
  return wrapClientRequest(
    REQ_INCR,
    encodeIncrRequest(key, delta, ttlSecsOnCreate, namespace),
  );
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
  | { type: 'Watching' }
  | { type: 'Unwatched' }
  | { type: 'WatchEvent'; key: string; value: Buffer | null; version: number }
  | { type: 'PatternDeleted';    deleted: number }
  | { type: 'PatternTtlUpdated'; updated: number }
  | { type: 'SetNx'; created: boolean; version: bigint }
  | { type: 'Counter'; value: bigint; version: bigint };

export function decodeResponse(buf: Buffer): ClientResponse {
  // Top-level: Envelope.
  const env = new Reader(buf);
  let responseBytes: Buffer | null = null;
  let version = 0;

  while (env.remaining() > 0) {
    const { field, wire } = env.readTag();
    if (field === ENV_VERSION && wire === WT_VARINT) {
      version = env.readVarintAsNumber();
    } else if (field === ENV_CLIENT_RESPONSE && wire === WT_LD) {
      responseBytes = env.readLD();
    } else {
      env.skip(wire);
    }
  }

  if (version !== 0 && version !== PROTOCOL_VERSION) {
    throw new Error(`unsupported protocol version: ${version}`);
  }
  if (responseBytes === null) {
    throw new Error('Envelope is missing client_response payload');
  }

  // Inner: ClientResponse — exactly one oneof field is set.
  const r = new Reader(responseBytes);
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (wire !== WT_LD) {
      r.skip(wire);
      continue;
    }
    const inner = r.readLD();
    switch (field) {
      case RESP_VALUE:               return decodeValueResponse(inner);
      case RESP_OK:                  return decodeOkResponse(inner);
      case RESP_DELETED:             return { type: 'Deleted' };
      case RESP_NOT_FOUND:           return { type: 'NotFound' };
      case RESP_PONG:                return { type: 'Pong' };
      case RESP_AUTH_OK:             return { type: 'AuthOk' };
      case RESP_ERROR:               return decodeErrorResponse(inner);
      case RESP_WATCHING:            return { type: 'Watching' };
      case RESP_UNWATCHED:           return { type: 'Unwatched' };
      case RESP_WATCH_EVENT:         return decodeWatchEvent(inner);
      case RESP_PATTERN_DELETED:     return { type: 'PatternDeleted',    deleted: decodeCount(inner) };
      case RESP_PATTERN_TTL_UPDATED: return { type: 'PatternTtlUpdated', updated: decodeCount(inner) };
      case RESP_SET_NX:              return decodeSetNxResponse(inner);
      case RESP_COUNTER:             return decodeCounterResponse(inner);
      default: continue;
    }
  }
  throw new Error('ClientResponse oneof has no active field');
}

function decodeValueResponse(buf: Buffer): ClientResponse {
  const r = new Reader(buf);
  let key = '', value = Buffer.alloc(0), version = 0;
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === VAL_KEY && wire === WT_LD) {
      key = r.readLD().toString('utf8');
    } else if (field === VAL_VALUE && wire === WT_LD) {
      value = Buffer.from(r.readLD());
    } else if (field === VAL_VERSION && wire === WT_VARINT) {
      version = r.readVarintAsNumber();
    } else {
      r.skip(wire);
    }
  }
  return { type: 'Value', key, value, version };
}

function decodeOkResponse(buf: Buffer): ClientResponse {
  const r = new Reader(buf);
  let version = 0;
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === VR_VERSION && wire === WT_VARINT) {
      version = r.readVarintAsNumber();
    } else {
      r.skip(wire);
    }
  }
  return { type: 'Ok', version };
}

function decodeErrorResponse(buf: Buffer): ClientResponse {
  const r = new Reader(buf);
  let codeIdx = 0;
  let message = '';
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === ERR_CODE && wire === WT_VARINT) {
      codeIdx = r.readVarintAsNumber();
    } else if (field === ERR_MESSAGE && wire === WT_LD) {
      message = r.readLD().toString('utf8');
    } else {
      r.skip(wire);
    }
  }
  const code = ERROR_CODE_NAMES[codeIdx] ?? 'InternalError';
  return { type: 'Error', code, message };
}

function decodeSetNxResponse(buf: Buffer): ClientResponse {
  const r = new Reader(buf);
  let created = false;
  let version = 0n;
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === SNX_CREATED && wire === WT_VARINT) {
      created = r.readVarint() !== 0n;
    } else if (field === SNX_VERSION && wire === WT_VARINT) {
      version = r.readVarint();
    } else {
      r.skip(wire);
    }
  }
  return { type: 'SetNx', created, version };
}

function decodeCounterResponse(buf: Buffer): ClientResponse {
  const r = new Reader(buf);
  let value = 0n;
  let version = 0n;
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === CTR_VALUE && wire === WT_VARINT) {
      value = r.readInt64();
    } else if (field === CTR_VERSION && wire === WT_VARINT) {
      version = r.readVarint();
    } else {
      r.skip(wire);
    }
  }
  return { type: 'Counter', value, version };
}

function decodeWatchEvent(buf: Buffer): ClientResponse {
  const r = new Reader(buf);
  let key = '';
  let value: Buffer | null = null;
  let version = 0;
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === WE_KEY && wire === WT_LD) {
      key = r.readLD().toString('utf8');
    } else if (field === WE_VALUE && wire === WT_LD) {
      // OptionalBytes { bytes value = 1; } — empty inner means delete-event payload.
      const optBuf = r.readLD();
      value = decodeOptionalBytes(optBuf);
    } else if (field === WE_VERSION && wire === WT_VARINT) {
      version = r.readVarintAsNumber();
    } else {
      r.skip(wire);
    }
  }
  return { type: 'WatchEvent', key, value, version };
}

function decodeOptionalBytes(buf: Buffer): Buffer {
  // Empty OptionalBytes still represents a present-but-empty value; the
  // server only emits this field at all when the value is Some(_).
  const r = new Reader(buf);
  let out = Buffer.alloc(0);
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === OPT_VALUE && wire === WT_LD) {
      out = Buffer.from(r.readLD());
    } else {
      r.skip(wire);
    }
  }
  return out;
}

function decodeCount(buf: Buffer): number {
  const r = new Reader(buf);
  let count = 0;
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === COUNT_FIELD && wire === WT_VARINT) {
      count = r.readVarintAsNumber();
    } else {
      r.skip(wire);
    }
  }
  return count;
}

// ---------------------------------------------------------------------------
// Test helpers (re-exported for unit tests that need to forge server frames).
// ---------------------------------------------------------------------------

/** Wrap an already-encoded ClientResponse (oneof variant + inner) in an
 *  Envelope and a 4-byte BE length frame. Exposed for tests. */
export function frameClientResponse(variantField: number, inner: Buffer): Buffer {
  const respWriter = new Writer();
  respWriter.ldFieldAlways(variantField, inner);
  const responseBytes = respWriter.finish();

  const envWriter = new Writer();
  envWriter.enumField(ENV_VERSION, PROTOCOL_VERSION);
  envWriter.ldFieldAlways(ENV_CLIENT_RESPONSE, responseBytes);
  const envelopeBytes = envWriter.finish();

  const out = Buffer.allocUnsafe(4 + envelopeBytes.length);
  out.writeUInt32BE(envelopeBytes.length, 0);
  envelopeBytes.copy(out, 4);
  return out;
}

/** Build an `ErrorResponse` inner-message buffer for tests. */
export function encodeErrorResponseInner(codeIdx: number, message: string): Buffer {
  const w = new Writer();
  w.enumField(ERR_CODE, codeIdx);
  w.stringField(ERR_MESSAGE, message);
  return w.finish();
}

/** Build a `VersionResponse` (used inside `Ok`) inner-message buffer. */
export function encodeVersionResponseInner(version: number): Buffer {
  const w = new Writer();
  w.uint64Field(VR_VERSION, version);
  return w.finish();
}

export function encodeSetNxResponseInner(created: boolean, version: number | bigint): Buffer {
  const w = new Writer();
  w.uint64Field(SNX_CREATED, created ? 1 : 0);
  w.uint64Field(SNX_VERSION, version);
  return w.finish();
}

export function encodeCounterResponseInner(value: number | bigint, version: number | bigint): Buffer {
  const w = new Writer();
  w.int64Field(CTR_VALUE, value);
  w.uint64Field(CTR_VERSION, version);
  return w.finish();
}

/** Build a `WatchEvent` inner-message buffer with a present value. */
export function encodeWatchEventInner(key: string, value: Buffer, version: number): Buffer {
  const w = new Writer();
  w.stringField(WE_KEY, key);
  // OptionalBytes { bytes value = 1; }
  const optBytes = new Writer();
  optBytes.bytesField(OPT_VALUE, value);
  // OptionalBytes is always emitted when value is Some(_) — even if value is
  // empty bytes — so use ldFieldAlways here.
  w.ldFieldAlways(WE_VALUE, optBytes.finish());
  w.uint64Field(WE_VERSION, version);
  return w.finish();
}

/**
 * Test helper: parse an Envelope payload (no length prefix) and return the
 * active ClientRequest oneof field number plus its inner buffer.
 * Throws if the payload is not a ClientRequest envelope.
 */
export function decodeClientRequestVariant(buf: Buffer): { field: number; inner: Buffer } {
  const env = new Reader(buf);
  let requestBytes: Buffer | null = null;
  while (env.remaining() > 0) {
    const { field, wire } = env.readTag();
    if (field === ENV_CLIENT_REQUEST && wire === WT_LD) {
      requestBytes = env.readLD();
    } else {
      env.skip(wire);
    }
  }
  if (requestBytes === null) throw new Error('Envelope is missing client_request payload');

  const r = new Reader(requestBytes);
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (wire !== WT_LD) { r.skip(wire); continue; }
    return { field, inner: r.readLD() };
  }
  throw new Error('ClientRequest oneof has no active field');
}

/** Test helper: decode KeyNamespace-like request payloads. */
export function decodeKeyNamespaceInner(buf: Buffer): { key: string; namespace?: string } {
  const r = new Reader(buf);
  let key = '';
  let namespace: string | undefined;
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === KN_KEY && wire === WT_LD) {
      key = r.readLD().toString('utf8');
    } else if (field === KN_NAMESPACE && wire === WT_LD) {
      namespace = decodeOptionalString(r.readLD());
    } else {
      r.skip(wire);
    }
  }
  return namespace === undefined ? { key } : { key, namespace };
}

function decodeOptionalString(buf: Buffer): string | undefined {
  const r = new Reader(buf);
  while (r.remaining() > 0) {
    const { field, wire } = r.readTag();
    if (field === OPT_VALUE && wire === WT_LD) {
      return r.readLD().toString('utf8');
    }
    r.skip(wire);
  }
  return undefined;
}

/** Re-export request-variant field numbers for tests. */
export const REQUEST_FIELDS = {
  GET: REQ_GET,
  SET: REQ_SET,
  DELETE: REQ_DELETE,
  PING: REQ_PING,
  AUTH: REQ_AUTH,
  WATCH: REQ_WATCH,
  UNWATCH: REQ_UNWATCH,
  DELETE_BY_PATTERN: REQ_DELETE_BY_PATTERN,
  SET_TTL_BY_PATTERN: REQ_SET_TTL_BY_PATTERN,
  SET_NX: REQ_SET_NX,
  INCR: REQ_INCR,
} as const;

/** Re-export response-variant field numbers for tests that forge frames. */
export const RESPONSE_FIELDS = {
  VALUE: RESP_VALUE,
  OK: RESP_OK,
  DELETED: RESP_DELETED,
  NOT_FOUND: RESP_NOT_FOUND,
  PONG: RESP_PONG,
  AUTH_OK: RESP_AUTH_OK,
  ERROR: RESP_ERROR,
  WATCHING: RESP_WATCHING,
  UNWATCHED: RESP_UNWATCHED,
  WATCH_EVENT: RESP_WATCH_EVENT,
  PATTERN_DELETED: RESP_PATTERN_DELETED,
  PATTERN_TTL_UPDATED: RESP_PATTERN_TTL_UPDATED,
  SET_NX: RESP_SET_NX,
  COUNTER: RESP_COUNTER,
} as const;
