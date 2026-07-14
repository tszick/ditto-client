package ditto

import (
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

func TestTCPClientWatchSetEventUnwatchFlow(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
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

		readVariant := func() (int, error) {
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

		// 1) Watch -> Watching
		v, err := readVariant()
		if err != nil {
			done <- err
			return
		}
		if v != reqWatch {
			done <- io.ErrUnexpectedEOF
			return
		}
		if _, err := conn.Write(frameClientResponse(rspWatching, nil)); err != nil {
			done <- err
			return
		}

		// 2) Set -> Ok(version=1) + WatchEvent
		v, err = readVariant()
		if err != nil {
			done <- err
			return
		}
		if v != reqSet {
			done <- io.ErrUnexpectedEOF
			return
		}
		if _, err := conn.Write(frameClientResponse(rspOK, encodeVersionResponseInner(1))); err != nil {
			done <- err
			return
		}
		if _, err := conn.Write(frameClientResponse(rspWatchEvent, encodeWatchEventInner("k", []byte("value"), true, 2))); err != nil {
			done <- err
			return
		}

		// 3) Unwatch -> Unwatched
		v, err = readVariant()
		if err != nil {
			done <- err
			return
		}
		if v != reqUnwatch {
			done <- io.ErrUnexpectedEOF
			return
		}
		_, err = conn.Write(frameClientResponse(rspUnwatched, nil))
		done <- err
	}()

	addr := ln.Addr().(*net.TCPAddr)
	client := NewTCPClient(TCPClientOptions{
		Host:    "127.0.0.1",
		Port:    addr.Port,
		Timeout: 2 * time.Second,
	})
	defer func() { _ = client.Close() }()

	if err := client.Connect(); err != nil {
		t.Fatalf("connect failed: %v", err)
	}
	if err := client.Watch("k"); err != nil {
		t.Fatalf("watch failed: %v", err)
	}
	setRes, err := client.SetString("k", "value")
	if err != nil {
		t.Fatalf("set failed: %v", err)
	}
	if setRes.Version != 1 {
		t.Fatalf("unexpected set version: %d", setRes.Version)
	}
	ev, err := client.WaitWatchEvent()
	if err != nil {
		t.Fatalf("wait watch event failed: %v", err)
	}
	if ev.Key != "k" || string(ev.Value) != "value" || ev.Version != 2 {
		t.Fatalf("unexpected watch event: %+v", ev)
	}
	if err := client.Unwatch("k"); err != nil {
		t.Fatalf("unwatch failed: %v", err)
	}

	if err := <-done; err != nil {
		t.Fatalf("mock server failed: %v", err)
	}
}

func TestTCPClientWaitWatchEventAfterIdleStillReceivesEvent(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
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

		readVariant := func() (int, error) {
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

		v, err := readVariant()
		if err != nil {
			done <- err
			return
		}
		if v != reqWatch {
			done <- io.ErrUnexpectedEOF
			return
		}
		if _, err := conn.Write(frameClientResponse(rspWatching, nil)); err != nil {
			done <- err
			return
		}

		time.Sleep(75 * time.Millisecond)
		_, err = conn.Write(frameClientResponse(
			rspWatchEvent,
			encodeWatchEventInner("k", []byte("idle-value"), true, 3),
		))
		done <- err
	}()

	addr := ln.Addr().(*net.TCPAddr)
	client := NewTCPClient(TCPClientOptions{
		Host:        "127.0.0.1",
		Port:        addr.Port,
		ReadTimeout: 50 * time.Millisecond,
	})
	defer func() { _ = client.Close() }()

	if err := client.Connect(); err != nil {
		t.Fatalf("connect failed: %v", err)
	}
	if err := client.Watch("k"); err != nil {
		t.Fatalf("watch failed: %v", err)
	}

	time.Sleep(60 * time.Millisecond)

	ev, err := client.WaitWatchEvent()
	if err != nil {
		t.Fatalf("wait watch event failed after idle: %v", err)
	}
	if ev.Key != "k" || string(ev.Value) != "idle-value" || ev.Version != 3 {
		t.Fatalf("unexpected watch event after idle: %+v", ev)
	}

	if err := <-done; err != nil {
		t.Fatalf("mock server failed: %v", err)
	}
}
