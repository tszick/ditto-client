package ditto

// Protobuf wire encoder/decoder for the Ditto client TCP protocol.
//
// Source of truth: ditto-protocol/proto/ditto.proto (proto3, package
// ditto.protocol.v1). This file is hand-rolled to avoid pulling in a
// protoc codegen step; the field numbers below MUST stay in sync with
// ditto.proto.
//
// Wire framing (added by tcp_server.rs):
//   - 4-byte big-endian payload-length prefix before each protobuf Envelope.
//
// Each outbound payload is an Envelope { version=1, client_request: <variant> }
// and each inbound payload is an Envelope { client_response: <variant> }.

import (
	"encoding/binary"
	"fmt"
	"strings"
)

const protocolVersion = 1

// responseKind is a stable index used by tcp_client.go to dispatch on the
// decoded ClientResponse variant. The numeric values must match the iota
// ordering below so existing call sites stay valid.
type responseKind int

const (
	respValue responseKind = iota
	respOK
	respDeleted
	respNotFound
	respPong
	respAuthOK
	respError
	respWatching
	respUnwatched
	respWatchEvent
	respPatternDeleted
	respPatternTTLUpdated
	respSetNX
	respCounter
)

type tcpResponse struct {
	kind     responseKind
	key      string
	value    []byte
	hasValue bool
	version  uint64
	code     string
	message  string
	count    uint64
	created  bool
	counter  int64
}

// Envelope field numbers
const (
	envVersion         = 1
	envClientRequest   = 2
	envClientResponse  = 3
)

// ClientRequest oneof field numbers
const (
	reqGet              = 1
	reqSet              = 2
	reqDelete           = 3
	reqPing             = 4
	reqAuth             = 5
	reqWatch            = 6
	reqUnwatch          = 7
	reqDeleteByPattern  = 8
	reqSetTTLByPattern  = 9
	reqSetNX            = 10
	reqIncr             = 11
)

// ClientResponse oneof field numbers
const (
	rspValue              = 1
	rspOK                 = 2
	rspDeleted            = 3
	rspNotFound           = 4
	rspPong               = 5
	rspAuthOK             = 6
	rspError              = 7
	rspWatching           = 8
	rspUnwatched          = 9
	rspWatchEvent         = 10
	rspPatternDeleted     = 11
	rspPatternTTLUpdated  = 12
	rspSetNX              = 13
	rspCounter            = 14
)

// Wire types
const (
	wtVarint = 0
	wtLD     = 2
)

// Inner-message field numbers
const (
	knKey         = 1
	knNamespace   = 2
	pnPattern     = 1
	pnNamespace   = 2
	srKey         = 1
	srValue       = 2
	srTTLSecs     = 3
	srNamespace   = 4
	stbpPattern   = 1
	stbpTTLSecs   = 2
	stbpNamespace = 3
	incrKey       = 1
	incrDelta     = 2
	incrTTLSecsOnCreate = 3
	incrNamespace = 4
	authToken     = 1
	valKey        = 1
	valValue      = 2
	valVersion    = 3
	vrVersion     = 1
	snxCreated    = 1
	snxVersion    = 2
	counterValue  = 1
	counterVersion = 2
	errCodeField  = 1
	errMessage    = 2
	weKey         = 1
	weValue       = 2
	weVersion     = 3
	countField    = 1
	optValue      = 1 // OptionalString.value, OptionalBytes.value, OptionalUint64.value
)

// ---------------------------------------------------------------------------
// Low-level varint + LD helpers
// ---------------------------------------------------------------------------

func appendVarint(buf []byte, v uint64) []byte {
	for v >= 0x80 {
		buf = append(buf, byte(v)|0x80)
		v >>= 7
	}
	return append(buf, byte(v))
}

func appendTag(buf []byte, field, wire int) []byte {
	return appendVarint(buf, uint64(field<<3)|uint64(wire))
}

