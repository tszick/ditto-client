package ditto

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

type TCPClientOptions struct {
	Host          string
	Port          int
	AuthToken     string
	Timeout       time.Duration
	MaxFrameBytes uint32
	StrictMode    bool
	AutoReconnect bool
}

type TCPClient struct {
	host          string
	port          int
	authToken     string
	timeout       time.Duration
	maxFrameBytes uint32
	strictMode    bool
	autoReconnect bool

	mu   sync.Mutex
	conn net.Conn
}

func NewTCPClient(opts TCPClientOptions) *TCPClient {
	host := opts.Host
	if host == "" {
		host = "localhost"
	}
	port := opts.Port
	if port == 0 {
		port = 7777
	}
	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	maxFrame := opts.MaxFrameBytes
	if maxFrame == 0 {
		maxFrame = 8 * 1024 * 1024
	}
	return &TCPClient{
		host: host, port: port, authToken: opts.AuthToken, timeout: timeout, maxFrameBytes: maxFrame, strictMode: opts.StrictMode, autoReconnect: opts.AutoReconnect,
	}
}

func (c *TCPClient) Connect() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn != nil {
		return nil
	}
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", c.host, c.port), c.timeout)
	if err != nil {
		return err
	}
	c.conn = conn
	if c.authToken != "" {
		resp, err := c.sendLocked(encodeAuth(c.authToken))
		if err != nil {
			_ = c.conn.Close()
			c.conn = nil
			return err
		}
		if resp.kind == respError {
			_ = c.conn.Close()
			c.conn = nil
			return &DittoError{Code: resp.code, Message: resp.message}
		}
		if resp.kind != respAuthOK {
			_ = c.conn.Close()
			c.conn = nil
			return fmt.Errorf("unexpected auth response")
		}
	}
	return nil
}

func (c *TCPClient) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn == nil {
		return nil
	}
	err := c.conn.Close()
	c.conn = nil
	return err
}

func (c *TCPClient) ensureConnectedLocked() error {
	if c.conn != nil {
		return nil
	}
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", c.host, c.port), c.timeout)
	if err != nil {
		return err
	}
	c.conn = conn
	if c.authToken != "" {
		resp, err := c.sendLocked(encodeAuth(c.authToken))
		if err != nil {
			_ = c.conn.Close()
			c.conn = nil
			return err
		}
		if resp.kind == respError {
			_ = c.conn.Close()
			c.conn = nil
			return &DittoError{Code: resp.code, Message: resp.message}
		}
		if resp.kind != respAuthOK {
			_ = c.conn.Close()
			c.conn = nil
			return fmt.Errorf("unexpected auth response")
		}
	}
	return nil
}

func (c *TCPClient) sendLocked(frame []byte) (*tcpResponse, error) {
	if err := c.conn.SetDeadline(time.Now().Add(c.timeout)); err != nil {
		return nil, err
	}
	if _, err := c.conn.Write(frame); err != nil {
		return nil, err
	}
	return c.readResponseLocked()
}

func (c *TCPClient) closeConnLocked() {
	if c.conn != nil {
		_ = c.conn.Close()
		c.conn = nil
	}
}

func (c *TCPClient) sendRequestLocked(frame []byte) (*tcpResponse, error) {
	resp, err := c.sendLocked(frame)
	if err == nil {
		return resp, nil
	}
	c.closeConnLocked()
	if !c.autoReconnect {
		return nil, err
	}
	if connErr := c.ensureConnectedLocked(); connErr != nil {
		return nil, connErr
	}
	return c.sendLocked(frame)
}

func (c *TCPClient) readResponseLocked() (*tcpResponse, error) {
	head := make([]byte, 4)
	if _, err := io.ReadFull(c.conn, head); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(head)
	if n > c.maxFrameBytes {
		return nil, fmt.Errorf("incoming frame too large: %d", n)
	}
	payload := make([]byte, n)
	if _, err := io.ReadFull(c.conn, payload); err != nil {
		return nil, err
	}
	return decodeResponse(payload)
}

func (c *TCPClient) Ping() (bool, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return false, err
	}
	resp, err := c.sendRequestLocked(encodePing())
	if err != nil {
		return false, err
	}
	return resp.kind == respPong, nil
}

func (c *TCPClient) Get(key string, namespace ...string) (*GetResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return nil, err
	}
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return nil, err
	}
	if err := validateCoreInputs(c.strictMode, "get", key, ns); err != nil {
		return nil, err
	}
	resp, err := c.sendRequestLocked(encodeGet(key, ns))
	if err != nil {
		return nil, err
	}
	switch resp.kind {
	case respNotFound:
		return nil, nil
	case respValue:
		return &GetResult{Value: resp.value, Version: resp.version}, nil
	case respError:
		return nil, &DittoError{Code: resp.code, Message: resp.message}
	default:
		return nil, fmt.Errorf("unexpected response")
	}
}

func (c *TCPClient) Set(key string, value []byte, ttlSecs ...uint64) (*SetResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return nil, err
	}
	if err := validateCoreInputs(c.strictMode, "set", key, nil); err != nil {
		return nil, err
	}
	var ttl *uint64
	if len(ttlSecs) > 0 && ttlSecs[0] > 0 {
		ttl = &ttlSecs[0]
	}
	resp, err := c.sendRequestLocked(encodeSet(key, value, ttl, nil))
	if err != nil {
		return nil, err
	}
	switch resp.kind {
	case respOK:
		return &SetResult{Version: resp.version}, nil
	case respError:
		return nil, &DittoError{Code: resp.code, Message: resp.message}
	default:
		return nil, fmt.Errorf("unexpected response")
	}
}

