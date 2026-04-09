package ditto

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestDecodeResponseErrorCodeMappingIncludesRateLimitAndCircuitOpen(t *testing.T) {
	tests := []struct {
		idx  uint32
		want string
	}{
		{idx: 7, want: ErrRateLimited},
		{idx: 8, want: ErrCircuitOpen},
		{idx: 9, want: ErrAuthFailed},
	}

	for _, tc := range tests {
		var payload bytes.Buffer
		_ = binary.Write(&payload, binary.LittleEndian, uint32(6))
		_ = binary.Write(&payload, binary.LittleEndian, tc.idx)
		msg := []byte("x")
		_ = binary.Write(&payload, binary.LittleEndian, uint64(len(msg)))
		_, _ = payload.Write(msg)

		resp, err := decodeResponse(payload.Bytes())
		if err != nil {
			t.Fatalf("decodeResponse failed: %v", err)
		}
		if resp.kind != respError {
			t.Fatalf("unexpected kind: %v", resp.kind)
		}
		if resp.code != tc.want {
			t.Fatalf("idx=%d mapped to %q, want %q", tc.idx, resp.code, tc.want)
		}
	}
}

func TestDecodeResponseWatchEventWithNoneAndSomeValue(t *testing.T) {
	buildPayload := func(hasValue bool) []byte {
		var payload bytes.Buffer
		_ = binary.Write(&payload, binary.LittleEndian, uint32(9))
		key := []byte("watched-key")
		_ = binary.Write(&payload, binary.LittleEndian, uint64(len(key)))
		_, _ = payload.Write(key)
		if hasValue {
			_ = payload.WriteByte(1)
			val := []byte("value")
			_ = binary.Write(&payload, binary.LittleEndian, uint64(len(val)))
			_, _ = payload.Write(val)
		} else {
			_ = payload.WriteByte(0)
		}
		_ = binary.Write(&payload, binary.LittleEndian, uint64(42))
		return payload.Bytes()
	}

	respNone, err := decodeResponse(buildPayload(false))
	if err != nil {
		t.Fatalf("decodeResponse (none) failed: %v", err)
	}
	if respNone.kind != respWatchEvent || respNone.hasValue {
		t.Fatalf("unexpected watch none response: kind=%v hasValue=%v", respNone.kind, respNone.hasValue)
	}

	respSome, err := decodeResponse(buildPayload(true))
	if err != nil {
		t.Fatalf("decodeResponse (some) failed: %v", err)
	}
	if respSome.kind != respWatchEvent || !respSome.hasValue {
		t.Fatalf("unexpected watch some response: kind=%v hasValue=%v", respSome.kind, respSome.hasValue)
	}
	if string(respSome.value) != "value" {
		t.Fatalf("unexpected watch value: %q", string(respSome.value))
	}
}
