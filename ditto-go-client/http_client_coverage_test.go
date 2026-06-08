package ditto

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func newHTTPTestClient(t *testing.T, handler http.Handler) (*HTTPClient, func()) {
	t.Helper()
	srv := httptest.NewServer(handler)
	u, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatalf("parse server URL: %v", err)
	}
	host, portText, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatalf("split server host: %v", err)
	}
	var port int
	if _, err := fmt.Sscanf(portText, "%d", &port); err != nil {
		t.Fatalf("parse server port: %v", err)
	}
	client := NewHTTPClient(HTTPClientOptions{
		Host:           host,
		Port:           port,
		Timeout:        time.Second,
		Username:       "ditto",
		Password:       "secret",
		StrictMode:     true,
		ConnectTimeout: time.Second,
		RequestTimeout: time.Second,
	})
	return client, srv.Close
}

func TestHTTPClientCoversNamespacePatternStatsAndErrors(t *testing.T) {
	var seen []string
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Method+" "+r.URL.RequestURI())
		wantAuth := "Basic " + base64.StdEncoding.EncodeToString([]byte("ditto:secret"))
		if got := r.Header.Get("Authorization"); got != wantAuth {
			t.Fatalf("unexpected auth header: %q", got)
		}

		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/ping":
			_ = json.NewEncoder(w).Encode(map[string]bool{"pong": true})
		case r.Method == http.MethodPut && r.URL.Path == "/key/ns-key":
			if got := r.Header.Get("X-Ditto-Namespace"); got != "tenant-a" {
				t.Fatalf("unexpected namespace header: %q", got)
			}
			if got := r.URL.Query().Get("ttl"); got != "30" {
				t.Fatalf("unexpected ttl query: %q", got)
			}
			body, _ := io.ReadAll(r.Body)
			if string(body) != "value" {
				t.Fatalf("unexpected set body: %q", string(body))
			}
			_ = json.NewEncoder(w).Encode(map[string]uint64{"version": 7})
		case r.Method == http.MethodGet && r.URL.Path == "/key/ns-key":
			if got := r.Header.Get("X-Ditto-Namespace"); got != "tenant-a" {
				t.Fatalf("unexpected get namespace header: %q", got)
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"value": "value", "version": 7})
		case r.Method == http.MethodGet && r.URL.Path == "/key/binary":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"value":        "lossy-fallback",
				"value_base64": base64.StdEncoding.EncodeToString([]byte{0, 159, 146, 150, 255}),
				"version":      8,
			})
		case r.Method == http.MethodDelete && r.URL.Path == "/key/ns-key":
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodPost && r.URL.Path == "/key/lease-key":
			if got := r.Header.Get("X-Ditto-Namespace"); got != "tenant-a" {
				t.Fatalf("unexpected setnx namespace header: %q", got)
			}
			if got := r.URL.Query().Get("nx"); got != "1" {
				t.Fatalf("unexpected nx query: %q", got)
			}
			if got := r.URL.Query().Get("ttl"); got != "30" {
				t.Fatalf("unexpected setnx ttl query: %q", got)
			}
			body, _ := io.ReadAll(r.Body)
			if !bytes.Equal(body, []byte{1, 2, 3}) {
				t.Fatalf("unexpected setnx body: %v", body)
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"created": true, "version": "9"})
		case r.Method == http.MethodPost && r.URL.Path == "/key/counter/incr":
			if got := r.Header.Get("X-Ditto-Namespace"); got != "tenant-a" {
				t.Fatalf("unexpected incr namespace header: %q", got)
			}
			var payload map[string]any
			_ = json.NewDecoder(r.Body).Decode(&payload)
			if payload["delta"].(float64) != 11 {
				t.Fatalf("unexpected incr payload: %v", payload)
			}
			_ = json.NewEncoder(w).Encode(map[string]string{"value": "11", "version": "12"})
		case r.Method == http.MethodDelete && r.URL.Path == "/key/missing":
			w.WriteHeader(http.StatusNotFound)
		case r.Method == http.MethodPost && r.URL.Path == "/keys/delete-by-pattern":
			if got := r.Header.Get("X-Ditto-Namespace"); got != "tenant-a" {
				t.Fatalf("unexpected pattern namespace header: %q", got)
			}
			var payload map[string]string
			_ = json.NewDecoder(r.Body).Decode(&payload)
			if payload["pattern"] != "tenant:*" {
				t.Fatalf("unexpected pattern payload: %v", payload)
			}
			_ = json.NewEncoder(w).Encode(map[string]uint64{"deleted": 3})
		case r.Method == http.MethodPost && r.URL.Path == "/keys/ttl-by-pattern":
			var payload map[string]any
			_ = json.NewDecoder(r.Body).Decode(&payload)
			if payload["pattern"] != "tenant:*" || payload["ttl_secs"].(float64) != 45 {
				t.Fatalf("unexpected ttl payload: %v", payload)
			}
			_ = json.NewEncoder(w).Encode(map[string]uint64{"updated": 4})
		case r.Method == http.MethodGet && r.URL.Path == "/stats":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"node_id": "n1", "status": "active", "is_primary": true,
				"committed_index": uint64(9), "key_count": uint64(2),
				"memory_used_bytes": uint64(100), "memory_max_bytes": uint64(1000),
				"evictions": uint64(1), "hit_count": uint64(5), "miss_count": uint64(6),
				"uptime_secs": uint64(60), "value_size_limit_bytes": uint64(1024),
				"max_keys_limit": uint64(100), "compression_enabled": true,
				"compression_threshold_bytes": uint64(32), "node_name": "node-a",
				"backup_dir_bytes": uint64(77),
			})
		case r.Method == http.MethodGet && r.URL.Path == "/key/failing":
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"error":"RateLimited","message":"slow down"}`))
		default:
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.RequestURI())
		}
	})
	client, closeServer := newHTTPTestClient(t, handler)
	defer closeServer()

	if ok, err := client.Ping(); err != nil || !ok {
		t.Fatalf("ping ok=%v err=%v", ok, err)
	}
	set, err := client.SetStringInNamespace("ns-key", "value", " tenant-a ", 30)
	if err != nil || set.Version != 7 {
		t.Fatalf("set version=%v err=%v", set, err)
	}
	got, err := client.Get("ns-key", "tenant-a")
	if err != nil || got == nil || string(got.Value) != "value" || got.Version != 7 {
		t.Fatalf("get result=%+v err=%v", got, err)
	}
	binary, err := client.Get("binary")
	if err != nil || binary == nil || string(binary.Value) == "lossy-fallback" || binary.Version != 8 {
		t.Fatalf("binary get result=%+v err=%v", binary, err)
	}
	if want := []byte{0, 159, 146, 150, 255}; !bytes.Equal(binary.Value, want) {
		t.Fatalf("binary get value=%v want=%v", binary.Value, want)
	}
	if deleted, err := client.Delete("ns-key"); err != nil || !deleted {
		t.Fatalf("delete existing=%v err=%v", deleted, err)
	}
	setnx, err := client.SetNX("lease-key", []byte{1, 2, 3}, 30, "tenant-a")
	if err != nil || !setnx.Created || setnx.Version != 9 {
		t.Fatalf("setnx=%+v err=%v", setnx, err)
	}
	incr, err := client.Incr("counter", 11, 0, "tenant-a")
	if err != nil || incr.Value != 11 || incr.Version != 12 {
		t.Fatalf("incr=%+v err=%v", incr, err)
	}
	if deleted, err := client.Delete("missing"); err != nil || deleted {
		t.Fatalf("delete missing=%v err=%v", deleted, err)
	}
	pattern, err := client.DeleteByPattern("tenant:*", "tenant-a")
	if err != nil || pattern.Deleted != 3 {
		t.Fatalf("delete pattern=%+v err=%v", pattern, err)
	}
	ttl, err := client.SetTtlByPattern("tenant:*", 45)
	if err != nil || ttl.Updated != 4 {
		t.Fatalf("ttl pattern=%+v err=%v", ttl, err)
	}
	stats, err := client.Stats()
	if err != nil || stats.NodeID != "n1" || !stats.IsPrimary || stats.KeyCount != 2 {
		t.Fatalf("stats=%+v err=%v", stats, err)
	}
	_, err = client.Get("failing")
	if err == nil || !strings.Contains(err.Error(), "slow down") {
		t.Fatalf("expected payload HTTP error, got %v", err)
	}

	if len(seen) < 11 {
		t.Fatalf("expected broad HTTP exercise, saw %v", seen)
	}
}

func TestHTTPClientStrictValidationCoversCoreBranches(t *testing.T) {
	client := NewHTTPClient(HTTPClientOptions{StrictMode: true})
	if _, err := client.Get(" "); err == nil || !strings.Contains(err.Error(), "key must not be empty") {
		t.Fatalf("expected blank-key validation, got %v", err)
	}
	if _, err := client.SetString("bad key", "value"); err == nil || !strings.Contains(err.Error(), "unsupported characters") {
		t.Fatalf("expected bad key validation, got %v", err)
	}
	if _, err := client.Get("key", " "); err == nil || !strings.Contains(err.Error(), "namespace must not be blank") {
		t.Fatalf("expected blank namespace validation, got %v", err)
	}
	if _, err := client.SetStringInNamespace("key", "value", "bad::ns"); err == nil || !strings.Contains(err.Error(), "must not contain '::'") {
		t.Fatalf("expected double-colon namespace validation, got %v", err)
	}
	if _, err := client.DeleteByPattern("bad pattern*", "tenant"); err == nil || !strings.Contains(err.Error(), "pattern contains unsupported") {
		t.Fatalf("expected pattern validation, got %v", err)
	}
}
