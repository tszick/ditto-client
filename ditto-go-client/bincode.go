package ditto

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"strings"
)

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
)

type tcpResponse struct {
	kind    responseKind
	key     string
	value   []byte
	hasValue bool
	version uint64
	code    string
	message string
	count   uint64
}

func frame(payload []byte) []byte {
	out := make([]byte, 4+len(payload))
	binary.BigEndian.PutUint32(out[0:4], uint32(len(payload)))
	copy(out[4:], payload)
	return out
}

func writeString(buf *bytes.Buffer, s string) {
	_ = binary.Write(buf, binary.LittleEndian, uint64(len(s)))
	buf.WriteString(s)
}

func writeBytes(buf *bytes.Buffer, b []byte) {
	_ = binary.Write(buf, binary.LittleEndian, uint64(len(b)))
	buf.Write(b)
}

func encodeGet(key string, namespace *string) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.LittleEndian, uint32(0))
	writeString(&b, key)
	writeOptionalString(&b, namespace)
	return frame(b.Bytes())
}

func encodeSet(key string, value []byte, ttlSecs *uint64, namespace *string) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.LittleEndian, uint32(1))
	writeString(&b, key)
	writeBytes(&b, value)
	if ttlSecs != nil && *ttlSecs > 0 {
		b.WriteByte(1)
		_ = binary.Write(&b, binary.LittleEndian, *ttlSecs)
	} else {
		b.WriteByte(0)
	}
	writeOptionalString(&b, namespace)
	return frame(b.Bytes())
}

func encodeDelete(key string, namespace *string) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.LittleEndian, uint32(2))
	writeString(&b, key)
	writeOptionalString(&b, namespace)
	return frame(b.Bytes())
}

func encodePing() []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.LittleEndian, uint32(3))
	return frame(b.Bytes())
}

func encodeAuth(token string) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.LittleEndian, uint32(4))
	writeString(&b, token)
	return frame(b.Bytes())
}

func encodeDeleteByPattern(pattern string, namespace *string) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.LittleEndian, uint32(7))
	writeString(&b, pattern)
	writeOptionalString(&b, namespace)
	return frame(b.Bytes())
}

func encodeWatch(key string, namespace *string) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.LittleEndian, uint32(5))
	writeString(&b, key)
	writeOptionalString(&b, namespace)
	return frame(b.Bytes())
}

func encodeUnwatch(key string, namespace *string) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.LittleEndian, uint32(6))
	writeString(&b, key)
	writeOptionalString(&b, namespace)
	return frame(b.Bytes())
}

func encodeSetTTLByPattern(pattern string, ttlSecs *uint64, namespace *string) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.LittleEndian, uint32(8))
	writeString(&b, pattern)
	if ttlSecs != nil && *ttlSecs > 0 {
		b.WriteByte(1)
		_ = binary.Write(&b, binary.LittleEndian, *ttlSecs)
	} else {
		b.WriteByte(0)
	}
	writeOptionalString(&b, namespace)
	return frame(b.Bytes())
}

func writeOptionalString(buf *bytes.Buffer, s *string) {
	if s == nil || strings.TrimSpace(*s) == "" {
		buf.WriteByte(0)
		return
	}
	buf.WriteByte(1)
	writeString(buf, *s)
}

func readU32LE(payload []byte, off *int) (uint32, error) {
	if len(payload) < *off+4 {
		return 0, fmt.Errorf("short payload reading u32")
	}
	v := binary.LittleEndian.Uint32(payload[*off : *off+4])
	*off += 4
	return v, nil
}

func readU64LE(payload []byte, off *int) (uint64, error) {
	if len(payload) < *off+8 {
		return 0, fmt.Errorf("short payload reading u64")
	}
	v := binary.LittleEndian.Uint64(payload[*off : *off+8])
	*off += 8
	return v, nil
}

