package ditto

import (
	"bytes"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

type HTTPClientOptions struct {
	Host               string
	Port               int
	TLS                bool
	Username           string
	Password           string
	RejectUnauthorized bool
	Timeout            time.Duration
}

type HTTPClient struct {
	baseURL    string
	httpClient *http.Client
	authHeader string
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
	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	tr := &http.Transport{}
	if opts.TLS {
		tr.TLSClientConfig = &tls.Config{InsecureSkipVerify: !opts.RejectUnauthorized} //nolint:gosec
	}
	c := &HTTPClient{
		baseURL: fmt.Sprintf("%s://%s:%d", scheme, host, port),
		httpClient: &http.Client{
			Timeout:   timeout,
			Transport: tr,
		},
	}
	if opts.Username != "" && opts.Password != "" {
		c.authHeader = "Basic " + base64.StdEncoding.EncodeToString([]byte(opts.Username+":"+opts.Password))
	}
	return c
}

func (c *HTTPClient) Close() {}

func (c *HTTPClient) request(method, path string, body []byte, contentType string) ([]byte, int, error) {
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
	var payload struct {
		Error   string `json:"error"`
		Message string `json:"message"`
	}
	if json.Unmarshal(body, &payload) == nil {
		if payload.Message != "" {
			msg = payload.Message
		} else if payload.Error != "" {
			msg = payload.Error
		}
	}
	return &DittoError{Code: httpStatusToCode(status), Message: msg}
}

func (c *HTTPClient) Ping() (bool, error) {
	b, status, err := c.request(http.MethodGet, "/ping", nil, "")
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

func (c *HTTPClient) Get(key string) (*GetResult, error) {
	b, status, err := c.request(http.MethodGet, "/key/"+url.PathEscape(key), nil, "")
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
		Value   string `json:"value"`
		Version uint64 `json:"version"`
	}
	if err := json.Unmarshal(b, &payload); err != nil {
		return nil, err
	}
	return &GetResult{Value: []byte(payload.Value), Version: payload.Version}, nil
}

func (c *HTTPClient) Set(key string, value []byte, ttlSecs ...uint64) (*SetResult, error) {
	path := "/key/" + url.PathEscape(key)
	if len(ttlSecs) > 0 && ttlSecs[0] > 0 {
		path += fmt.Sprintf("?ttl=%d", ttlSecs[0])
	}
	b, status, err := c.request(http.MethodPut, path, value, "text/plain")
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

func (c *HTTPClient) Delete(key string) (bool, error) {
	b, status, err := c.request(http.MethodDelete, "/key/"+url.PathEscape(key), nil, "")
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

func (c *HTTPClient) DeleteByPattern(pattern string) (*DeleteByPatternResult, error) {
	payload, _ := json.Marshal(map[string]string{"pattern": pattern})
	b, status, err := c.request(http.MethodPost, "/keys/delete-by-pattern", payload, "application/json")
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

func (c *HTTPClient) SetTtlByPattern(pattern string, ttlSecs uint64) (*SetTtlByPatternResult, error) {
	m := map[string]any{"pattern": pattern}
	if ttlSecs > 0 {
		m["ttl_secs"] = ttlSecs
	}
	payload, _ := json.Marshal(m)
	b, status, err := c.request(http.MethodPost, "/keys/ttl-by-pattern", payload, "application/json")
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
	b, status, err := c.request(http.MethodGet, "/stats", nil, "")
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
