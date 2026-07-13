use crate::errors::{DittoError, Result};

const PROTOCOL_VERSION: u64 = 1;

const ENV_VERSION: u32 = 1;
const ENV_CLIENT_REQUEST: u32 = 2;
const ENV_CLIENT_RESPONSE: u32 = 3;

const REQ_GET: u32 = 1;
const REQ_SET: u32 = 2;
const REQ_DELETE: u32 = 3;
const REQ_PING: u32 = 4;
const REQ_AUTH: u32 = 5;
const REQ_WATCH: u32 = 6;
const REQ_UNWATCH: u32 = 7;
const REQ_DELETE_BY_PATTERN: u32 = 8;
const REQ_SET_TTL_BY_PATTERN: u32 = 9;
const REQ_SET_NX: u32 = 10;
const REQ_INCR: u32 = 11;

const RESP_VALUE: u32 = 1;
const RESP_OK: u32 = 2;
const RESP_DELETED: u32 = 3;
const RESP_NOT_FOUND: u32 = 4;
const RESP_PONG: u32 = 5;
const RESP_AUTH_OK: u32 = 6;
const RESP_ERROR: u32 = 7;
const RESP_WATCHING: u32 = 8;
const RESP_UNWATCHED: u32 = 9;
const RESP_WATCH_EVENT: u32 = 10;
const RESP_PATTERN_DELETED: u32 = 11;
const RESP_PATTERN_TTL_UPDATED: u32 = 12;
const RESP_SET_NX: u32 = 13;
const RESP_COUNTER: u32 = 14;

const WT_VARINT: u32 = 0;
const WT_LD: u32 = 2;

const KN_KEY: u32 = 1;
const KN_NAMESPACE: u32 = 2;
const PN_PATTERN: u32 = 1;
const PN_NAMESPACE: u32 = 2;
const SR_KEY: u32 = 1;
const SR_VALUE: u32 = 2;
const SR_TTL_SECS: u32 = 3;
const SR_NAMESPACE: u32 = 4;
const STBP_PATTERN: u32 = 1;
const STBP_TTL_SECS: u32 = 2;
const STBP_NAMESPACE: u32 = 3;
const INCR_KEY: u32 = 1;
const INCR_DELTA: u32 = 2;
const INCR_TTL_SECS_ON_CREATE: u32 = 3;
const INCR_NAMESPACE: u32 = 4;
const SNX_CREATED: u32 = 1;
const SNX_VERSION: u32 = 2;
const CTR_VALUE: u32 = 1;
const CTR_VERSION: u32 = 2;
const AUTH_TOKEN: u32 = 1;
const VAL_KEY: u32 = 1;
const VAL_VALUE: u32 = 2;
const VAL_VERSION: u32 = 3;
const VR_VERSION: u32 = 1;
const ERR_CODE: u32 = 1;
const ERR_MESSAGE: u32 = 2;
const WE_KEY: u32 = 1;
const WE_VALUE: u32 = 2;
const WE_VERSION: u32 = 3;
const COUNT_FIELD: u32 = 1;
const OPT_VALUE: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClientResponse {
    Value {
        key: String,
        value: Vec<u8>,
        version: u64,
    },
    Ok {
        version: u64,
    },
    Deleted,
    NotFound,
    Pong,
    AuthOk,
    Error {
        code: String,
        message: String,
    },
    Watching,
    Unwatched,
    WatchEvent {
        key: String,
        value: Option<Vec<u8>>,
        version: u64,
    },
    PatternDeleted {
        deleted: u64,
    },
    PatternTtlUpdated {
        updated: u64,
    },
    SetNx {
        created: bool,
        version: u64,
    },
    Counter {
        value: i64,
        version: u64,
    },
}

pub fn encode_get(key: &str, namespace: Option<&str>) -> Vec<u8> {
    wrap_client_request(REQ_GET, encode_key_namespace(key, namespace))
}

