package ditto

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
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

			done := make(chan error, 1)
			go func() {
				// First connection: read request and drop connection before response.
				conn1, err := ln.Accept()
				if err != nil {
					done <- err
					return
				}
				head := make([]byte, 4)
				if _, err := io.ReadFull(conn1, head); err != nil {
					_ = conn1.Close()
					done <- err
					return
				}
				n := binary.BigEndian.Uint32(head)
				payload := make([]byte, n)
				if _, err := io.ReadFull(conn1, payload); err != nil {
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
				if _, err := io.ReadFull(conn2, head); err != nil {
					done <- err
					return
				}
				n = binary.BigEndian.Uint32(head)
				payload = make([]byte, n)
				if _, err := io.ReadFull(conn2, payload); err != nil {
					done <- err
					return
				}

				var b bytes.Buffer
				_ = binary.Write(&b, binary.LittleEndian, uint32(4)) // Pong
				reply := b.Bytes()
				frame := make([]byte, 4+len(reply))
				binary.BigEndian.PutUint32(frame[:4], uint32(len(reply)))
				copy(frame[4:], reply)
				_, err = conn2.Write(frame)
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