func readString(payload []byte, off *int) (string, error) {
	n, err := readU64LE(payload, off)
	if err != nil {
		return "", err
	}
	if len(payload) < *off+int(n) {
		return "", fmt.Errorf("short payload reading string")
	}
	s := string(payload[*off : *off+int(n)])
	*off += int(n)
	return s, nil
}

func readBytes(payload []byte, off *int) ([]byte, error) {
	n, err := readU64LE(payload, off)
	if err != nil {
		return nil, err
	}
	if len(payload) < *off+int(n) {
		return nil, fmt.Errorf("short payload reading bytes")
	}
	b := append([]byte(nil), payload[*off:*off+int(n)]...)
	*off += int(n)
	return b, nil
}

func readOptionalBytes(payload []byte, off *int) ([]byte, bool, error) {
	if len(payload) < *off+1 {
		return nil, false, fmt.Errorf("short payload reading option discriminant")
	}
	tag := payload[*off]
	*off += 1
	if tag == 0 {
		return nil, false, nil
	}
	if tag != 1 {
		return nil, false, fmt.Errorf("invalid option tag: %d", tag)
	}
	b, err := readBytes(payload, off)
	if err != nil {
		return nil, false, err
	}
	return b, true, nil
}

func decodeResponse(payload []byte) (*tcpResponse, error) {
	off := 0
	variant, err := readU32LE(payload, &off)
	if err != nil {
		return nil, err
	}
	switch variant {
	case 0:
		key, err := readString(payload, &off)
		if err != nil {
			return nil, err
		}
		val, err := readBytes(payload, &off)
		if err != nil {
			return nil, err
		}
		ver, err := readU64LE(payload, &off)
		if err != nil {
			return nil, err
		}
		return &tcpResponse{kind: respValue, key: key, value: val, version: ver}, nil
	case 1:
		ver, err := readU64LE(payload, &off)
		if err != nil {
			return nil, err
		}
		return &tcpResponse{kind: respOK, version: ver}, nil
	case 2:
		return &tcpResponse{kind: respDeleted}, nil
	case 3:
		return &tcpResponse{kind: respNotFound}, nil
	case 4:
		return &tcpResponse{kind: respPong}, nil
	case 5:
		return &tcpResponse{kind: respAuthOK}, nil
	case 6:
		codeIdx, err := readU32LE(payload, &off)
		if err != nil {
			return nil, err
		}
		msg, err := readString(payload, &off)
		if err != nil {
			return nil, err
		}
		codes := []string{ErrNodeInactive, ErrNoQuorum, ErrKeyNotFound, ErrInternalError, ErrWriteTimeout, ErrValueTooLarge, ErrKeyLimitReached, ErrAuthFailed}
		code := ErrInternalError
		if int(codeIdx) < len(codes) {
			code = codes[codeIdx]
		}
		return &tcpResponse{kind: respError, code: code, message: msg}, nil
	case 7:
		return &tcpResponse{kind: respWatching}, nil
	case 8:
		return &tcpResponse{kind: respUnwatched}, nil
	case 9:
		key, err := readString(payload, &off)
		if err != nil {
			return nil, err
		}
		val, hasValue, err := readOptionalBytes(payload, &off)
		if err != nil {
			return nil, err
		}
		ver, err := readU64LE(payload, &off)
		if err != nil {
			return nil, err
		}
		return &tcpResponse{kind: respWatchEvent, key: key, value: val, hasValue: hasValue, version: ver}, nil
	case 10:
		deleted, err := readU64LE(payload, &off)
		if err != nil {
			return nil, err
		}
		return &tcpResponse{kind: respPatternDeleted, count: deleted}, nil
	case 11:
		updated, err := readU64LE(payload, &off)
		if err != nil {
			return nil, err
		}
		return &tcpResponse{kind: respPatternTTLUpdated, count: updated}, nil
	default:
		return nil, fmt.Errorf("unknown response variant: %d", variant)
	}
}