pub fn encode_set(
    key: &str,
    value: &[u8],
    ttl_secs: Option<u64>,
    namespace: Option<&str>,
) -> Vec<u8> {
    wrap_client_request(REQ_SET, encode_set_request(key, value, ttl_secs, namespace))
}

pub fn encode_delete(key: &str, namespace: Option<&str>) -> Vec<u8> {
    wrap_client_request(REQ_DELETE, encode_key_namespace(key, namespace))
}

/// SET_NX reuses the SetRequest wire shape (proto: `SetRequest set_nx = 10`).
pub fn encode_set_nx(
    key: &str,
    value: &[u8],
    ttl_secs: Option<u64>,
    namespace: Option<&str>,
) -> Vec<u8> {
    wrap_client_request(
        REQ_SET_NX,
        encode_set_request(key, value, ttl_secs, namespace),
    )
}

pub fn encode_incr(
    key: &str,
    delta: i64,
    ttl_secs_on_create: Option<u64>,
    namespace: Option<&str>,
) -> Vec<u8> {
    wrap_client_request(
        REQ_INCR,
        encode_incr_request(key, delta, ttl_secs_on_create, namespace),
    )
}

pub fn encode_ping() -> Vec<u8> {
    wrap_client_request(REQ_PING, Vec::new())
}

pub fn encode_auth(token: &str) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.string_field(AUTH_TOKEN, token);
    wrap_client_request(REQ_AUTH, writer.finish())
}

pub fn encode_watch(key: &str, namespace: Option<&str>) -> Vec<u8> {
    wrap_client_request(REQ_WATCH, encode_key_namespace(key, namespace))
}

pub fn encode_unwatch(key: &str, namespace: Option<&str>) -> Vec<u8> {
    wrap_client_request(REQ_UNWATCH, encode_key_namespace(key, namespace))
}

pub fn encode_delete_by_pattern(pattern: &str, namespace: Option<&str>) -> Vec<u8> {
    wrap_client_request(
        REQ_DELETE_BY_PATTERN,
        encode_pattern_namespace(pattern, namespace),
    )
}

pub fn encode_set_ttl_by_pattern(
    pattern: &str,
    ttl_secs: Option<u64>,
    namespace: Option<&str>,
) -> Vec<u8> {
    wrap_client_request(
        REQ_SET_TTL_BY_PATTERN,
        encode_set_ttl_by_pattern_request(pattern, ttl_secs, namespace),
    )
}

pub fn decode_response(payload: &[u8]) -> Result<ClientResponse> {
    let mut reader = Reader::new(payload);
    let mut response_bytes = None;
    let mut version = 0;

    while reader.remaining() > 0 {
        let (field, wire) = reader.read_tag()?;
        match (field, wire) {
            (ENV_VERSION, WT_VARINT) => version = reader.read_varint()?,
            (ENV_CLIENT_RESPONSE, WT_LD) => response_bytes = Some(reader.read_ld()?.to_vec()),
            _ => reader.skip(wire)?,
        }
    }

    if version != 0 && version != PROTOCOL_VERSION {
        return Err(DittoError::Protocol(format!(
            "unsupported protocol version: {version}"
        )));
    }
    let response_bytes = response_bytes.ok_or_else(|| {
        DittoError::Protocol("envelope is missing client_response payload".into())
    })?;

    let mut response = Reader::new(&response_bytes);
    while response.remaining() > 0 {
        let (field, wire) = response.read_tag()?;
        if wire != WT_LD {
            response.skip(wire)?;
            continue;
        }
        let inner = response.read_ld()?;
        return match field {
            RESP_VALUE => decode_value_response(inner),
            RESP_OK => decode_ok_response(inner),
            RESP_DELETED => Ok(ClientResponse::Deleted),
            RESP_NOT_FOUND => Ok(ClientResponse::NotFound),
            RESP_PONG => Ok(ClientResponse::Pong),
            RESP_AUTH_OK => Ok(ClientResponse::AuthOk),
            RESP_ERROR => decode_error_response(inner),
            RESP_WATCHING => Ok(ClientResponse::Watching),
            RESP_UNWATCHED => Ok(ClientResponse::Unwatched),
            RESP_WATCH_EVENT => decode_watch_event(inner),
            RESP_PATTERN_DELETED => Ok(ClientResponse::PatternDeleted {
                deleted: decode_count(inner)?,
            }),
            RESP_PATTERN_TTL_UPDATED => Ok(ClientResponse::PatternTtlUpdated {
                updated: decode_count(inner)?,
            }),
            RESP_SET_NX => decode_set_nx_response(inner),
            RESP_COUNTER => decode_counter_response(inner),
            _ => continue,
        };
    }

    Err(DittoError::Protocol(
        "client_response oneof has no active field".into(),
    ))
}

