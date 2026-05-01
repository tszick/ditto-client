package ditto

import (
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
	}
	want := []int{reqAuth, reqSet, reqGet, reqDelete, reqDeleteByPattern, reqSetTTLByPattern}
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
