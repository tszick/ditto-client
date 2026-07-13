package ditto

import (
	"bytes"
	"crypto/x509"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

type HTTPClientOptions struct {
	Host               string
	Port               int
	TLS                bool
	Username           string
	Password           string
	RejectUnauthorized bool
	InsecureSkipVerify bool
	DevInsecureTLS     bool
	TrustedCertPath    string
	ConnectTimeout     time.Duration
	RequestTimeout     time.Duration
	Timeout            time.Duration
	StrictMode         bool
}

type HTTPClient struct {
	baseURL    string
	httpClient *http.Client
	authHeader string
	strictMode bool
}

func NewHTTPClient(opts HTTPClientOptions) *HTTPClient {
	host := opts.Host
	if host == "" {
		host = "localhost"
	}
	port := opts.Port
	if port == 0 {
		port = 7778
	}
	scheme := "http"
	if opts.TLS {
		scheme = "https"
	}
	connectTimeout := opts.ConnectTimeout
	if connectTimeout <= 0 {
		connectTimeout = opts.Timeout
	}
	if connectTimeout <= 0 {
		connectTimeout = 10 * time.Second
	}
	requestTimeout := opts.RequestTimeout
	if requestTimeout <= 0 {
		requestTimeout = opts.Timeout
	}
	if requestTimeout <= 0 {
		requestTimeout = 10 * time.Second
	}
	tr := &http.Transport{
		DialContext: (&net.Dialer{
			Timeout: connectTimeout,
		}).DialContext,
	}
	if opts.TLS {
		if opts.InsecureSkipVerify || opts.DevInsecureTLS {
			log.Print("WARNING: insecure TLS bypass flags are no longer supported and will be ignored; use a trusted certificate configuration instead")
		}
		tlsConfig := &tls.Config{InsecureSkipVerify: false}
		if strings.TrimSpace(opts.TrustedCertPath) != "" {
			pemBytes, err := os.ReadFile(strings.TrimSpace(opts.TrustedCertPath))
			if err != nil {
				panic(fmt.Sprintf("failed to read trusted cert path %q: %v", opts.TrustedCertPath, err))
			}
			pool, err := x509.SystemCertPool()
			if err != nil || pool == nil {
				pool = x509.NewCertPool()
			}
			if !pool.AppendCertsFromPEM(pemBytes) {
				panic(fmt.Sprintf("failed to parse trusted cert path %q", opts.TrustedCertPath))
			}
			tlsConfig.RootCAs = pool
		}
		tr.TLSClientConfig = tlsConfig
	}
	c := &HTTPClient{
		baseURL: fmt.Sprintf("%s://%s:%d", scheme, host, port),
		httpClient: &http.Client{
			Timeout:   requestTimeout,
			Transport: tr,
		},
		strictMode: opts.StrictMode,
	}
	if opts.Username != "" && opts.Password != "" {
		c.authHeader = "Basic " + base64.StdEncoding.EncodeToString([]byte(opts.Username+":"+opts.Password))
	}
	return c
}

func (c *HTTPClient) Close() {}

func (c *HTTPClient) request(method, path string, body []byte, contentType string, headers map[string]string) ([]byte, int, error) {
	req, err := http.NewRequest(method, c.baseURL+path, bytes.NewReader(body))
	if err != nil {
		return nil, 0, err
	}
	if c.authHeader != "" {
		req.Header.Set("Authorization", c.authHeader)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, resp.StatusCode, err
	}
	return b, resp.StatusCode, nil
}

func httpStatusToCode(status int) string {
	switch status {
	case 503:
		return ErrNodeInactive
	case 504:
		return ErrWriteTimeout
	case 404:
		return ErrKeyNotFound
	default:
		return ErrInternalError
	}
}

func parseHTTPError(status int, body []byte) error {
	if status >= 200 && status < 300 {
		return nil
	}
	msg := string(body)
	code := httpStatusToCode(status)
	var payload struct {
		Error   string `json:"error"`
		Message string `json:"message"`
	}
	if json.Unmarshal(body, &payload) == nil {
		if payload.Error != "" {
			code = payload.Error
		}
		if payload.Message != "" {
			msg = payload.Message
		} else if payload.Error != "" {
			msg = payload.Error
		}
	}
	return &DittoError{Code: code, Message: msg}
}

func (c *HTTPClient) Ping() (bool, error) {
	b, status, err := c.request(http.MethodGet, "/ping", nil, "", nil)
	if err != nil {
		return false, err
	}
	if status != http.StatusOK {
		return false, nil
	}
	var p struct {
		Pong bool `json:"pong"`
	}
	if err := json.Unmarshal(b, &p); err != nil {
		return false, err
	}
	return p.Pong, nil
}

func (c *HTTPClient) Get(key string, namespace ...string) (*GetResult, error) {
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return nil, err
	}
	if err := validateCoreInputs(c.strictMode, "get", key, ns); err != nil {
		return nil, err
	}
	b, status, err := c.request(http.MethodGet, "/key/"+url.PathEscape(key), nil, "", namespaceHeaderPtr(ns))
	if err != nil {
		return nil, err
	}
	if status == http.StatusNotFound {
		return nil, nil
	}
	if err := parseHTTPError(status, b); err != nil {
		return nil, err
	}
	var payload struct {
		Value       string `json:"value"`
		ValueBase64 string `json:"value_base64"`
		Version     uint64 `json:"version"`
	}
	if err := json.Unmarshal(b, &payload); err != nil {
		return nil, err
	}
	value := []byte(payload.Value)
	if payload.ValueBase64 != "" {
		decoded, err := base64.StdEncoding.DecodeString(payload.ValueBase64)
		if err != nil {
			return nil, err
		}
		value = decoded
	}
	return &GetResult{Value: value, Version: payload.Version}, nil
}

