package ditto

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	neturl "net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type contractSuite struct {
	Version string         `json:"version"`
	Suite   string         `json:"suite"`
	Cases   []contractCase `json:"cases"`
}

type contractCase struct {
	ID        string                 `json:"id"`
	Operation string                 `json:"operation"`
	Inputs    map[string]any         `json:"inputs"`
	Expect    map[string]any         `json:"expect"`
}

func TestCoreOpsContractHTTPRunner(t *testing.T) {
	contractPath := filepath.Join("..", "contracts", "core-ops.contract.json")
	raw, err := os.ReadFile(contractPath)
	if err != nil {
		t.Fatalf("read contract: %v", err)
	}

	var suite contractSuite
	if err := json.Unmarshal(raw, &suite); err != nil {
		t.Fatalf("parse contract: %v", err)
	}
	if len(suite.Cases) == 0 {
		t.Fatalf("empty contract cases")
	}

	type entry struct {
		Value   string
		Version uint64
	}
	store := map[string]entry{}

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/ping":
			_ = json.NewEncoder(w).Encode(map[string]bool{"pong": true})
			return

		case strings.HasPrefix(r.URL.Path, "/key/"):
			key := strings.TrimPrefix(r.URL.Path, "/key/")
			switch r.Method {
			case http.MethodPut:
				bodyBytes, _ := io.ReadAll(r.Body)
				prev := store[key]
				store[key] = entry{Value: string(bodyBytes), Version: prev.Version + 1}
				_ = json.NewEncoder(w).Encode(map[string]uint64{"version": store[key].Version})
				return
			case http.MethodGet:
				e, ok := store[key]
				if !ok {
					w.WriteHeader(http.StatusNotFound)
					return
				}
				_ = json.NewEncoder(w).Encode(map[string]any{"value": e.Value, "version": e.Version})
				return
			case http.MethodDelete:
				if _, ok := store[key]; !ok {
					w.WriteHeader(http.StatusNotFound)
					return
				}
				delete(store, key)
				w.WriteHeader(http.StatusNoContent)
				return
			}

		case r.Method == http.MethodPost && r.URL.Path == "/keys/delete-by-pattern":
			var in struct {
				Pattern string `json:"pattern"`
			}
			_ = json.NewDecoder(r.Body).Decode(&in)
			prefix := strings.TrimSuffix(in.Pattern, "*")
			deleted := uint64(0)
			for k := range store {
				if strings.HasPrefix(k, prefix) {
					delete(store, k)
					deleted++
				}
			}
			_ = json.NewEncoder(w).Encode(map[string]uint64{"deleted": deleted})
			return
		}

		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	u, err := neturl.Parse(srv.URL)
	if err != nil {
		t.Fatalf("parse server url: %v", err)
	}
	host, portStr, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatalf("split host/port: %v", err)
	}
	var port int
	if _, err := fmt.Sscanf(portStr, "%d", &port); err != nil {
		t.Fatalf("parse port: %v", err)
	}

	client := NewHTTPClient(HTTPClientOptions{
		Host: host,
		Port: port,
		TLS:  false,
	})

	for _, c := range suite.Cases {
		c := c
		t.Run(c.ID, func(t *testing.T) {
			switch c.Operation {
			case "ping":
				pong, err := client.Ping()
				if err != nil {
					t.Fatalf("ping failed: %v", err)
				}
				want := expectBool(t, c.Expect, "value")
				if pong != want {
					t.Fatalf("expected pong=%v, got %v", want, pong)
				}

			case "set_get":
				key := expectString(t, c.Inputs, "key")
				value := expectString(t, c.Inputs, "value")
				ttl := expectUint64OrZero(c.Inputs, "ttl_secs")
				_, err := client.SetString(key, value, ttl)
				if err != nil {
					t.Fatalf("set failed: %v", err)
				}
				got, err := client.Get(key)
				if err != nil {
					t.Fatalf("get failed: %v", err)
				}
				if got == nil {
					t.Fatalf("expected value, got nil")
				}
				want := expectString(t, c.Expect, "value_equals")
				if string(got.Value) != want {
					t.Fatalf("expected value=%q, got %q", want, string(got.Value))
				}

			case "delete":
				key := expectString(t, c.Inputs, "key")
				deleted, err := client.Delete(key)
				if err != nil {
					t.Fatalf("delete failed: %v", err)
				}
				want := expectBool(t, c.Expect, "value")
				if deleted != want {
					t.Fatalf("expected deleted=%v, got %v", want, deleted)
				}

			case "delete_by_pattern":
				store["contract:prefix:a"] = entry{Value: "a", Version: 1}
				store["contract:prefix:b"] = entry{Value: "b", Version: 1}
				pattern := expectString(t, c.Inputs, "pattern")
				out, err := client.DeleteByPattern(pattern)
				if err != nil {
					t.Fatalf("delete_by_pattern failed: %v", err)
				}
				min := expectUint64OrZero(c.Expect, "min")
				if out.Deleted < min {
					t.Fatalf("expected deleted >= %d, got %d", min, out.Deleted)
				}

			default:
				t.Fatalf("unsupported operation: %s", c.Operation)
			}
		})
	}
}

func expectString(t *testing.T, m map[string]any, key string) string {
	t.Helper()
	v, ok := m[key]
	if !ok {
		t.Fatalf("missing key %q", key)
	}
	s, ok := v.(string)
	if !ok {
		t.Fatalf("key %q must be string, got %T", key, v)
	}
	return s
}

func expectBool(t *testing.T, m map[string]any, key string) bool {
	t.Helper()
	v, ok := m[key]
	if !ok {
		t.Fatalf("missing key %q", key)
	}
	b, ok := v.(bool)
	if !ok {
		t.Fatalf("key %q must be bool, got %T", key, v)
	}
	return b
}

func expectUint64OrZero(m map[string]any, key string) uint64 {
	v, ok := m[key]
	if !ok {
		return 0
	}
	switch n := v.(type) {
	case float64:
		if n < 0 {
			return 0
		}
		return uint64(n)
	default:
		return 0
	}
}