func appendLDField(buf []byte, field int, payload []byte) []byte {
	if len(payload) == 0 {
		return buf
	}
	buf = appendTag(buf, field, wtLD)
	buf = appendVarint(buf, uint64(len(payload)))
	return append(buf, payload...)
}

// appendLDFieldAlways encodes a length-delimited field even when the payload
// is empty — used to mark oneof presence.
func appendLDFieldAlways(buf []byte, field int, payload []byte) []byte {
	buf = appendTag(buf, field, wtLD)
	buf = appendVarint(buf, uint64(len(payload)))
	return append(buf, payload...)
}

func appendStringField(buf []byte, field int, value string) []byte {
	if value == "" {
		return buf
	}
	raw := []byte(value)
	buf = appendTag(buf, field, wtLD)
	buf = appendVarint(buf, uint64(len(raw)))
	return append(buf, raw...)
}

func appendBytesField(buf []byte, field int, value []byte) []byte {
	if len(value) == 0 {
		return buf
	}
	buf = appendTag(buf, field, wtLD)
	buf = appendVarint(buf, uint64(len(value)))
	return append(buf, value...)
}

func appendUint64Field(buf []byte, field int, value uint64) []byte {
	if value == 0 {
		return buf
	}
	buf = appendTag(buf, field, wtVarint)
	return appendVarint(buf, value)
}

func appendEnumField(buf []byte, field int, value int) []byte {
	if value == 0 {
		return buf
	}
	buf = appendTag(buf, field, wtVarint)
	return appendVarint(buf, uint64(value))
}

func appendInt64Field(buf []byte, field int, value int64) []byte {
	buf = appendTag(buf, field, wtVarint)
	return appendVarint(buf, uint64(value))
}

// ---------------------------------------------------------------------------
// Inner message encoders
// ---------------------------------------------------------------------------

func encodeOptionalString(value string) []byte {
	return appendStringField(nil, optValue, value)
}

func encodeOptionalUint64(value uint64) []byte {
	return appendUint64Field(nil, optValue, value)
}

func hasNamespace(ns *string) bool {
	return ns != nil && strings.TrimSpace(*ns) != ""
}

func encodeKeyNamespace(key string, namespace *string) []byte {
	buf := appendStringField(nil, knKey, key)
	if hasNamespace(namespace) {
		buf = appendLDField(buf, knNamespace, encodeOptionalString(*namespace))
	}
	return buf
}

func encodePatternNamespace(pattern string, namespace *string) []byte {
	buf := appendStringField(nil, pnPattern, pattern)
	if hasNamespace(namespace) {
		buf = appendLDField(buf, pnNamespace, encodeOptionalString(*namespace))
	}
	return buf
}

func encodeSetRequest(key string, value []byte, ttlSecs *uint64, namespace *string) []byte {
	buf := appendStringField(nil, srKey, key)
	buf = appendBytesField(buf, srValue, value)
	if ttlSecs != nil && *ttlSecs > 0 {
		buf = appendLDField(buf, srTTLSecs, encodeOptionalUint64(*ttlSecs))
	}
	if hasNamespace(namespace) {
		buf = appendLDField(buf, srNamespace, encodeOptionalString(*namespace))
	}
	return buf
}

func encodeSetTTLByPatternRequest(pattern string, ttlSecs *uint64, namespace *string) []byte {
	buf := appendStringField(nil, stbpPattern, pattern)
	if ttlSecs != nil && *ttlSecs > 0 {
		buf = appendLDField(buf, stbpTTLSecs, encodeOptionalUint64(*ttlSecs))
	}
	if hasNamespace(namespace) {
		buf = appendLDField(buf, stbpNamespace, encodeOptionalString(*namespace))
	}
	return buf
}

