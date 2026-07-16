package ditto

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func TestTCPClientAutoReconnectPing(t *testing.T) {
	cases := []struct {
		name          string
		autoReconnect bool
		wantOK        bool
	}{
		{name: "disabled", autoReconnect: false, wantOK: false},
		{name: "enabled", autoReconnect: true, wantOK: true},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ln, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatalf("listen failed: %v", err)
			}
			defer ln.Close()

			readVariant := func(conn net.Conn) (int, error) {
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

			done := make(chan error, 1)
			go func() {
				// First connection: read request and drop connection before response.
				conn1, err := ln.Accept()
				if err != nil {
					done <- err
					return
				}
				if _, err := readVariant(conn1); err != nil {
					_ = conn1.Close()
					done <- err
					return
				}
				_ = conn1.Close()

				// Second connection: serve pong if client reconnects.
				if tcpLn, ok := ln.(*net.TCPListener); ok {
					_ = tcpLn.SetDeadline(time.Now().Add(500 * time.Millisecond))
				}
				conn2, err := ln.Accept()
				if err != nil {
					if tc.autoReconnect {
						done <- err
					} else {
						done <- nil
					}
					return
				}
				defer conn2.Close()
				if _, err := readVariant(conn2); err != nil {
					done <- err
					return
				}
				_, err = conn2.Write(frameClientResponse(rspPong, nil))
				done <- err
			}()

			addr := ln.Addr().(*net.TCPAddr)
			client := NewTCPClient(TCPClientOptions{
				Host:          "127.0.0.1",
				Port:          addr.Port,
				Timeout:       2 * time.Second,
				AutoReconnect: tc.autoReconnect,
			})
			defer func() { _ = client.Close() }()

			if err := client.Connect(); err != nil {
				t.Fatalf("connect failed: %v", err)
			}

			ok, err := client.Ping()
			if tc.wantOK {
				if err != nil || !ok {
					t.Fatalf("expected reconnect ping success, got ok=%v err=%v", ok, err)
				}
			} else {
				if err == nil {
					t.Fatalf("expected ping error when autoReconnect is disabled")
				}
			}

			if err := <-done; err != nil {
				t.Fatalf("mock server failed: %v", err)
			}
		})
	}
}

func TestTCPClientAutoReconnectDoesNotRetryMutation(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	defer ln.Close()

	readVariant := func(conn net.Conn) (int, error) {
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

	done := make(chan error, 1)
	go func() {
		first, err := ln.Accept()
		if err != nil {
			done <- err
			return
		}
		field, readErr := readVariant(first)
		_ = first.Close()
		if readErr != nil {
			done <- readErr
			return
		}
		if field != reqIncr {
			done <- fmt.Errorf("expected INCR request, got variant %d", field)
			return
		}

		if tcpLn, ok := ln.(*net.TCPListener); ok {
			_ = tcpLn.SetDeadline(time.Now().Add(500 * time.Millisecond))
		}
		second, acceptErr := ln.Accept()
		if acceptErr == nil {
			_ = second.Close()
			done <- fmt.Errorf("mutation was retried on a second connection")
			return
		}
		if netErr, ok := acceptErr.(net.Error); !ok || !netErr.Timeout() {
			done <- acceptErr
			return
		}
		done <- nil
	}()

	addr := ln.Addr().(*net.TCPAddr)
	client := NewTCPClient(TCPClientOptions{
		Host:          "127.0.0.1",
		Port:          addr.Port,
		Timeout:       2 * time.Second,
		AutoReconnect: true,
	})
	defer func() { _ = client.Close() }()
	if err := client.Connect(); err != nil {
		t.Fatalf("connect failed: %v", err)
	}

	_, err = client.Incr("counter", 1, 0)
	if err == nil || !strings.Contains(err.Error(), "outcome unknown") {
		t.Fatalf("expected outcome unknown error, got %v", err)
	}
	if err := <-done; err != nil {
		t.Fatalf("mock server failed: %v", err)
	}
}
