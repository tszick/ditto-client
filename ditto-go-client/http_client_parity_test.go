package ditto

import "testing"

func TestParseHTTPErrorPrefersPayloadErrorCode(t *testing.T) {
	err := parseHTTPError(429, []byte(`{"error":"NamespaceQuotaExceeded","message":"quota hit"}`))
	if err == nil {
		t.Fatalf("expected error")
	}
	de, ok := err.(*DittoError)
	if !ok {
		t.Fatalf("expected DittoError, got %T", err)
	}
	if de.Code != "NamespaceQuotaExceeded" {
		t.Fatalf("expected NamespaceQuotaExceeded, got %s", de.Code)
	}
	if de.Message != "quota hit" {
		t.Fatalf("expected message quota hit, got %s", de.Message)
	}
}

func TestNamespaceHeaderTrimsWhitespace(t *testing.T) {
	h := namespaceHeader("  tenant-a  ")
	if h == nil || h["X-Ditto-Namespace"] != "tenant-a" {
		t.Fatalf("expected trimmed namespace header, got %#v", h)
	}

	blank := "   "
	h2 := namespaceHeaderPtr(&blank)
	if h2 != nil {
		t.Fatalf("expected nil header for blank namespace, got %#v", h2)
	}
}