func encodeIncrRequest(key string, delta *int64, ttlSecsOnCreate *uint64, namespace *string) []byte {
	buf := appendStringField(nil, incrKey, key)
	if delta != nil {
		buf = appendInt64Field(buf, incrDelta, *delta)
	}
	if ttlSecsOnCreate != nil && *ttlSecsOnCreate > 0 {
		buf = appendLDField(buf, incrTTLSecsOnCreate, encodeOptionalUint64(*ttlSecsOnCreate))
	}
	if hasNamespace(namespace) {
		buf = appendLDField(buf, incrNamespace, encodeOptionalString(*namespace))
	}
	return buf
}

func encodeAuthRequest(token string) []byte {
	return appendStringField(nil, authToken, token)
}

// wrapClientRequest wraps an inner ClientRequest oneof variant payload in
// Envelope + 4-byte BE length frame.
func wrapClientRequest(variantField int, inner []byte) []byte {
	requestBytes := appendLDFieldAlways(nil, variantField, inner)

	envelope := appendEnumField(nil, envVersion, protocolVersion)
	envelope = appendLDFieldAlways(envelope, envClientRequest, requestBytes)

	out := make([]byte, 4+len(envelope))
	binary.BigEndian.PutUint32(out[:4], uint32(len(envelope)))
	copy(out[4:], envelope)
	return out
}

// ---------------------------------------------------------------------------
// Public encoders (named to match the tcp_client.go call sites)
// ---------------------------------------------------------------------------

func encodeGet(key string, namespace *string) []byte {
	return wrapClientRequest(reqGet, encodeKeyNamespace(key, namespace))
}

func encodeSet(key string, value []byte, ttlSecs *uint64, namespace *string) []byte {
	return wrapClientRequest(reqSet, encodeSetRequest(key, value, ttlSecs, namespace))
}

func encodeDelete(key string, namespace *string) []byte {
	return wrapClientRequest(reqDelete, encodeKeyNamespace(key, namespace))
}

func encodePing() []byte {
	return wrapClientRequest(reqPing, nil)
}

func encodeAuth(token string) []byte {
	return wrapClientRequest(reqAuth, encodeAuthRequest(token))
}

func encodeWatch(key string, namespace *string) []byte {
	return wrapClientRequest(reqWatch, encodeKeyNamespace(key, namespace))
}

func encodeUnwatch(key string, namespace *string) []byte {
	return wrapClientRequest(reqUnwatch, encodeKeyNamespace(key, namespace))
}

func encodeDeleteByPattern(pattern string, namespace *string) []byte {
	return wrapClientRequest(reqDeleteByPattern, encodePatternNamespace(pattern, namespace))
}

func encodeSetTTLByPattern(pattern string, ttlSecs *uint64, namespace *string) []byte {
	return wrapClientRequest(reqSetTTLByPattern, encodeSetTTLByPatternRequest(pattern, ttlSecs, namespace))
}

func encodeSetNX(key string, value []byte, ttlSecs *uint64, namespace *string) []byte {
	return wrapClientRequest(reqSetNX, encodeSetRequest(key, value, ttlSecs, namespace))
}

func encodeIncr(key string, delta *int64, ttlSecsOnCreate *uint64, namespace *string) []byte {
	return wrapClientRequest(reqIncr, encodeIncrRequest(key, delta, ttlSecsOnCreate, namespace))
}

// ---------------------------------------------------------------------------
// Reader
// ---------------------------------------------------------------------------

type reader struct {
	buf []byte
	off int
}

func (r *reader) remaining() int {
	return len(r.buf) - r.off
}

func (r *reader) readVarint() (uint64, error) {
	var (
		result uint64
		shift  uint
	)
	for r.off < len(r.buf) {
		b := r.buf[r.off]
		r.off++
		result |= uint64(b&0x7F) << shift
		if (b & 0x80) == 0 {
			return result, nil
		}
		shift += 7
		if shift > 70 {
			return 0, fmt.Errorf("varint too long")
		}
	}
	return 0, fmt.Errorf("truncated varint")
}

