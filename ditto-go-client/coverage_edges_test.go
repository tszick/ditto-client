package ditto

import (
	"encoding/binary"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestErrorAndHTTPMappingEdges(t *testing.T) {
	var nilErr *DittoError
	if nilErr.Error() != "<nil>" {
		t.Fatalf("nil error string mismatch: %q", nilErr.Error())
	}
	if got := (&DittoError{Message: "plain"}).Error(); got != "plain" {
		t.Fatalf("plain error string mismatch: %q", got)
	}
	for status, want := range map[int]string{
		503: ErrNodeInactive,
		504: ErrWriteTimeout,
		404: ErrKeyNotFound,
		418: ErrInternalError,
	} {
		if got := httpStatusToCode(status); got != want {
			t.Fatalf("status %d mapped to %q, want %q", status, got, want)
		}
	}
	if err := parseHTTPError(204, nil); err != nil {
		t.Fatalf("2xx should not be an error: %v", err)
	}
	if err := parseHTTPError(500, []byte(`{"error":"FutureCode"}`)); err == nil || !strings.Contains(err.Error(), "FutureCode") {
		t.Fatalf("expected payload error fallback, got %v", err)
	}
	if err := parseHTTPError(500, []byte(`not json`)); err == nil || !strings.Contains(err.Error(), "not json") {
		t.Fatalf("expected raw-body error, got %v", err)
	}
}

func TestWireDecoderSimpleVariantsAndMalformedPayloads(t *testing.T) {
	simple := []struct {
		field int
		kind  responseKind
	}{
		{rspDeleted, respDeleted},
		{rspNotFound, respNotFound},
		{rspPong, respPong},
		{rspAuthOK, respAuthOK},
		{rspWatching, respWatching},
		{rspUnwatched, respUnwatched},
	}
	for _, tc := range simple {
		resp, err := decodeResponse(frameClientResponse(tc.field, nil)[4:])
		if err != nil {
			t.Fatalf("decode simple field %d: %v", tc.field, err)
		}
		if resp.kind != tc.kind {
			t.Fatalf("field %d kind=%v want %v", tc.field, resp.kind, tc.kind)
		}
	}

	for _, tc := range []struct {
		field int
		kind  responseKind
		count uint64
	}{
		{rspPatternDeleted, respPatternDeleted, 3},
		{rspPatternTTLUpdated, respPatternTTLUpdated, 4},
	} {
		resp, err := decodeResponse(frameClientResponse(tc.field, countResponseInner(tc.count))[4:])
		if err != nil {
			t.Fatalf("decode count field %d: %v", tc.field, err)
		}
		if resp.kind != tc.kind || resp.count != tc.count {
			t.Fatalf("field %d resp=%+v", tc.field, resp)
		}
	}

	value, err := decodeResponse(frameClientResponse(rspValue, valueResponseInner("k", "v", 9))[4:])
	if err != nil || value.kind != respValue || value.key != "k" || string(value.value) != "v" || value.version != 9 {
		t.Fatalf("unexpected value response: %+v err=%v", value, err)
	}

	badVersion := appendEnumField(nil, envVersion, 99)
	badVersion = appendLDFieldAlways(badVersion, envClientResponse, nil)
	if _, err := decodeResponse(badVersion); err == nil || !strings.Contains(err.Error(), "unsupported protocol version") {
		t.Fatalf("expected version error, got %v", err)
	}
	if _, err := decodeResponse([]byte{0x08}); err == nil || !strings.Contains(err.Error(), "truncated") {
		t.Fatalf("expected truncated error, got %v", err)
	}
	if _, err := decodeResponse(appendTag(nil, 99, 7)); err == nil || !strings.Contains(err.Error(), "unsupported wire type") {
		t.Fatalf("expected unsupported wire error, got %v", err)
	}
}

func tcpClientForOneResponse(t *testing.T, response []byte) *TCPClient {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_, _ = readClientVariant(conn)
		_, _ = conn.Write(response)
	}()
	addr := ln.Addr().(*net.TCPAddr)
	return NewTCPClient(TCPClientOptions{
		Host:    "127.0.0.1",
		Port:    addr.Port,
		Timeout: time.Second,
	})
}

func tcpClientWithImmediateResponse(t *testing.T, response []byte) *TCPClient {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_, _ = conn.Write(response)
	}()
	addr := ln.Addr().(*net.TCPAddr)
	return NewTCPClient(TCPClientOptions{
		Host:    "127.0.0.1",
		Port:    addr.Port,
		Timeout: time.Second,
	})
}