func (c *TCPClient) SetString(key, value string, ttlSecs ...uint64) (*SetResult, error) {
	return c.Set(key, []byte(value), ttlSecs...)
}

func (c *TCPClient) SetInNamespace(key string, value []byte, namespace string, ttlSecs ...uint64) (*SetResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return nil, err
	}
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace)
	if err != nil {
		return nil, err
	}
	if err := validateCoreInputs(c.strictMode, "set", key, ns); err != nil {
		return nil, err
	}
	var ttl *uint64
	if len(ttlSecs) > 0 && ttlSecs[0] > 0 {
		ttl = &ttlSecs[0]
	}
	resp, err := c.sendRequestLocked(encodeSet(key, value, ttl, ns))
	if err != nil {
		return nil, err
	}
	switch resp.kind {
	case respOK:
		return &SetResult{Version: resp.version}, nil
	case respError:
		return nil, &DittoError{Code: resp.code, Message: resp.message}
	default:
		return nil, fmt.Errorf("unexpected response")
	}
}

func (c *TCPClient) SetStringInNamespace(key, value, namespace string, ttlSecs ...uint64) (*SetResult, error) {
	return c.SetInNamespace(key, []byte(value), namespace, ttlSecs...)
}

func (c *TCPClient) Delete(key string, namespace ...string) (bool, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return false, err
	}
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return false, err
	}
	if err := validateCoreInputs(c.strictMode, "delete", key, ns); err != nil {
		return false, err
	}
	resp, err := c.sendRequestLocked(encodeDelete(key, ns))
	if err != nil {
		return false, err
	}
	switch resp.kind {
	case respDeleted:
		return true, nil
	case respNotFound:
		return false, nil
	case respError:
		return false, &DittoError{Code: resp.code, Message: resp.message}
	default:
		return false, fmt.Errorf("unexpected response")
	}
}

func (c *TCPClient) DeleteByPattern(pattern string, namespace ...string) (*DeleteByPatternResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return nil, err
	}
	resp, err := c.sendRequestLocked(encodeDeleteByPattern(pattern, normalizeNamespace(namespace...)))
	if err != nil {
		return nil, err
	}
	switch resp.kind {
	case respPatternDeleted:
		return &DeleteByPatternResult{Deleted: resp.count}, nil
	case respError:
		return nil, &DittoError{Code: resp.code, Message: resp.message}
	default:
		return nil, fmt.Errorf("unexpected response")
	}
}

func (c *TCPClient) SetTtlByPattern(pattern string, ttlSecs uint64, namespace ...string) (*SetTtlByPatternResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return nil, err
	}
	var ttl *uint64
	if ttlSecs > 0 {
		ttl = &ttlSecs
	}
	resp, err := c.sendRequestLocked(encodeSetTTLByPattern(pattern, ttl, normalizeNamespace(namespace...)))
	if err != nil {
		return nil, err
	}
	switch resp.kind {
	case respPatternTTLUpdated:
		return &SetTtlByPatternResult{Updated: resp.count}, nil
	case respError:
		return nil, &DittoError{Code: resp.code, Message: resp.message}
	default:
		return nil, fmt.Errorf("unexpected response")
	}
}

func (c *TCPClient) Watch(key string, namespace ...string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return err
	}
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return err
	}
	if err := validateCoreInputs(c.strictMode, "watch", key, ns); err != nil {
		return err
	}
	resp, err := c.sendRequestLocked(encodeWatch(key, ns))
	if err != nil {
		return err
	}
	switch resp.kind {
	case respWatching:
		return nil
	case respError:
		return &DittoError{Code: resp.code, Message: resp.message}
	default:
		return fmt.Errorf("unexpected response")
	}
}

func (c *TCPClient) Unwatch(key string, namespace ...string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return err
	}
	ns, err := normalizedNamespaceStrict(c.strictMode, namespace...)
	if err != nil {
		return err
	}
	if err := validateCoreInputs(c.strictMode, "unwatch", key, ns); err != nil {
		return err
	}
	resp, err := c.sendRequestLocked(encodeUnwatch(key, ns))
	if err != nil {
		return err
	}
	switch resp.kind {
	case respUnwatched:
		return nil
	case respError:
		return &DittoError{Code: resp.code, Message: resp.message}
	default:
		return fmt.Errorf("unexpected response")
	}
}

func (c *TCPClient) WaitWatchEvent() (*WatchEventResult, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.ensureConnectedLocked(); err != nil {
		return nil, err
	}
	resp, err := c.readResponseLocked()
	if err != nil {
		return nil, err
	}
	switch resp.kind {
	case respWatchEvent:
		value := resp.value
		if !resp.hasValue {
			value = nil
		}
		return &WatchEventResult{
			Key:     resp.key,
			Value:   value,
			Version: resp.version,
		}, nil
	case respError:
		return nil, &DittoError{Code: resp.code, Message: resp.message}
	default:
		return nil, fmt.Errorf("unexpected response")
	}
}

func normalizeNamespace(namespace ...string) *string {
	ns, _ := normalizedNamespaceStrict(false, namespace...)
	return ns
}
