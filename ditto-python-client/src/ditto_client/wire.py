"""
Protobuf wire encoder/decoder for the Ditto client TCP protocol.

Source of truth: ``ditto-protocol/proto/ditto.proto`` (proto3, package
``ditto.protocol.v1``). This module is hand-rolled to avoid pulling in a
``protoc`` codegen step; the field numbers below MUST stay in sync with
``ditto.proto``.

Wire framing (added by tcp_server.rs):
  - 4-byte big-endian payload-length prefix before each protobuf Envelope.

Each outbound payload is an Envelope { version=1, client_request: <variant> }
and each inbound payload is an Envelope { client_response: <variant> }.
"""

from __future__ import annotations

import struct
from typing import NamedTuple

from .types import DittoErrorCode

PROTOCOL_VERSION = 1

# --- Envelope field numbers --------------------------------------------------
_ENV_VERSION         = 1
_ENV_CLIENT_REQUEST  = 2
_ENV_CLIENT_RESPONSE = 3

# --- ClientRequest oneof field numbers --------------------------------------
_REQ_GET                = 1
_REQ_SET                = 2
_REQ_DELETE             = 3
_REQ_PING               = 4
_REQ_AUTH               = 5
_REQ_WATCH              = 6
_REQ_UNWATCH            = 7
_REQ_DELETE_BY_PATTERN  = 8
_REQ_SET_TTL_BY_PATTERN = 9
_REQ_SET_NX             = 10
_REQ_INCR               = 11

# --- ClientResponse oneof field numbers -------------------------------------
_RESP_VALUE               = 1
_RESP_OK                  = 2
_RESP_DELETED             = 3
_RESP_NOT_FOUND           = 4
_RESP_PONG                = 5
_RESP_AUTH_OK             = 6
_RESP_ERROR               = 7
_RESP_WATCHING            = 8
_RESP_UNWATCHED           = 9
_RESP_WATCH_EVENT         = 10
_RESP_PATTERN_DELETED     = 11
_RESP_PATTERN_TTL_UPDATED = 12
_RESP_SET_NX              = 13
_RESP_COUNTER             = 14

# Wire types
_WT_VARINT = 0
_WT_LD     = 2

# Inner-message field numbers
_KN_KEY        = 1
_KN_NAMESPACE  = 2
_PN_PATTERN    = 1
_PN_NAMESPACE  = 2
_SR_KEY        = 1
_SR_VALUE      = 2
_SR_TTL_SECS   = 3
_SR_NAMESPACE  = 4
_STBP_PATTERN  = 1
_STBP_TTL_SECS = 2
_STBP_NAMESPACE = 3
_INCR_KEY                = 1
_INCR_DELTA              = 2
_INCR_TTL_SECS_ON_CREATE = 3
_INCR_NAMESPACE          = 4
_SNX_CREATED   = 1
_SNX_VERSION   = 2
_CTR_VALUE     = 1
_CTR_VERSION   = 2
_AUTH_TOKEN    = 1
_VAL_KEY       = 1
_VAL_VALUE     = 2
_VAL_VERSION   = 3
_VR_VERSION    = 1
_ERR_CODE      = 1
_ERR_MESSAGE   = 2
_WE_KEY        = 1
_WE_VALUE      = 2
_WE_VERSION    = 3
_COUNT_FIELD   = 1
_OPT_VALUE     = 1  # OptionalString.value / OptionalBytes.value / OptionalUint64.value

_ERROR_CODE_NAMES = [
    DittoErrorCode.NODE_INACTIVE,
    DittoErrorCode.NO_QUORUM,
    DittoErrorCode.KEY_NOT_FOUND,
    DittoErrorCode.INTERNAL_ERROR,
    DittoErrorCode.WRITE_TIMEOUT,
    DittoErrorCode.VALUE_TOO_LARGE,
    DittoErrorCode.KEY_LIMIT_REACHED,
    DittoErrorCode.RATE_LIMITED,
    DittoErrorCode.CIRCUIT_OPEN,
    DittoErrorCode.NAMESPACE_QUOTA_EXCEEDED,
    DittoErrorCode.AUTH_FAILED,
    DittoErrorCode.UNSUPPORTED_REQUEST,
    DittoErrorCode.TYPE_MISMATCH,
    DittoErrorCode.OVERFLOW,
]