func (c *HTTPClient) Set(key string, value []byte, ttlSecs ...uint64) (*SetResult, error) {
	if err := validateCoreInputs(c.strictMode, "set", key, nil); err != nil {
		return nil, err
	}
	path := "/key/" + url.PathEscape(key)
	if len(ttlSecs) > 0 && ttlSecs[0] > 0 {
		path += fmt.Sprintf("?ttl=%d", ttlSecs[0])
	}
	b, status, err := c.request(http.MethodPut, path, value, "text/plain", nil)
	if err != nil {
		return nil, err
	}
	if err := parseHTTPError(status, b); err != nil {
		return nil, err
	}
	var payload struct {
		Version uint64 `json:"version"`
	}
	if err := json.Unmarshal(b, &payload); err != nil {
		return nil, err
	}
	return &SetResult{Version: payload.Version}, nil
}

func (c *HTTPClient) SetString(key, value string, ttlSecs ...uint64) (*SetResult, error) {
	return c.Set(key, []byte(value), ttlSecs...)
}

func (c *HTTPClient) SetInNamespace(key string, value []byte, namespace string, ttlSecs ...uint64) (*SetResult, error) {
	ns := strings.TrimSpace(namespace)
	if err := validateCoreInputs(c.strictMode, "set", key, &ns); err != nil {
		return nil, err
	}
	path := "/key/" + url.PathEscape(key)
	if len(ttlSecs) > 0 && ttlSecs[0] > 0 {
		path += fmt.Sprintf("?ttl=%d", ttlSecs[0])
	}
	b, status, err := c.request(http.MethodPut, path, value, "text/plain", namespaceHeaderPtr(&ns))
	if err != nil {
		return nil, err
	}
	if err := parseHTTPError(status, b); err != nil {
		return nil, err
	}
	var payload struct {
		Version uint64 `json:"version"`
	}
	if err := json.Unmarshal(b, &payload); err != nil {
		return nil, err
	}
	return &SetResult{Version: payload.Version}, nil
}

func (c *HTTPClient) SetStringInNamespace(key, value, namespace string, ttlSecs ...uint64) (*SetResult, error) {
	return c.SetInNamespace(key, []byte(value), namespace, ttlSecs...)
}

func (c *HTTPClient) SetNX(key string, value []byte, ttlSecs uint64, namespace ...string) (*SetNXResult, error) {
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return nil, err
	}
	if err := validateCoreInputs(c.strictMode, "set", key, ns); err != nil {
		return nil, err
	}
	path := "/key/" + url.PathEscape(key) + "?nx=1"
	if ttlSecs > 0 {
		path += fmt.Sprintf("&ttl=%d", ttlSecs)
	}
	b, status, err := c.request(http.MethodPost, path, value, "application/octet-stream", namespaceHeaderPtr(ns))
	if err != nil {
		return nil, err
	}
	if status == http.StatusBadRequest || status == http.StatusNotFound || status == http.StatusNotImplemented {
		if err := parseAtomicHTTPUnsupported(status, b, "SET_NX"); err != nil {
			return nil, err
		}
	}
	if err := parseHTTPError(status, b); err != nil {
		return nil, err
	}
	var payload struct {
		Created bool   `json:"created"`
		Version string `json:"version"`
	}
	if err := json.Unmarshal(b, &payload); err != nil {
		return nil, err
	}
	version, err := strconv.ParseUint(payload.Version, 10, 64)
	if err != nil {
		return nil, err
	}
	return &SetNXResult{Created: payload.Created, Version: version}, nil
}