fn encode_key_namespace(key: &str, namespace: Option<&str>) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.string_field(KN_KEY, key);
    if let Some(namespace) = namespace.filter(|ns| !ns.trim().is_empty()) {
        writer.ld_field(KN_NAMESPACE, &encode_optional_string(namespace));
    }
    writer.finish()
}

fn encode_pattern_namespace(pattern: &str, namespace: Option<&str>) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.string_field(PN_PATTERN, pattern);
    if let Some(namespace) = namespace.filter(|ns| !ns.trim().is_empty()) {
        writer.ld_field(PN_NAMESPACE, &encode_optional_string(namespace));
    }
    writer.finish()
}

fn encode_set_request(
    key: &str,
    value: &[u8],
    ttl_secs: Option<u64>,
    namespace: Option<&str>,
) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.string_field(SR_KEY, key);
    writer.bytes_field(SR_VALUE, value);
    if let Some(ttl) = ttl_secs.filter(|ttl| *ttl > 0) {
        writer.ld_field(SR_TTL_SECS, &encode_optional_u64(ttl));
    }
    if let Some(namespace) = namespace.filter(|ns| !ns.trim().is_empty()) {
        writer.ld_field(SR_NAMESPACE, &encode_optional_string(namespace));
    }
    writer.finish()
}

fn encode_incr_request(
    key: &str,
    delta: i64,
    ttl_secs_on_create: Option<u64>,
    namespace: Option<&str>,
) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.string_field(INCR_KEY, key);
    // `delta` is a proto `int64` (two's-complement varint). Always emitted so the
    // server uses the caller's delta verbatim rather than its absent-default of 1.
    writer.int64_field(INCR_DELTA, delta);
    if let Some(ttl) = ttl_secs_on_create.filter(|ttl| *ttl > 0) {
        writer.ld_field(INCR_TTL_SECS_ON_CREATE, &encode_optional_u64(ttl));
    }
    if let Some(namespace) = namespace.filter(|ns| !ns.trim().is_empty()) {
        writer.ld_field(INCR_NAMESPACE, &encode_optional_string(namespace));
    }
    writer.finish()
}

fn encode_set_ttl_by_pattern_request(
    pattern: &str,
    ttl_secs: Option<u64>,
    namespace: Option<&str>,
) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.string_field(STBP_PATTERN, pattern);
    if let Some(ttl) = ttl_secs.filter(|ttl| *ttl > 0) {
        writer.ld_field(STBP_TTL_SECS, &encode_optional_u64(ttl));
    }
    if let Some(namespace) = namespace.filter(|ns| !ns.trim().is_empty()) {
        writer.ld_field(STBP_NAMESPACE, &encode_optional_string(namespace));
    }
    writer.finish()
}

fn encode_optional_string(value: &str) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.string_field(OPT_VALUE, value);
    writer.finish()
}

fn encode_optional_u64(value: u64) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.uint64_field(OPT_VALUE, value);
    writer.finish()
}

