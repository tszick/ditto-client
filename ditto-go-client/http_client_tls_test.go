package ditto

import (
	"net/http"
	"testing"
)

func TestHTTPClientTLSDefaultsToCertificateVerification(t *testing.T) {
	client := NewHTTPClient(HTTPClientOptions{
		Host: "localhost",
		Port: 7778,
		TLS:  true,
	})

	transport, ok := client.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("expected *http.Transport, got %T", client.httpClient.Transport)
	}
	if transport.TLSClientConfig == nil {
		t.Fatal("expected TLS client config")
	}
	if transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("expected InsecureSkipVerify=false by default")
	}
}

func TestHTTPClientTLSAllowsExplicitInsecureOptIn(t *testing.T) {
	t.Setenv("DITTO_CLIENT_ALLOW_INSECURE_TLS_DEV", "true")
	client := NewHTTPClient(HTTPClientOptions{
		Host:               "localhost",
		Port:               7778,
		TLS:                true,
		InsecureSkipVerify: true,
	})

	transport, ok := client.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("expected *http.Transport, got %T", client.httpClient.Transport)
	}
	if transport.TLSClientConfig == nil {
		t.Fatal("expected TLS client config")
	}
	if !transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("expected InsecureSkipVerify=true when explicitly configured")
	}
}

func TestHTTPClientTLSIgnoresInsecureOptInWithoutDevEnv(t *testing.T) {
	t.Setenv("DITTO_CLIENT_ALLOW_INSECURE_TLS_DEV", "")
	client := NewHTTPClient(HTTPClientOptions{
		Host:               "localhost",
		Port:               7778,
		TLS:                true,
		InsecureSkipVerify: true,
	})

	transport, ok := client.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("expected *http.Transport, got %T", client.httpClient.Transport)
	}
	if transport.TLSClientConfig == nil {
		t.Fatal("expected TLS client config")
	}
	if transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("expected insecure TLS opt-in to be ignored without DITTO_CLIENT_ALLOW_INSECURE_TLS_DEV=true")
	}
}

func TestHTTPClientRejectUnauthorizedOverridesInsecureFlag(t *testing.T) {
	client := NewHTTPClient(HTTPClientOptions{
		Host:               "localhost",
		Port:               7778,
		TLS:                true,
		InsecureSkipVerify: true,
		RejectUnauthorized: true,
	})

	transport, ok := client.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("expected *http.Transport, got %T", client.httpClient.Transport)
	}
	if transport.TLSClientConfig == nil {
		t.Fatal("expected TLS client config")
	}
	if transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("expected RejectUnauthorized=true to force certificate verification")
	}
}

func TestHTTPClientDevInsecureTLSFlagEnablesInsecureMode(t *testing.T) {
	t.Setenv("DITTO_CLIENT_ALLOW_INSECURE_TLS_DEV", "true")
	client := NewHTTPClient(HTTPClientOptions{
		Host:           "localhost",
		Port:           7778,
		TLS:            true,
		DevInsecureTLS: true,
	})

	transport, ok := client.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("expected *http.Transport, got %T", client.httpClient.Transport)
	}
	if transport.TLSClientConfig == nil {
		t.Fatal("expected TLS client config")
	}
	if !transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("expected DevInsecureTLS=true to enable insecure mode")
	}
}
