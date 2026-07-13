package ditto

import (
	"net/http"
	"testing"
	"os"
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

const testHTTPClientCertPEM = `-----BEGIN CERTIFICATE-----
MIIC5jCCAc6gAwIBAgIBATANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDEwkxMjcu
MC4wLjEwHhcNMjYwNzEzMDgwNDI2WhcNMjYwNzE0MDkwNDI2WjAUMRIwEAYDVQQD
EwkxMjcuMC4wLjEwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC5Hc/T
72VvvrgR436eYOmPSsUjlfnB3vvnKax1Nw+nhlBYPmVJn/iNvXDulJ0HqFWLFHN1
cJysrebCDRcoFZscliEwYu7zuh3MbqTissvydiMjRK+OjrqFicvIWci3XG/oN5kN
r2gFGXZJ9hrUpjtNIBUIU9M2putkbFemRuV9czDqVTsTQWj9FefMzhOdfatcnPkc
ORCJe9cQuKynHe3Qc21b6nKSMg9RzH+bTwGFotcBlpsLWkcVM9Jodb5yL9RsRuV4
YhqsNOkaXwO0q9PmSzehkbvu2odXz/TFi7HI08RuU97dC3RsPQwVGn44l17fnZRm
RHPbxjSh4/0OXmdHAgMBAAGjQzBBMA4GA1UdDwEB/wQEAwIFoDATBgNVHSUEDDAK
BggrBgEFBQcDATAaBgNVHREEEzARgglsb2NhbGhvc3SHBH8AAAEwDQYJKoZIhvcN
AQELBQADggEBAJSpGSTK81AziK4EK5vpd1WoaKLb4G+OZsF8UwiHR/u8GR2jr4MC
V54N5FQXpINB9OABIKtBxTfrNiXWebCpLLSj42xg1qBw1D3AZ58lMqzqBR5D6ZWg
kWk29NNu2Kw9WHJvLkifCHQK6wmKEcaIfAmy+1xn0IaYiM6X+ELVWj34ri9Ja4d1
yNLBLtmrFy2LuGdoQjqB3pUKgcX6wZ6ogKmpAjpAG1phqnRCqstWEASaMqJ5xv+Z
QA16rmRMPOiirSi4e0/WUQqH1nF+6Qyi5nQLQpZPh9ds9pEnCuB6ECrXFFQPjL2y
GnjWYu2HHO1qQR7nTZ+e9TQ0sZIM4LV5qIE=
-----END CERTIFICATE-----`