func TestTCPClientResponseBranches(t *testing.T) {
	if got, err := tcpClientForOneResponse(t, frameClientResponse(rspNotFound, nil)).Get("missing"); err != nil || got != nil {
		t.Fatalf("get not found=%+v err=%v", got, err)
	}
	if deleted, err := tcpClientForOneResponse(t, frameClientResponse(rspNotFound, nil)).Delete("missing"); err != nil || deleted {
		t.Fatalf("delete not found=%v err=%v", deleted, err)
	}
	if _, err := tcpClientForOneResponse(t, frameClientResponse(rspError, encodeErrorResponseInner(7, "slow"))).Get("k"); err == nil || !strings.Contains(err.Error(), "slow") {
		t.Fatalf("expected get error, got %v", err)
	}
	if _, err := tcpClientForOneResponse(t, frameClientResponse(rspPong, nil)).SetString("k", "v"); err == nil || !strings.Contains(err.Error(), "unexpected") {
		t.Fatalf("expected unexpected set response, got %v", err)
	}
	if _, err := tcpClientForOneResponse(t, frameClientResponse(rspError, encodeErrorResponseInner(8, "open"))).DeleteByPattern("k*"); err == nil || !strings.Contains(err.Error(), "open") {
		t.Fatalf("expected pattern error, got %v", err)
	}
	if _, err := tcpClientForOneResponse(t, frameClientResponse(rspPong, nil)).SetTtlByPattern("k*", 0); err == nil || !strings.Contains(err.Error(), "unexpected") {
		t.Fatalf("expected ttl unexpected response, got %v", err)
	}
	if err := tcpClientForOneResponse(t, frameClientResponse(rspPong, nil)).Watch("k"); err == nil || !strings.Contains(err.Error(), "unexpected") {
		t.Fatalf("expected watch unexpected response, got %v", err)
	}
	if err := tcpClientForOneResponse(t, frameClientResponse(rspError, encodeErrorResponseInner(3, "unwatch failed"))).Unwatch("k"); err == nil || !strings.Contains(err.Error(), "unwatch failed") {
		t.Fatalf("expected unwatch error, got %v", err)
	}
	if _, err := tcpClientWithImmediateResponse(t, frameClientResponse(rspError, encodeErrorResponseInner(3, "watch failed"))).WaitWatchEvent(); err == nil || !strings.Contains(err.Error(), "watch failed") {
		t.Fatalf("expected watch event error, got %v", err)
	}
}

func tcpAuthServer(t *testing.T, response []byte) (host string, port int, closeFn func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_, _ = readClientVariant(conn)
		_, _ = conn.Write(response)
	}()
	addr := ln.Addr().(*net.TCPAddr)
	return "127.0.0.1", addr.Port, func() {
		_ = ln.Close()
		<-done
	}
}

func tcpSequenceClient(t *testing.T, responses ...[]byte) *TCPClient {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		for _, response := range responses {
			_, _ = readClientVariant(conn)
			_, _ = conn.Write(response)
		}
	}()
	addr := ln.Addr().(*net.TCPAddr)
	return NewTCPClient(TCPClientOptions{
		Host:      "127.0.0.1",
		Port:      addr.Port,
		AuthToken: "token",
		Timeout:   time.Second,
	})
}

func TestTCPClientAuthAndFrameErrorBranches(t *testing.T) {
	host, port, closeFn := tcpAuthServer(t, frameClientResponse(rspError, encodeErrorResponseInner(10, "bad token")))
	client := NewTCPClient(TCPClientOptions{Host: host, Port: port, AuthToken: "bad", Timeout: time.Second})
	if err := client.Connect(); err == nil || !strings.Contains(err.Error(), "bad token") {
		t.Fatalf("expected auth error, got %v", err)
	}
	closeFn()

	host, port, closeFn = tcpAuthServer(t, frameClientResponse(rspPong, nil))
	client = NewTCPClient(TCPClientOptions{Host: host, Port: port, AuthToken: "odd", Timeout: time.Second})
	if err := client.Connect(); err == nil || !strings.Contains(err.Error(), "unexpected auth response") {
		t.Fatalf("expected unexpected auth response, got %v", err)
	}
	closeFn()

	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	client = NewTCPClient(TCPClientOptions{MaxFrameBytes: 1, Timeout: time.Second})
	client.conn = left
	go func() {
		var head [4]byte
		binary.BigEndian.PutUint32(head[:], 2)
		_, _ = right.Write(head[:])
		_, _ = right.Write([]byte{1, 2})
	}()
	if _, err := client.readResponseLocked(); err == nil || !strings.Contains(err.Error(), "incoming frame too large") {
		t.Fatalf("expected frame size error, got %v", err)
	}
}

func TestTCPEnsureConnectedAuthBranches(t *testing.T) {
	client := tcpSequenceClient(
		t,
		frameClientResponse(rspAuthOK, nil),
		frameClientResponse(rspPong, nil),
	)
	ok, err := client.Ping()
	if err != nil || !ok {
		t.Fatalf("expected authenticated ping, ok=%v err=%v", ok, err)
	}

	client = tcpSequenceClient(t, frameClientResponse(rspError, encodeErrorResponseInner(10, "auth denied")))
	if ok, err := client.Ping(); err == nil || ok || !strings.Contains(err.Error(), "auth denied") {
		t.Fatalf("expected ensureConnected auth error, ok=%v err=%v", ok, err)
	}

	client = tcpSequenceClient(t, frameClientResponse(rspPong, nil))
	if ok, err := client.Ping(); err == nil || ok || !strings.Contains(err.Error(), "unexpected auth response") {
		t.Fatalf("expected ensureConnected unexpected auth response, ok=%v err=%v", ok, err)
	}
}

func TestBuildTLSConfigRejectsInvalidCACert(t *testing.T) {
	client := NewTCPClient(TCPClientOptions{
		Host:      "127.0.0.1",
		Port:      7777,
		TLS:       true,
		TLSCACert: "-----BEGIN CERTIFICATE-----\nnot-base64\n-----END CERTIFICATE-----",
	})
	if _, err := client.buildTLSConfig(); err == nil || !strings.Contains(err.Error(), "failed to parse TLS CA certificate") {
		t.Fatalf("expected TLS CA parse error, got %v", err)
	}
}

func TestHTTPClientPingFalseAndClose(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()
	u, _ := url.Parse(srv.URL)
	host, portText, _ := net.SplitHostPort(u.Host)
	var port int
	_, _ = fmt.Sscanf(portText, "%d", &port)
	client := NewHTTPClient(HTTPClientOptions{Host: host, Port: port, Timeout: time.Second})
	ok, err := client.Ping()
	if err != nil || ok {
		t.Fatalf("ping unavailable ok=%v err=%v", ok, err)
	}
	client.Close()
	if got := namespaceHeader(" "); got != nil {
		t.Fatalf("blank namespace header should be nil: %v", got)
	}
}