fn wrap_client_request(variant_field: u32, inner: Vec<u8>) -> Vec<u8> {
    let mut request = Writer::default();
    request.ld_field_always(variant_field, &inner);

    let mut envelope = Writer::default();
    envelope.uint64_field(ENV_VERSION, PROTOCOL_VERSION);
    envelope.ld_field_always(ENV_CLIENT_REQUEST, &request.finish());
    let envelope = envelope.finish();

    let mut frame = Vec::with_capacity(4 + envelope.len());
    frame.extend_from_slice(&(envelope.len() as u32).to_be_bytes());
    frame.extend_from_slice(&envelope);
    frame
}

fn decode_value_response(buf: &[u8]) -> Result<ClientResponse> {
    let mut reader = Reader::new(buf);
    let mut key = String::new();
    let mut value = Vec::new();
    let mut version = 0;
    while reader.remaining() > 0 {
        let (field, wire) = reader.read_tag()?;
        match (field, wire) {
            (VAL_KEY, WT_LD) => key = String::from_utf8_lossy(reader.read_ld()?).to_string(),
            (VAL_VALUE, WT_LD) => value = reader.read_ld()?.to_vec(),
            (VAL_VERSION, WT_VARINT) => version = reader.read_varint()?,
            _ => reader.skip(wire)?,
        }
    }
    Ok(ClientResponse::Value {
        key,
        value,
        version,
    })
}

fn decode_ok_response(buf: &[u8]) -> Result<ClientResponse> {
    let mut reader = Reader::new(buf);
    let mut version = 0;
    while reader.remaining() > 0 {
        let (field, wire) = reader.read_tag()?;
        if field == VR_VERSION && wire == WT_VARINT {
            version = reader.read_varint()?;
        } else {
            reader.skip(wire)?;
        }
    }
    Ok(ClientResponse::Ok { version })
}

fn decode_error_response(buf: &[u8]) -> Result<ClientResponse> {
    let mut reader = Reader::new(buf);
    let mut code_idx = 0;
    let mut message = String::new();
    while reader.remaining() > 0 {
        let (field, wire) = reader.read_tag()?;
        match (field, wire) {
            (ERR_CODE, WT_VARINT) => code_idx = reader.read_varint()?,
            (ERR_MESSAGE, WT_LD) => {
                message = String::from_utf8_lossy(reader.read_ld()?).to_string()
            }
            _ => reader.skip(wire)?,
        }
    }
    Ok(ClientResponse::Error {
        code: error_code_name(code_idx).to_string(),
        message,
    })
}

fn decode_watch_event(buf: &[u8]) -> Result<ClientResponse> {
    let mut reader = Reader::new(buf);
    let mut key = String::new();
    let mut value = None;
    let mut version = 0;
    while reader.remaining() > 0 {
        let (field, wire) = reader.read_tag()?;
        match (field, wire) {
            (WE_KEY, WT_LD) => key = String::from_utf8_lossy(reader.read_ld()?).to_string(),
            (WE_VALUE, WT_LD) => value = Some(decode_optional_bytes(reader.read_ld()?)?),
            (WE_VERSION, WT_VARINT) => version = reader.read_varint()?,
            _ => reader.skip(wire)?,
        }
    }
    Ok(ClientResponse::WatchEvent {
        key,
        value,
        version,
    })
}

fn decode_optional_bytes(buf: &[u8]) -> Result<Vec<u8>> {
    let mut reader = Reader::new(buf);
    let mut out = Vec::new();
    while reader.remaining() > 0 {
        let (field, wire) = reader.read_tag()?;
        if field == OPT_VALUE && wire == WT_LD {
            out = reader.read_ld()?.to_vec();
        } else {
            reader.skip(wire)?;
        }
    }
    Ok(out)
}

fn decode_set_nx_response(buf: &[u8]) -> Result<ClientResponse> {
    let mut reader = Reader::new(buf);
    let mut created = false;
    let mut version = 0;
    while reader.remaining() > 0 {
        let (field, wire) = reader.read_tag()?;
        match (field, wire) {
            (SNX_CREATED, WT_VARINT) => created = reader.read_varint()? != 0,
            (SNX_VERSION, WT_VARINT) => version = reader.read_varint()?,
            _ => reader.skip(wire)?,
        }
    }
    Ok(ClientResponse::SetNx { created, version })
}

