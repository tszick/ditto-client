package ditto

import (
	"encoding/json"
	"fmt"
	"strings"
)

func resolveCoreNamespace(strict bool, op string, key string, namespace ...string) (*string, error) {
	ns, err := normalizedNamespaceStrict(strict, namespace...)
	if err != nil {
		return nil, err
	}
	if err := validateCoreInputs(strict, op, key, ns); err != nil {
		return nil, err
	}
	return ns, nil
}

func resolveCoreNamespaceValue(strict bool, op string, key string, namespace string) (*string, error) {
	return resolveCoreNamespace(strict, op, key, namespace)
}

func resolvePatternNamespace(strict bool, op string, pattern string, namespace ...string) (*string, error) {
	ns, err := normalizedNamespaceStrict(strict, namespace...)
	if err != nil {
		return nil, err
	}
	if err := validatePatternInputs(strict, op, pattern, ns); err != nil {
		return nil, err
	}
	return ns, nil
}

func optionalUint64FromSlice(values ...uint64) *uint64 {
	if len(values) == 0 || values[0] == 0 {
		return nil
	}
	return &values[0]
}

func optionalUint64(value uint64) *uint64 {
	if value == 0 {
		return nil
	}
	return &value
}

func optionalInt64(value int64) *int64 {
	return &value
}

func normalizeNamespace(namespace ...string) *string {
	ns, _ := normalizedNamespaceStrict(false, namespace...)
	return ns
}

func parseUnexpectedResponseError() error {
	return fmt.Errorf("unexpected response")
}

func unsupportedAtomicOperationError(operation string) error {
	return &DittoError{
		Code:    ErrUnsupportedRequest,
		Message: fmt.Sprintf("server does not support %s; upgrade dittod to a version with atomic primitives", operation),
	}
}

func parseAtomicHTTPUnsupported(body []byte, operation string) error {
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
	return unsupportedAtomicOperationError(operation)
}

func normalizeAtomicTCPErr(err error, operation string) error {
	if err == nil {
		return nil
	}
	if de, ok := err.(*DittoError); ok {
		return de
	}
	msg := strings.ToLower(err.Error())
	if strings.Contains(msg, "unsupported") ||
		strings.Contains(msg, "protocol") ||
		strings.Contains(msg, "decode") ||
		strings.Contains(msg, "clientresponse oneof") ||
		strings.Contains(msg, "unexpected response") ||
		strings.Contains(msg, "eof") ||
		strings.Contains(msg, "connection reset") {
		return unsupportedAtomicOperationError(operation)
	}
	return err
}