# ---------------------------------------------------------------------------
# Low-level varint + length-delimited encoding helpers
# ---------------------------------------------------------------------------

def _encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("negative varint not supported")
    out = bytearray()
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def _encode_tag(field: int, wire: int) -> bytes:
    return _encode_varint((field << 3) | wire)


def _ld_field(field: int, payload: bytes) -> bytes:
    """Length-delimited field; emit only when payload is non-empty."""
    if not payload:
        return b""
    return _encode_tag(field, _WT_LD) + _encode_varint(len(payload)) + payload


def _ld_field_always(field: int, payload: bytes) -> bytes:
    """Always emit a length-delimited field — used to mark oneof presence."""
    return _encode_tag(field, _WT_LD) + _encode_varint(len(payload)) + payload


def _string_field(field: int, value: str) -> bytes:
    if not value:
        return b""
    raw = value.encode("utf-8")
    return _encode_tag(field, _WT_LD) + _encode_varint(len(raw)) + raw


def _bytes_field(field: int, value: bytes) -> bytes:
    if not value:
        return b""
    return _encode_tag(field, _WT_LD) + _encode_varint(len(value)) + value


def _uint64_field(field: int, value: int) -> bytes:
    if value == 0:
        return b""
    return _encode_tag(field, _WT_VARINT) + _encode_varint(value)


def _int64_field(field: int, value: int) -> bytes:
    """Encode a proto ``int64`` field. Always emitted (even for 0, since INCR
    delta is explicit-presence and 0 is meaningful); negatives use a 10-byte
    two's-complement varint, NOT zigzag."""
    return _encode_tag(field, _WT_VARINT) + _encode_varint(value & 0xFFFFFFFFFFFFFFFF)


def _enum_field(field: int, value: int) -> bytes:
    if value == 0:
        return b""
    return _encode_tag(field, _WT_VARINT) + _encode_varint(value)


# Inner-message helpers

def _encode_optional_string(value: str) -> bytes:
    return _string_field(_OPT_VALUE, value)


def _encode_optional_uint64(value: int) -> bytes:
    return _uint64_field(_OPT_VALUE, value)


def _has_namespace(namespace: str | None) -> bool:
    return namespace is not None and namespace.strip() != ""


def _encode_key_namespace(key: str, namespace: str | None) -> bytes:
    parts = [_string_field(_KN_KEY, key)]
    if _has_namespace(namespace):
        parts.append(_ld_field(_KN_NAMESPACE, _encode_optional_string(namespace)))
    return b"".join(parts)


def _encode_pattern_namespace(pattern: str, namespace: str | None) -> bytes:
    parts = [_string_field(_PN_PATTERN, pattern)]
    if _has_namespace(namespace):
        parts.append(_ld_field(_PN_NAMESPACE, _encode_optional_string(namespace)))
    return b"".join(parts)


def _encode_set_request(key: str, value: bytes, ttl_secs: int, namespace: str | None) -> bytes:
    parts = [
        _string_field(_SR_KEY, key),
        _bytes_field(_SR_VALUE, value),
    ]
    if ttl_secs > 0:
        parts.append(_ld_field(_SR_TTL_SECS, _encode_optional_uint64(ttl_secs)))
    if _has_namespace(namespace):
        parts.append(_ld_field(_SR_NAMESPACE, _encode_optional_string(namespace)))
    return b"".join(parts)