fn decode_counter_response(buf: &[u8]) -> Result<ClientResponse> {
    let mut reader = Reader::new(buf);
    let mut value = 0i64;
    let mut version = 0;
    while reader.remaining() > 0 {
        let (field, wire) = reader.read_tag()?;
        match (field, wire) {
            (CTR_VALUE, WT_VARINT) => value = reader.read_int64()?,
            (CTR_VERSION, WT_VARINT) => version = reader.read_varint()?,
            _ => reader.skip(wire)?,
        }
    }
    Ok(ClientResponse::Counter { value, version })
}

fn decode_count(buf: &[u8]) -> Result<u64> {
    let mut reader = Reader::new(buf);
    let mut count = 0;
    while reader.remaining() > 0 {
        let (field, wire) = reader.read_tag()?;
        if field == COUNT_FIELD && wire == WT_VARINT {
            count = reader.read_varint()?;
        } else {
            reader.skip(wire)?;
        }
    }
    Ok(count)
}

fn error_code_name(idx: u64) -> &'static str {
    match idx {
        0 => "NodeInactive",
        1 => "NoQuorum",
        2 => "KeyNotFound",
        3 => "InternalError",
        4 => "WriteTimeout",
        5 => "ValueTooLarge",
        6 => "KeyLimitReached",
        7 => "RateLimited",
        8 => "CircuitOpen",
        9 => "NamespaceQuotaExceeded",
        10 => "AuthFailed",
        11 => "AccessDenied",
        12 => "UnsupportedRequest",
        13 => "TypeMismatch",
        14 => "Overflow",
        _ => "InternalError",
    }
}

#[derive(Default)]
struct Writer {
    buf: Vec<u8>,
}

impl Writer {
    fn varint(&mut self, mut value: u64) {
        while value >= 0x80 {
            self.buf.push((value as u8) | 0x80);
            value >>= 7;
        }
        self.buf.push(value as u8);
    }

    fn tag(&mut self, field: u32, wire: u32) {
        self.varint(((field << 3) | wire) as u64);
    }

    fn uint64_field(&mut self, field: u32, value: u64) {
        if value == 0 {
            return;
        }
        self.tag(field, WT_VARINT);
        self.varint(value);
    }

    /// Encode a proto `int64` field. Unlike `uint64_field` this always emits
    /// (even for 0) because INCR delta is explicit-presence and 0 is meaningful,
    /// and negatives are written as a 10-byte two's-complement varint.
    fn int64_field(&mut self, field: u32, value: i64) {
        self.tag(field, WT_VARINT);
        self.varint(value as u64);
    }

    fn ld_field(&mut self, field: u32, payload: &[u8]) {
        if payload.is_empty() {
            return;
        }
        self.ld_field_always(field, payload);
    }

    fn ld_field_always(&mut self, field: u32, payload: &[u8]) {
        self.tag(field, WT_LD);
        self.varint(payload.len() as u64);
        self.buf.extend_from_slice(payload);
    }

    fn string_field(&mut self, field: u32, value: &str) {
        if value.is_empty() {
            return;
        }
        self.ld_field_always(field, value.as_bytes());
    }

    fn bytes_field(&mut self, field: u32, value: &[u8]) {
        if value.is_empty() {
            return;
        }
        self.ld_field_always(field, value);
    }

    fn finish(self) -> Vec<u8> {
        self.buf
    }
}

struct Reader<'a> {
    buf: &'a [u8],
    off: usize,
}