func (r *reader) readTag() (int, int, error) {
	v, err := r.readVarint()
	if err != nil {
		return 0, 0, err
	}
	return int(v >> 3), int(v & 0x7), nil
}

func (r *reader) readLD() ([]byte, error) {
	n, err := r.readVarint()
	if err != nil {
		return nil, err
	}
	if r.off+int(n) > len(r.buf) {
		return nil, fmt.Errorf("truncated length-delimited field")
	}
	out := r.buf[r.off : r.off+int(n)]
	r.off += int(n)
	return out, nil
}

func (r *reader) skip(wire int) error {
	switch wire {
	case wtVarint:
		_, err := r.readVarint()
		return err
	case wtLD:
		_, err := r.readLD()
		return err
	case 1: // fixed64
		r.off += 8
		return nil
	case 5: // fixed32
		r.off += 4
		return nil
	default:
		return fmt.Errorf("unsupported wire type: %d", wire)
	}
}

// ---------------------------------------------------------------------------
// Decoder — returns the same *tcpResponse shape as the old bincode decoder,
// so tcp_client.go does not need to change.
// ---------------------------------------------------------------------------

func decodeResponse(payload []byte) (*tcpResponse, error) {
	env := &reader{buf: payload}
	var (
		responseBytes []byte
		version       uint64
	)
	for env.remaining() > 0 {
		field, wire, err := env.readTag()
		if err != nil {
			return nil, err
		}
		switch {
		case field == envVersion && wire == wtVarint:
			v, err := env.readVarint()
			if err != nil {
				return nil, err
			}
			version = v
		case field == envClientResponse && wire == wtLD:
			b, err := env.readLD()
			if err != nil {
				return nil, err
			}
			responseBytes = b
		default:
			if err := env.skip(wire); err != nil {
				return nil, err
			}
		}
	}
	if version != 0 && version != protocolVersion {
		return nil, fmt.Errorf("unsupported protocol version: %d", version)
	}
	if responseBytes == nil {
		return nil, fmt.Errorf("envelope is missing client_response payload")
	}

	r := &reader{buf: responseBytes}
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return nil, err
		}
		if wire != wtLD {
			if err := r.skip(wire); err != nil {
				return nil, err
			}
			continue
		}
		inner, err := r.readLD()
		if err != nil {
			return nil, err
		}
		switch field {
		case rspValue:
			return decodeValueResponse(inner)
		case rspOK:
			return decodeOkResponse(inner)
		case rspDeleted:
			return &tcpResponse{kind: respDeleted}, nil
		case rspNotFound:
			return &tcpResponse{kind: respNotFound}, nil
		case rspPong:
			return &tcpResponse{kind: respPong}, nil
		case rspAuthOK:
			return &tcpResponse{kind: respAuthOK}, nil
		case rspError:
			return decodeErrorResponse(inner)
		case rspWatching:
			return &tcpResponse{kind: respWatching}, nil
		case rspUnwatched:
			return &tcpResponse{kind: respUnwatched}, nil
		case rspWatchEvent:
			return decodeWatchEvent(inner)
		case rspPatternDeleted:
			c, err := decodeCount(inner)
			if err != nil {
				return nil, err
			}
			return &tcpResponse{kind: respPatternDeleted, count: c}, nil
		case rspPatternTTLUpdated:
			c, err := decodeCount(inner)
			if err != nil {
				return nil, err
			}
			return &tcpResponse{kind: respPatternTTLUpdated, count: c}, nil
		case rspSetNX:
			return decodeSetNXResponse(inner)
		case rspCounter:
			return decodeCounterResponse(inner)
		}
	}
	return nil, fmt.Errorf("ClientResponse oneof has no active field")
}

