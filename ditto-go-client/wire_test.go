package ditto

import (
	"testing"
)

func TestDecodeResponseErrorCodeMappingIncludesRateLimitAndCircuitOpen(t *testing.T) {
	tests := []struct {
		idx  int
		want string
	}{
		{idx: 7, want: ErrRateLimited},
		{idx: 8, want: ErrCircuitOpen},
		{idx: 9, want: ErrNamespaceQuotaExceeded},
		{idx: 10, want: ErrAuthFailed},
		{idx: 11, want: ErrUnsupportedRequest},
		{idx: 12, want: ErrTypeMismatch},
		{idx: 13, want: ErrOverflow},
	}

	for _, tc := range tests {
		framed := frameClientResponse(rspError, encodeErrorResponseInner(tc.idx, "x"))
		// frameClientResponse prepends a 4-byte BE length; decodeResponse expects the
		// envelope payload, so strip the length prefix here.
		payload := framed[4:]

		resp, err := decodeResponse(payload)
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

func TestDecodeResponseFallsBackToInternalErrorForUnknownCodeIndex(t *testing.T) {
	framed := frameClientResponse(rspError, encodeErrorResponseInner(99, "mystery"))
	resp, err := decodeResponse(framed[4:])
	if err != nil {
		t.Fatalf("decodeResponse failed: %v", err)
	}
	if resp.kind != respError {
		t.Fatalf("unexpected kind: %v", resp.kind)
	}
	if resp.code != ErrInternalError {
		t.Fatalf("unknown index mapped to %q, want %q", resp.code, ErrInternalError)
	}
	if resp.message != "mystery" {
		t.Fatalf("unexpected message: %q", resp.message)
	}
}

func TestDecodeResponseWatchEventWithNoneAndSomeValue(t *testing.T) {
	build := func(hasValue bool) []byte {
		var inner []byte
		if hasValue {
			inner = encodeWatchEventInner("watched-key", []byte("value"), true, 42)
		} else {
			inner = encodeWatchEventInner("watched-key", nil, false, 42)
		}
		return frameClientResponse(rspWatchEvent, inner)[4:]
	}

	respNone, err := decodeResponse(build(false))
	if err != nil {
		t.Fatalf("decodeResponse (none) failed: %v", err)
	}
	if respNone.kind != respWatchEvent || respNone.hasValue {
		t.Fatalf("unexpected watch none response: kind=%v hasValue=%v", respNone.kind, respNone.hasValue)
	}

	respSome, err := decodeResponse(build(true))
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