def _encode_incr_request(key: str, delta: int, ttl_secs_on_create: int, namespace: str | None) -> bytes:
    parts = [
        _string_field(_INCR_KEY, key),
        _int64_field(_INCR_DELTA, delta),
    ]
    if ttl_secs_on_create > 0:
        parts.append(_ld_field(_INCR_TTL_SECS_ON_CREATE, _encode_optional_uint64(ttl_secs_on_create)))
    if _has_namespace(namespace):
        parts.append(_ld_field(_INCR_NAMESPACE, _encode_optional_string(namespace)))
    return b"".join(parts)


def _encode_set_ttl_by_pattern_request(pattern: str, ttl_secs: int, namespace: str | None) -> bytes:
    parts = [_string_field(_STBP_PATTERN, pattern)]
    if ttl_secs > 0:
        parts.append(_ld_field(_STBP_TTL_SECS, _encode_optional_uint64(ttl_secs)))
    if _has_namespace(namespace):
        parts.append(_ld_field(_STBP_NAMESPACE, _encode_optional_string(namespace)))
    return b"".join(parts)


def _encode_auth_request(token: str) -> bytes:
    return _string_field(_AUTH_TOKEN, token)


def _wrap_client_request(variant_field: int, inner: bytes) -> bytes:
    """Wrap an inner ClientRequest oneof variant in Envelope + 4-byte BE length frame."""
    request_bytes = _ld_field_always(variant_field, inner)
    envelope_bytes = (
        _enum_field(_ENV_VERSION, PROTOCOL_VERSION)
        + _ld_field_always(_ENV_CLIENT_REQUEST, request_bytes)
    )
    return struct.pack(">I", len(envelope_bytes)) + envelope_bytes


# ---------------------------------------------------------------------------
# Public encoders (preserve old `encode_*` names for tcp_client.py)
# ---------------------------------------------------------------------------

def encode_get(key: str, namespace: str | None = None) -> bytes:
    return _wrap_client_request(_REQ_GET, _encode_key_namespace(key, namespace))


def encode_set(key: str, value: bytes, ttl_secs: int = 0, namespace: str | None = None) -> bytes:
    return _wrap_client_request(_REQ_SET, _encode_set_request(key, value, ttl_secs, namespace))


def encode_delete(key: str, namespace: str | None = None) -> bytes:
    return _wrap_client_request(_REQ_DELETE, _encode_key_namespace(key, namespace))


def encode_set_nx(key: str, value: bytes, ttl_secs: int = 0, namespace: str | None = None) -> bytes:
    # SET_NX reuses the SetRequest wire shape (proto: SetRequest set_nx = 10).
    return _wrap_client_request(_REQ_SET_NX, _encode_set_request(key, value, ttl_secs, namespace))


def encode_incr(key: str, delta: int = 1, ttl_secs_on_create: int = 0, namespace: str | None = None) -> bytes:
    return _wrap_client_request(_REQ_INCR, _encode_incr_request(key, delta, ttl_secs_on_create, namespace))


def encode_ping() -> bytes:
    return _wrap_client_request(_REQ_PING, b"")


def encode_auth(token: str) -> bytes:
    return _wrap_client_request(_REQ_AUTH, _encode_auth_request(token))


def encode_watch(key: str, namespace: str | None = None) -> bytes:
    return _wrap_client_request(_REQ_WATCH, _encode_key_namespace(key, namespace))


def encode_unwatch(key: str, namespace: str | None = None) -> bytes:
    return _wrap_client_request(_REQ_UNWATCH, _encode_key_namespace(key, namespace))


def encode_delete_by_pattern(pattern: str, namespace: str | None = None) -> bytes:
    return _wrap_client_request(_REQ_DELETE_BY_PATTERN, _encode_pattern_namespace(pattern, namespace))


def encode_set_ttl_by_pattern(pattern: str, ttl_secs: int = 0, namespace: str | None = None) -> bytes:
    return _wrap_client_request(
        _REQ_SET_TTL_BY_PATTERN,
        _encode_set_ttl_by_pattern_request(pattern, ttl_secs, namespace),
    )


# ---------------------------------------------------------------------------
# Decoder
# ---------------------------------------------------------------------------