func decodeValueResponse(buf []byte) (*tcpResponse, error) {
	r := &reader{buf: buf}
	out := &tcpResponse{kind: respValue}
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return nil, err
		}
		switch {
		case field == valKey && wire == wtLD:
			b, err := r.readLD()
			if err != nil {
				return nil, err
			}
			out.key = string(b)
		case field == valValue && wire == wtLD:
			b, err := r.readLD()
			if err != nil {
				return nil, err
			}
			out.value = append([]byte(nil), b...)
		case field == valVersion && wire == wtVarint:
			v, err := r.readVarint()
			if err != nil {
				return nil, err
			}
			out.version = v
		default:
			if err := r.skip(wire); err != nil {
				return nil, err
			}
		}
	}
	return out, nil
}

func decodeOkResponse(buf []byte) (*tcpResponse, error) {
	r := &reader{buf: buf}
	out := &tcpResponse{kind: respOK}
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return nil, err
		}
		if field == vrVersion && wire == wtVarint {
			v, err := r.readVarint()
			if err != nil {
				return nil, err
			}
			out.version = v
		} else {
			if err := r.skip(wire); err != nil {
				return nil, err
			}
		}
	}
	return out, nil
}

func decodeErrorResponse(buf []byte) (*tcpResponse, error) {
	r := &reader{buf: buf}
	codeIdx := uint64(0)
	message := ""
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return nil, err
		}
		switch {
		case field == errCodeField && wire == wtVarint:
			v, err := r.readVarint()
			if err != nil {
				return nil, err
			}
			codeIdx = v
		case field == errMessage && wire == wtLD:
			b, err := r.readLD()
			if err != nil {
				return nil, err
			}
			message = string(b)
		default:
			if err := r.skip(wire); err != nil {
				return nil, err
			}
		}
	}
	codes := []string{
		ErrNodeInactive,
		ErrNoQuorum,
		ErrKeyNotFound,
		ErrInternalError,
		ErrWriteTimeout,
		ErrValueTooLarge,
		ErrKeyLimitReached,
		ErrRateLimited,
		ErrCircuitOpen,
		ErrNamespaceQuotaExceeded,
		ErrAuthFailed,
		ErrUnsupportedRequest,
		ErrTypeMismatch,
		ErrOverflow,
	}
	code := ErrInternalError
	if codeIdx < uint64(len(codes)) {
		code = codes[codeIdx]
	}
	return &tcpResponse{kind: respError, code: code, message: message}, nil
}

func decodeSetNXResponse(buf []byte) (*tcpResponse, error) {
	r := &reader{buf: buf}
	out := &tcpResponse{kind: respSetNX}
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return nil, err
		}
		switch {
		case field == snxCreated && wire == wtVarint:
			v, err := r.readVarint()
			if err != nil {
				return nil, err
			}
			out.created = v != 0
		case field == snxVersion && wire == wtVarint:
			v, err := r.readVarint()
			if err != nil {
				return nil, err
			}
			out.version = v
		default:
			if err := r.skip(wire); err != nil {
				return nil, err
			}
		}
	}
	return out, nil
}

func decodeCounterResponse(buf []byte) (*tcpResponse, error) {
	r := &reader{buf: buf}
	out := &tcpResponse{kind: respCounter}
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return nil, err
		}
		switch {
		case field == counterValue && wire == wtVarint:
			v, err := r.readVarint()
			if err != nil {
				return nil, err
			}
			out.counter = int64(v)
		case field == counterVersion && wire == wtVarint:
			v, err := r.readVarint()
			if err != nil {
				return nil, err
			}
			out.version = v
		default:
			if err := r.skip(wire); err != nil {
				return nil, err
			}
		}
	}
	return out, nil
}

func decodeWatchEvent(buf []byte) (*tcpResponse, error) {
	r := &reader{buf: buf}
	out := &tcpResponse{kind: respWatchEvent}
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return nil, err
		}
		switch {
		case field == weKey && wire == wtLD:
			b, err := r.readLD()
			if err != nil {
				return nil, err
			}
			out.key = string(b)
		case field == weValue && wire == wtLD:
			b, err := r.readLD()
			if err != nil {
				return nil, err
			}
			val, err := decodeOptionalBytes(b)
			if err != nil {
				return nil, err
			}
			out.value = val
			out.hasValue = true
		case field == weVersion && wire == wtVarint:
			v, err := r.readVarint()
			if err != nil {
				return nil, err
			}
			out.version = v
		default:
			if err := r.skip(wire); err != nil {
				return nil, err
			}
		}
	}
	return out, nil
}

