package ditto

import (
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"testing"
	"time"
)

func readClientVariant(conn net.Conn) (int, error) {
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		return 0, err
	}
	n := binary.BigEndian.Uint32(head)
	payload := make([]byte, n)
	if _, err := io.ReadFull(conn, payload); err != nil {
		return 0, err
	}
	field, _, err := decodeClientRequestVariant(payload)
	return field, err
}

func valueResponseInner(key, value string, version uint64) []byte {
	buf := appendStringField(nil, valKey, key)
	buf = appendBytesField(buf, valValue, []byte(value))
	return appendUint64Field(buf, valVersion, version)
}

func countResponseInner(count uint64) []byte {
	return appendUint64Field(nil, countField, count)
}

func setNXResponseInner(created bool, version uint64) []byte {
	buf := appendUint64Field(nil, snxCreated, map[bool]uint64{false: 0, true: 1}[created])
	return appendUint64Field(buf, snxVersion, version)
}

func counterResponseInner(value int64, version uint64) []byte {
	buf := appendInt64Field(nil, counterValue, value)
	return appendUint64Field(buf, counterVersion, version)
}

func TestTCPClientFullCommandSurface(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	defer ln.Close()

	responses := map[int][]byte{
		reqAuth:             frameClientResponse(rspAuthOK, nil),
		reqSet:              frameClientResponse(rspOK, encodeVersionResponseInner(7)),
		reqGet:              frameClientResponse(rspValue, valueResponseInner("ns-key", "value", 8)),
		reqDelete:           frameClientResponse(rspDeleted, nil),
		reqDeleteByPattern:  frameClientResponse(rspPatternDeleted, countResponseInner(3)),
		reqSetTTLByPattern:  frameClientResponse(rspPatternTTLUpdated, countResponseInner(4)),
		reqSetNX:            frameClientResponse(rspSetNX, setNXResponseInner(true, 9)),
		reqIncr:             frameClientResponse(rspCounter, counterResponseInner(11, 12)),
	}
	want := []int{reqAuth, reqSet, reqGet, reqDelete, reqDeleteByPattern, reqSetTTLByPattern, reqSetNX, reqIncr}
	done := make(chan error, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			done <- err
			return
		}
		defer conn.Close()

		for _, expected := range want {
			got, err := readClientVariant(conn)
			if err != nil {
				done <- err
				return
			}
			if got != expected {
				done <- fmt.Errorf("request variant got %d, want %d", got, expected)
				return
			}
			if _, err := conn.Write(responses[got]); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()

	addr := ln.Addr().(*net.TCPAddr)
	client := NewTCPClient(TCPClientOptions{
		Host:       "127.0.0.1",
		Port:       addr.Port,
		AuthToken:  "token",
		Timeout:    2 * time.Second,
		StrictMode: true,
	})
	defer func() { _ = client.Close() }()

	if err := client.Connect(); err != nil {
		t.Fatalf("connect failed: %v", err)
	}
	set, err := client.SetStringInNamespace("ns-key", "value", "tenant-a", 30)
	if err != nil || set.Version != 7 {
		t.Fatalf("set namespace=%+v err=%v", set, err)
	}
	got, err := client.Get("ns-key", "tenant-a")
	if err != nil || got == nil || string(got.Value) != "value" || got.Version != 8 {
		t.Fatalf("get=%+v err=%v", got, err)
	}
	deleted, err := client.Delete("ns-key", "tenant-a")
	if err != nil || !deleted {
		t.Fatalf("delete=%v err=%v", deleted, err)
	}
	pattern, err := client.DeleteByPattern("tenant:*", "tenant-a")
	if err != nil || pattern.Deleted != 3 {
		t.Fatalf("delete pattern=%+v err=%v", pattern, err)
	}
	ttl, err := client.SetTtlByPattern("tenant:*", 45, "tenant-a")
	if err != nil || ttl.Updated != 4 {
		t.Fatalf("ttl pattern=%+v err=%v", ttl, err)
	}
	setNx, err := client.SetNX("lease-key", []byte{1, 2, 3}, 30, "tenant-a")
	if err != nil || !setNx.Created || setNx.Version != 9 {
		t.Fatalf("setnx=%+v err=%v", setNx, err)
	}
	counter, err := client.Incr("counter", 11, 0, "tenant-a")
	if err != nil || counter.Value != 11 || counter.Version != 12 {
		t.Fatalf("incr=%+v err=%v", counter, err)
	}

	if err := <-done; err != nil {
		t.Fatalf("mock server failed: %v", err)
	}
}

func TestTCPClientErrorAndValidationBranches(t *testing.T) {
	client := NewTCPClient(TCPClientOptions{StrictMode: true, Timeout: 10 * time.Millisecond})
	if _, err := client.Get(" "); err == nil {
		t.Fatalf("expected validation error for blank key")
	}
	if _, err := client.DeleteByPattern("bad pattern*"); err == nil {
		t.Fatalf("expected validation error for invalid pattern")
	}
	assertNamespace := "tenant-a"
	if ns := normalizeNamespace(" tenant-a "); ns == nil || *ns != assertNamespace {
		t.Fatalf("unexpected normalized namespace: %v", ns)
	}
	if ns := normalizeNamespace(" "); ns != nil {
		t.Fatalf("blank lenient namespace should normalize to nil: %v", *ns)
	}
}

func TestTCPClientTLSConnectAndPing(t *testing.T) {
	testCertPEM, testKeyPEM := generateTestCertificatePEM(t)
	certificate, err := tls.X509KeyPair([]byte(testCertPEM), []byte(testKeyPEM))
	if err != nil {
		t.Fatalf("x509 key pair: %v", err)
	}
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{certificate},
		MinVersion:   tls.VersionTLS12,
	})
	if err != nil {
		t.Fatalf("tls listen failed: %v", err)
	}
	defer ln.Close()

	done := make(chan error, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			done <- err
			return
		}
		defer conn.Close()

		if got, err := readClientVariant(conn); err != nil {
			done <- err
			return
		} else if got != reqAuth {
			done <- fmt.Errorf("auth variant got %d", got)
			return
		}
		if _, err := conn.Write(frameClientResponse(rspAuthOK, nil)); err != nil {
			done <- err
			return
		}
		if got, err := readClientVariant(conn); err != nil {
			done <- err
			return
		} else if got != reqPing {
			done <- fmt.Errorf("ping variant got %d", got)
			return
		}
		_, err = conn.Write(frameClientResponse(rspPong, nil))
		done <- err
	}()

	addr := ln.Addr().(*net.TCPAddr)
	client := NewTCPClient(TCPClientOptions{
		Host:          "127.0.0.1",
		Port:          addr.Port,
		AuthToken:     "token",
		TLS:           true,
		TLSCACert:     testCertPEM,
		TLSServerName: "127.0.0.1",
		Timeout:       2 * time.Second,
	})
	defer func() { _ = client.Close() }()

	if err := client.Connect(); err != nil {
		t.Fatalf("tls connect failed: %v", err)
	}
	ok, err := client.Ping()
	if err != nil || !ok {
		t.Fatalf("tls ping ok=%v err=%v", ok, err)
	}
	if err := <-done; err != nil {
		t.Fatalf("tls mock server failed: %v", err)
	}
}