class _Reader:
    __slots__ = ("buf", "off", "end")

    def __init__(self, buf: bytes, off: int = 0, end: int | None = None) -> None:
        self.buf = buf
        self.off = off
        self.end = end if end is not None else len(buf)

    def remaining(self) -> int:
        return self.end - self.off

    def read_varint(self) -> int:
        result = 0
        shift = 0
        while self.off < self.end:
            b = self.buf[self.off]
            self.off += 1
            result |= (b & 0x7F) << shift
            if (b & 0x80) == 0:
                return result
            shift += 7
            if shift > 70:
                raise ValueError("varint too long")
        raise ValueError("truncated varint")

    def read_int64(self) -> int:
        """Read a proto ``int64`` varint, reinterpreting the two's-complement
        bit pattern as a signed 64-bit integer."""
        v = self.read_varint()
        return v - (1 << 64) if v >= (1 << 63) else v

    def read_tag(self) -> tuple[int, int]:
        t = self.read_varint()
        return (t >> 3, t & 0x7)

    def read_ld(self) -> bytes:
        length = self.read_varint()
        if self.off + length > self.end:
            raise ValueError("truncated length-delimited field")
        out = self.buf[self.off:self.off + length]
        self.off += length
        return out

    def skip(self, wire: int) -> None:
        if wire == _WT_VARINT:
            self.read_varint()
        elif wire == _WT_LD:
            self.read_ld()
        elif wire == 1:  # fixed64
            self.off += 8
        elif wire == 5:  # fixed32
            self.off += 4
        else:
            raise ValueError(f"unsupported wire type: {wire}")


# ---------------------------------------------------------------------------
# Decoded response containers (preserve _ClassName.type strings used by client)
# ---------------------------------------------------------------------------

class _Value(NamedTuple):
    type: str
    key: str
    value: bytes
    version: int


class _Ok(NamedTuple):
    type: str
    version: int


class _Simple(NamedTuple):
    type: str


class _Error(NamedTuple):
    type: str
    code: DittoErrorCode
    message: str


class _WatchEvent(NamedTuple):
    type: str
    key: str
    value: bytes | None
    version: int


class _PatternDeleted(NamedTuple):
    type: str
    deleted: int


class _PatternTtlUpdated(NamedTuple):
    type: str
    updated: int


class _SetNx(NamedTuple):
    type: str
    created: bool
    version: int


class _Counter(NamedTuple):
    type: str
    value: int
    version: int


ClientResponse = (
    _Value | _Ok | _Simple | _Error | _WatchEvent
    | _PatternDeleted | _PatternTtlUpdated | _SetNx | _Counter
)


def decode_response(buf: bytes) -> ClientResponse:
    """Decode an Envelope containing a ClientResponse oneof variant."""
    env = _Reader(buf)
    response_bytes: bytes | None = None
    version = 0
    while env.remaining() > 0:
        field, wire = env.read_tag()
        if field == _ENV_VERSION and wire == _WT_VARINT:
            version = env.read_varint()
        elif field == _ENV_CLIENT_RESPONSE and wire == _WT_LD:
            response_bytes = env.read_ld()
        else:
            env.skip(wire)

    if version != 0 and version != PROTOCOL_VERSION:
        raise ValueError(f"unsupported protocol version: {version}")
    if response_bytes is None:
        raise ValueError("Envelope is missing client_response payload")

    r = _Reader(response_bytes)
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if wire != _WT_LD:
            r.skip(wire)
            continue
        inner = r.read_ld()
        if field == _RESP_VALUE:               return _decode_value_response(inner)
        if field == _RESP_OK:                  return _decode_ok_response(inner)
        if field == _RESP_DELETED:             return _Simple("Deleted")
        if field == _RESP_NOT_FOUND:           return _Simple("NotFound")
        if field == _RESP_PONG:                return _Simple("Pong")
        if field == _RESP_AUTH_OK:             return _Simple("AuthOk")
        if field == _RESP_ERROR:               return _decode_error_response(inner)
        if field == _RESP_WATCHING:            return _Simple("Watching")
        if field == _RESP_UNWATCHED:           return _Simple("Unwatched")
        if field == _RESP_WATCH_EVENT:         return _decode_watch_event(inner)
        if field == _RESP_PATTERN_DELETED:     return _PatternDeleted("PatternDeleted", _decode_count(inner))
        if field == _RESP_PATTERN_TTL_UPDATED: return _PatternTtlUpdated("PatternTtlUpdated", _decode_count(inner))
        if field == _RESP_SET_NX:              return _decode_set_nx_response(inner)
        if field == _RESP_COUNTER:             return _decode_counter_response(inner)
    raise ValueError("ClientResponse oneof has no active field")