impl<'a> Reader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, off: 0 }
    }

    fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.off)
    }

    fn read_varint(&mut self) -> Result<u64> {
        let mut result = 0u64;
        let mut shift = 0;
        while self.off < self.buf.len() {
            let byte = self.buf[self.off];
            self.off += 1;
            result |= u64::from(byte & 0x7f) << shift;
            if byte & 0x80 == 0 {
                return Ok(result);
            }
            shift += 7;
            if shift > 70 {
                return Err(DittoError::Protocol("varint too long".into()));
            }
        }
        Err(DittoError::Protocol("truncated varint".into()))
    }

    fn read_int64(&mut self) -> Result<i64> {
        Ok(self.read_varint()? as i64)
    }

    fn read_tag(&mut self) -> Result<(u32, u32)> {
        let tag = self.read_varint()? as u32;
        Ok((tag >> 3, tag & 0x7))
    }

    fn read_ld(&mut self) -> Result<&'a [u8]> {
        let len = self.read_varint()? as usize;
        if self.off + len > self.buf.len() {
            return Err(DittoError::Protocol(
                "truncated length-delimited field".into(),
            ));
        }
        let out = &self.buf[self.off..self.off + len];
        self.off += len;
        Ok(out)
    }

    fn skip(&mut self, wire: u32) -> Result<()> {
        match wire {
            WT_VARINT => {
                self.read_varint()?;
            }
            WT_LD => {
                self.read_ld()?;
            }
            1 => self.off += 8,
            5 => self.off += 4,
            _ => {
                return Err(DittoError::Protocol(format!(
                    "unsupported wire type: {wire}"
                )));
            }
        }
        if self.off > self.buf.len() {
            return Err(DittoError::Protocol("truncated fixed-width field".into()));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_ping_frame() {
        let frame = encode_ping();
        assert_eq!(&frame[0..4], &[0, 0, 0, 6]);
        assert_eq!(&frame[4..], &[8, 1, 18, 2, 34, 0]);
    }

    #[test]
    fn decodes_set_nx_response() {
        let frame = test_support::frame_set_nx(true, 42);
        // strip the 4-byte length prefix before decoding the envelope
        let resp = decode_response(&frame[4..]).unwrap();
        assert_eq!(
            resp,
            ClientResponse::SetNx {
                created: true,
                version: 42
            }
        );
    }

    #[test]
    fn decodes_set_nx_existing_keeps_version_without_create() {
        let frame = test_support::frame_set_nx(false, 7);
        let resp = decode_response(&frame[4..]).unwrap();
        assert_eq!(
            resp,
            ClientResponse::SetNx {
                created: false,
                version: 7
            }
        );
    }

    #[test]
    fn decodes_counter_response_including_negative_values() {
        for value in [-5i64, 0, 1, i64::MIN, i64::MAX] {
            let frame = test_support::frame_counter(value, 3);
            let resp = decode_response(&frame[4..]).unwrap();
            assert_eq!(resp, ClientResponse::Counter { value, version: 3 });
        }
    }

    #[test]
    fn incr_encodes_negative_delta_as_twos_complement_varint() {
        // proto int64 -1 is a 10-byte all-0xFF/0x01 varint (not zigzag).
        let frame = encode_incr("k", -1, None, None);
        // The delta field (tag 0x10 = field 2, varint) must be followed by the
        // 10-byte two's-complement encoding of -1.
        let needle = [
            0x10u8, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01,
        ];
        assert!(
            frame.windows(needle.len()).any(|w| w == needle),
            "expected two's-complement delta encoding in frame {frame:?}"
        );
    }

    #[test]
    fn error_code_names_cover_atomic_primitive_codes() {
        assert_eq!(error_code_name(11), "AccessDenied");
        assert_eq!(error_code_name(12), "UnsupportedRequest");
        assert_eq!(error_code_name(13), "TypeMismatch");
        assert_eq!(error_code_name(14), "Overflow");
    }
}

#[cfg(test)]
pub(crate) mod test_support {
    use super::*;

    pub(crate) fn frame_pong() -> Vec<u8> {
        frame_client_response(RESP_PONG, &[])
    }

    pub(crate) fn frame_auth_ok() -> Vec<u8> {
        frame_client_response(RESP_AUTH_OK, &[])
    }

    pub(crate) fn frame_not_found() -> Vec<u8> {
        frame_client_response(RESP_NOT_FOUND, &[])
    }

    pub(crate) fn frame_deleted() -> Vec<u8> {
        frame_client_response(RESP_DELETED, &[])
    }

    pub(crate) fn frame_watching() -> Vec<u8> {
        frame_client_response(RESP_WATCHING, &[])
    }

    pub(crate) fn frame_unwatched() -> Vec<u8> {
        frame_client_response(RESP_UNWATCHED, &[])
    }

    pub(crate) fn frame_value(key: &str, value: &[u8], version: u64) -> Vec<u8> {
        let mut inner = Writer::default();
        inner.string_field(VAL_KEY, key);
        inner.bytes_field(VAL_VALUE, value);
        inner.uint64_field(VAL_VERSION, version);
        frame_client_response(RESP_VALUE, &inner.finish())
    }

    pub(crate) fn frame_ok(version: u64) -> Vec<u8> {
        let mut inner = Writer::default();
        inner.uint64_field(VR_VERSION, version);
        frame_client_response(RESP_OK, &inner.finish())
    }

    pub(crate) fn frame_set_nx(created: bool, version: u64) -> Vec<u8> {
        let mut inner = Writer::default();
        inner.uint64_field(SNX_CREATED, if created { 1 } else { 0 });
        inner.uint64_field(SNX_VERSION, version);
        frame_client_response(RESP_SET_NX, &inner.finish())
    }

    pub(crate) fn frame_counter(value: i64, version: u64) -> Vec<u8> {
        let mut inner = Writer::default();
        inner.int64_field(CTR_VALUE, value);
        inner.uint64_field(CTR_VERSION, version);
        frame_client_response(RESP_COUNTER, &inner.finish())
    }

    pub(crate) fn frame_pattern_deleted(deleted: u64) -> Vec<u8> {
        frame_count(RESP_PATTERN_DELETED, deleted)
    }

    pub(crate) fn frame_pattern_ttl_updated(updated: u64) -> Vec<u8> {
        frame_count(RESP_PATTERN_TTL_UPDATED, updated)
    }

    pub(crate) fn frame_watch_event(key: &str, value: Option<&[u8]>, version: u64) -> Vec<u8> {
        let mut inner = Writer::default();
        inner.string_field(WE_KEY, key);
        if let Some(value) = value {
            let mut opt = Writer::default();
            opt.bytes_field(OPT_VALUE, value);
            inner.ld_field_always(WE_VALUE, &opt.finish());
        }
        inner.uint64_field(WE_VERSION, version);
        frame_client_response(RESP_WATCH_EVENT, &inner.finish())
    }

    pub(crate) fn frame_error(code_idx: u64, message: &str) -> Vec<u8> {
        let mut inner = Writer::default();
        inner.uint64_field(ERR_CODE, code_idx);
        inner.string_field(ERR_MESSAGE, message);
        frame_client_response(RESP_ERROR, &inner.finish())
    }

    fn frame_count(field: u32, count: u64) -> Vec<u8> {
        let mut inner = Writer::default();
        inner.uint64_field(COUNT_FIELD, count);
        frame_client_response(field, &inner.finish())
    }

    fn frame_client_response(variant_field: u32, inner: &[u8]) -> Vec<u8> {
        let mut response = Writer::default();
        response.ld_field_always(variant_field, inner);

        let mut envelope = Writer::default();
        envelope.uint64_field(ENV_VERSION, PROTOCOL_VERSION);
        envelope.ld_field_always(ENV_CLIENT_RESPONSE, &response.finish());
        let envelope = envelope.finish();

        let mut frame = Vec::with_capacity(4 + envelope.len());
        frame.extend_from_slice(&(envelope.len() as u32).to_be_bytes());
        frame.extend_from_slice(&envelope);
        frame
    }
}
