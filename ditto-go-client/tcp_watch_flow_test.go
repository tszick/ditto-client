package ditto

import (
	"bytes"
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

		readVariant := func() (uint32, error) {
			head := make([]byte, 4)
			if _, err := io.ReadFull(conn, head); err != nil {
				return 0, err
			}
			n := binary.BigEndian.Uint32(head)
			payload := make([]byte, n)
			if _, err := io.ReadFull(conn, payload); err != nil {
				return 0, err
			}
			return binary.LittleEndian.Uint32(payload[:4]), nil
		}
		writePayload := func(payload []byte) error {
			frame := make([]byte, 4+len(payload))
			binary.BigEndian.PutUint32(frame[:4], uint32(len(payload)))
			copy(frame[4:], payload)
			_, err := conn.Write(frame)
			return err
		}
		writeSimple := func(variant uint32) error {
			var b bytes.Buffer
			_ = binary.Write(&b, binary.LittleEndian, variant)
			return writePayload(b.Bytes())
		}
		writeOK := func(version uint64) error {
			var b bytes.Buffer
			_ = binary.Write(&b, binary.LittleEndian, uint32(1))
			_ = binary.Write(&b, binary.LittleEndian, version)
			return writePayload(b.Bytes())
		}
		writeWatchEvent := func(key string, value []byte, version uint64) error {
			var b bytes.Buffer
			_ = binary.Write(&b, binary.LittleEndian, uint32(9))
			_ = binary.Write(&b, binary.LittleEndian, uint64(len(key)))
			_, _ = b.WriteString(key)
			_ = b.WriteByte(1)
			_ = binary.Write(&b, binary.LittleEndian, uint64(len(value)))
			_, _ = b.Write(value)
			_ = binary.Write(&b, binary.LittleEndian, version)
			return writePayload(b.Bytes())
		}

		v, err := readVariant()
		if err != nil {
			done <- err
			return
		}
		if v != 5 {
			done <- io.ErrUnexpectedEOF
			return
		}
		if err := writeSimple(7); err != nil {
			done <- err
			return
		}

		v, err = readVariant()
		if err != nil {
			done <- err
			return
		}
		if v != 1 {
			done <- io.ErrUnexpectedEOF
			return
		}
		if err := writeOK(1); err != nil {
			done <- err
			return
		}
		if err := writeWatchEvent("k", []byte("value"), 2); err != nil {
			done <- err
			return
		}

		v, err = readVariant()
		if err != nil {
			done <- err
			return
		}
		if v != 6 {
			done <- io.ErrUnexpectedEOF
			return
		}
		done <- writeSimple(8)
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