def _decode_value_response(buf: bytes) -> _Value:
    r = _Reader(buf)
    key = ""
    value = b""
    version = 0
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if field == _VAL_KEY and wire == _WT_LD:
            key = r.read_ld().decode("utf-8")
        elif field == _VAL_VALUE and wire == _WT_LD:
            value = bytes(r.read_ld())
        elif field == _VAL_VERSION and wire == _WT_VARINT:
            version = r.read_varint()
        else:
            r.skip(wire)
    return _Value("Value", key, value, version)


def _decode_ok_response(buf: bytes) -> _Ok:
    r = _Reader(buf)
    version = 0
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if field == _VR_VERSION and wire == _WT_VARINT:
            version = r.read_varint()
        else:
            r.skip(wire)
    return _Ok("Ok", version)


def _decode_error_response(buf: bytes) -> _Error:
    r = _Reader(buf)
    code_idx = 0
    message = ""
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if field == _ERR_CODE and wire == _WT_VARINT:
            code_idx = r.read_varint()
        elif field == _ERR_MESSAGE and wire == _WT_LD:
            message = r.read_ld().decode("utf-8")
        else:
            r.skip(wire)
    code = (
        _ERROR_CODE_NAMES[code_idx]
        if 0 <= code_idx < len(_ERROR_CODE_NAMES)
        else DittoErrorCode.INTERNAL_ERROR
    )
    return _Error("Error", code, message)


def _decode_watch_event(buf: bytes) -> _WatchEvent:
    r = _Reader(buf)
    key = ""
    value: bytes | None = None
    version = 0
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if field == _WE_KEY and wire == _WT_LD:
            key = r.read_ld().decode("utf-8")
        elif field == _WE_VALUE and wire == _WT_LD:
            value = _decode_optional_bytes(r.read_ld())
        elif field == _WE_VERSION and wire == _WT_VARINT:
            version = r.read_varint()
        else:
            r.skip(wire)
    return _WatchEvent("WatchEvent", key, value, version)


def _decode_optional_bytes(buf: bytes) -> bytes:
    r = _Reader(buf)
    out = b""
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if field == _OPT_VALUE and wire == _WT_LD:
            out = bytes(r.read_ld())
        else:
            r.skip(wire)
    return out


def _decode_set_nx_response(buf: bytes) -> _SetNx:
    r = _Reader(buf)
    created = False
    version = 0
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if field == _SNX_CREATED and wire == _WT_VARINT:
            created = r.read_varint() != 0
        elif field == _SNX_VERSION and wire == _WT_VARINT:
            version = r.read_varint()
        else:
            r.skip(wire)
    return _SetNx("SetNx", created, version)


def _decode_counter_response(buf: bytes) -> _Counter:
    r = _Reader(buf)
    value = 0
    version = 0
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if field == _CTR_VALUE and wire == _WT_VARINT:
            value = r.read_int64()
        elif field == _CTR_VERSION and wire == _WT_VARINT:
            version = r.read_varint()
        else:
            r.skip(wire)
    return _Counter("Counter", value, version)


def _decode_count(buf: bytes) -> int:
    r = _Reader(buf)
    count = 0
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if field == _COUNT_FIELD and wire == _WT_VARINT:
            count = r.read_varint()
        else:
            r.skip(wire)
    return count