func decodeOptionalBytes(buf []byte) ([]byte, error) {
	r := &reader{buf: buf}
	out := []byte{}
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return nil, err
		}
		if field == optValue && wire == wtLD {
			b, err := r.readLD()
			if err != nil {
				return nil, err
			}
			out = append([]byte(nil), b...)
		} else {
			if err := r.skip(wire); err != nil {
				return nil, err
			}
		}
	}
	return out, nil
}

func decodeCount(buf []byte) (uint64, error) {
	r := &reader{buf: buf}
	count := uint64(0)
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return 0, err
		}
		if field == countField && wire == wtVarint {
			v, err := r.readVarint()
			if err != nil {
				return 0, err
			}
			count = v
		} else {
			if err := r.skip(wire); err != nil {
				return 0, err
			}
		}
	}
	return count, nil
}

// ---------------------------------------------------------------------------
// Test helpers (used by tests to forge server-style frames).
// ---------------------------------------------------------------------------

// frameClientResponse wraps an inner ClientResponse oneof variant in Envelope +
// 4-byte BE length frame.
func frameClientResponse(variantField int, inner []byte) []byte {
	responseBytes := appendLDFieldAlways(nil, variantField, inner)

	envelope := appendEnumField(nil, envVersion, protocolVersion)
	envelope = appendLDFieldAlways(envelope, envClientResponse, responseBytes)

	out := make([]byte, 4+len(envelope))
	binary.BigEndian.PutUint32(out[:4], uint32(len(envelope)))
	copy(out[4:], envelope)
	return out
}

func encodeErrorResponseInner(codeIdx int, message string) []byte {
	buf := appendEnumField(nil, errCodeField, codeIdx)
	return appendStringField(buf, errMessage, message)
}

func encodeVersionResponseInner(version uint64) []byte {
	return appendUint64Field(nil, vrVersion, version)
}

func encodeWatchEventInner(key string, value []byte, hasValue bool, version uint64) []byte {
	buf := appendStringField(nil, weKey, key)
	if hasValue {
		opt := appendBytesField(nil, optValue, value)
		buf = appendLDFieldAlways(buf, weValue, opt)
	}
	return appendUint64Field(buf, weVersion, version)
}

// decodeClientRequestVariant parses an Envelope payload (no length prefix) and
// returns the active ClientRequest oneof field number plus its inner buffer.
func decodeClientRequestVariant(payload []byte) (int, []byte, error) {
	env := &reader{buf: payload}
	var requestBytes []byte
	for env.remaining() > 0 {
		field, wire, err := env.readTag()
		if err != nil {
			return 0, nil, err
		}
		if field == envClientRequest && wire == wtLD {
			b, err := env.readLD()
			if err != nil {
				return 0, nil, err
			}
			requestBytes = b
		} else {
			if err := env.skip(wire); err != nil {
				return 0, nil, err
			}
		}
	}
	if requestBytes == nil {
		return 0, nil, fmt.Errorf("envelope is missing client_request payload")
	}
	r := &reader{buf: requestBytes}
	for r.remaining() > 0 {
		field, wire, err := r.readTag()
		if err != nil {
			return 0, nil, err
		}
		if wire != wtLD {
			if err := r.skip(wire); err != nil {
				return 0, nil, err
			}
			continue
		}
		inner, err := r.readLD()
		if err != nil {
			return 0, nil, err
		}
		return field, inner, nil
	}
	return 0, nil, fmt.Errorf("ClientRequest oneof has no active field")
}