func (c *HTTPClient) Incr(key string, delta int64, ttlSecsOnCreate uint64, namespace ...string) (*IncrResult, error) {
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return nil, err
	}
	if err := validateCoreInputs(c.strictMode, "set", key, ns); err != nil {
		return nil, err
	}
	payload := map[string]any{"delta": delta}
	if ttlSecsOnCreate > 0 {
		payload["ttl_secs_on_create"] = ttlSecsOnCreate
	}
	body, _ := json.Marshal(payload)
	b, status, err := c.request(http.MethodPost, "/key/"+url.PathEscape(key)+"/incr", body, "application/json", namespaceHeaderPtr(ns))
	if err != nil {
		return nil, err
	}
	if status == http.StatusBadRequest || status == http.StatusNotFound || status == http.StatusNotImplemented {
		if err := parseAtomicHTTPUnsupported(status, b, "INCR"); err != nil {
			return nil, err
		}
	}
	if err := parseHTTPError(status, b); err != nil {
		return nil, err
	}
	var response struct {
		Value   string `json:"value"`
		Version string `json:"version"`
	}
	if err := json.Unmarshal(b, &response); err != nil {
		return nil, err
	}
	value, err := strconv.ParseInt(response.Value, 10, 64)
	if err != nil {
		return nil, err
	}
	version, err := strconv.ParseUint(response.Version, 10, 64)
	if err != nil {
		return nil, err
	}
	return &IncrResult{Value: value, Version: version}, nil
}

func (c *HTTPClient) Delete(key string, namespace ...string) (bool, error) {
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return false, err
	}
	if err := validateCoreInputs(c.strictMode, "delete", key, ns); err != nil {
		return false, err
	}
	b, status, err := c.request(http.MethodDelete, "/key/"+url.PathEscape(key), nil, "", namespaceHeaderPtr(ns))
	if err != nil {
		return false, err
	}
	if status == http.StatusNoContent {
		return true, nil
	}
	if status == http.StatusNotFound {
		return false, nil
	}
	if err := parseHTTPError(status, b); err != nil {
		return false, err
	}
	return true, nil
}

func (c *HTTPClient) DeleteByPattern(pattern string, namespace ...string) (*DeleteByPatternResult, error) {
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return nil, err
	}
	if err := validatePatternInputs(c.strictMode, "deleteByPattern", pattern, ns); err != nil {
		return nil, err
	}
	payload, _ := json.Marshal(map[string]string{"pattern": pattern})
	b, status, err := c.request(http.MethodPost, "/keys/delete-by-pattern", payload, "application/json", namespaceHeaderPtr(ns))
	if err != nil {
		return nil, err
	}
	if err := parseHTTPError(status, b); err != nil {
		return nil, err
	}
	var out DeleteByPatternResult
	if err := json.Unmarshal(b, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *HTTPClient) SetTtlByPattern(pattern string, ttlSecs uint64, namespace ...string) (*SetTtlByPatternResult, error) {
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return nil, err
	}
	if err := validatePatternInputs(c.strictMode, "setTtlByPattern", pattern, ns); err != nil {
		return nil, err
	}
	m := map[string]any{"pattern": pattern}
	if ttlSecs > 0 {
		m["ttl_secs"] = ttlSecs
	}
	payload, _ := json.Marshal(m)
	b, status, err := c.request(http.MethodPost, "/keys/ttl-by-pattern", payload, "application/json", namespaceHeaderPtr(ns))
	if err != nil {
		return nil, err
	}
	if err := parseHTTPError(status, b); err != nil {
		return nil, err
	}
	var out SetTtlByPatternResult
	if err := json.Unmarshal(b, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *HTTPClient) Stats() (*StatsResult, error) {
	b, status, err := c.request(http.MethodGet, "/stats", nil, "", nil)
	if err != nil {
		return nil, err
	}
	if err := parseHTTPError(status, b); err != nil {
		return nil, err
	}
	var out StatsResult
	if err := json.Unmarshal(b, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func namespaceHeader(namespace ...string) map[string]string {
	if len(namespace) == 0 {
		return nil
	}
	ns := strings.TrimSpace(namespace[0])
	if ns == "" {
		return nil
	}
	return map[string]string{"X-Ditto-Namespace": ns}
}

func namespaceHeaderPtr(namespace *string) map[string]string {
	if namespace == nil {
		return nil
	}
	ns := strings.TrimSpace(*namespace)
	if ns == "" {
		return nil
	}
	return map[string]string{"X-Ditto-Namespace": ns}
}

func parseAtomicHTTPUnsupported(status int, body []byte, operation string) error {
	var payload struct {
		Error   string `json:"error"`
		Message string `json:"message"`
	}
	if json.Unmarshal(body, &payload) == nil && payload.Error == ErrUnsupportedRequest {
		msg := payload.Message
		if msg == "" {
			msg = payload.Error
		}
		return &DittoError{Code: ErrUnsupportedRequest, Message: msg}
	}
	return &DittoError{
		Code:    ErrUnsupportedRequest,
		Message: fmt.Sprintf("server does not support %s; upgrade dittod to a version with atomic primitives", operation),
	}
}