# ---------------------------------------------------------------------------
# Test helpers — used by unit tests to forge server-style frames.
# ---------------------------------------------------------------------------

REQUEST_FIELDS = {
    "GET": _REQ_GET, "SET": _REQ_SET, "DELETE": _REQ_DELETE, "PING": _REQ_PING,
    "AUTH": _REQ_AUTH, "WATCH": _REQ_WATCH, "UNWATCH": _REQ_UNWATCH,
    "DELETE_BY_PATTERN": _REQ_DELETE_BY_PATTERN,
    "SET_TTL_BY_PATTERN": _REQ_SET_TTL_BY_PATTERN,
    "SET_NX": _REQ_SET_NX,
    "INCR": _REQ_INCR,
}

RESPONSE_FIELDS = {
    "VALUE": _RESP_VALUE, "OK": _RESP_OK, "DELETED": _RESP_DELETED,
    "NOT_FOUND": _RESP_NOT_FOUND, "PONG": _RESP_PONG, "AUTH_OK": _RESP_AUTH_OK,
    "ERROR": _RESP_ERROR, "WATCHING": _RESP_WATCHING, "UNWATCHED": _RESP_UNWATCHED,
    "WATCH_EVENT": _RESP_WATCH_EVENT, "PATTERN_DELETED": _RESP_PATTERN_DELETED,
    "PATTERN_TTL_UPDATED": _RESP_PATTERN_TTL_UPDATED,
    "SET_NX": _RESP_SET_NX, "COUNTER": _RESP_COUNTER,
}


def frame_client_response(variant_field: int, inner: bytes) -> bytes:
    """Wrap an inner ClientResponse oneof variant in Envelope + 4-byte BE length frame."""
    response_bytes = _ld_field_always(variant_field, inner)
    envelope_bytes = (
        _enum_field(_ENV_VERSION, PROTOCOL_VERSION)
        + _ld_field_always(_ENV_CLIENT_RESPONSE, response_bytes)
    )
    return struct.pack(">I", len(envelope_bytes)) + envelope_bytes


def encode_error_response_inner(code_idx: int, message: str) -> bytes:
    return _enum_field(_ERR_CODE, code_idx) + _string_field(_ERR_MESSAGE, message)


def encode_version_response_inner(version: int) -> bytes:
    return _uint64_field(_VR_VERSION, version)


def encode_set_nx_response_inner(created: bool, version: int) -> bytes:
    return _uint64_field(_SNX_CREATED, 1 if created else 0) + _uint64_field(_SNX_VERSION, version)


def encode_counter_response_inner(value: int, version: int) -> bytes:
    return _int64_field(_CTR_VALUE, value) + _uint64_field(_CTR_VERSION, version)


def encode_watch_event_inner(key: str, value: bytes | None, version: int) -> bytes:
    parts = [_string_field(_WE_KEY, key)]
    if value is not None:
        # OptionalBytes is always emitted when value is Some(_) — even for empty bytes.
        opt = _bytes_field(_OPT_VALUE, value)
        parts.append(_ld_field_always(_WE_VALUE, opt))
    parts.append(_uint64_field(_WE_VERSION, version))
    return b"".join(parts)


def decode_client_request_variant(buf: bytes) -> tuple[int, bytes]:
    """Test helper: parse an Envelope (no length prefix) and return the
    active ClientRequest oneof field number plus its inner buffer."""
    env = _Reader(buf)
    request_bytes: bytes | None = None
    while env.remaining() > 0:
        field, wire = env.read_tag()
        if field == _ENV_CLIENT_REQUEST and wire == _WT_LD:
            request_bytes = env.read_ld()
        else:
            env.skip(wire)
    if request_bytes is None:
        raise ValueError("Envelope is missing client_request payload")

    r = _Reader(request_bytes)
    while r.remaining() > 0:
        field, wire = r.read_tag()
        if wire != _WT_LD:
            r.skip(wire)
            continue
        return field, r.read_ld()
    raise ValueError("ClientRequest oneof has no active field")
