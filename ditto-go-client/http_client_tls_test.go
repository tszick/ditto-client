package ditto

import (
	"os"
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

func TestHTTPClientTLSIgnoresExplicitInsecureOptIn(t *testing.T) {
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
		t.Fatal("expected insecure TLS opt-in to be ignored")
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

func TestHTTPClientDevInsecureTLSFlagIsIgnored(t *testing.T) {
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
	if transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("expected DevInsecureTLS=true to be ignored")
	}
}

func TestHTTPClientLoadsTrustedCertPath(t *testing.T) {
	testHTTPClientCertPEM, _ := generateTestCertificatePEM(t)
	tmpFile, err := os.CreateTemp(t.TempDir(), "ditto-ca-*.pem")
	if err != nil {
		t.Fatalf("create temp cert: %v", err)
	}
	if _, err := tmpFile.WriteString(testHTTPClientCertPEM); err != nil {
		t.Fatalf("write temp cert: %v", err)
	}
	_ = tmpFile.Close()

	client := NewHTTPClient(HTTPClientOptions{
		Host:            "localhost",
		Port:            7778,
		TLS:             true,
		TrustedCertPath: tmpFile.Name(),
	})

	transport, ok := client.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("expected *http.Transport, got %T", client.httpClient.Transport)
	}
	if transport.TLSClientConfig == nil || transport.TLSClientConfig.RootCAs == nil {
		t.Fatal("expected RootCAs to be configured from trusted cert path")
	}
}
