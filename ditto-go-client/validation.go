package ditto

import (
	"fmt"
	"strings"
	"unicode"
)

func validateCoreInputs(strict bool, op string, key string, namespace *string) error {
	if !strict {
		return nil
	}
	if strings.TrimSpace(key) == "" {
		return fmt.Errorf("invalid %s request: key must not be empty", op)
	}
	if !isStrictToken(key) {
		return fmt.Errorf("invalid %s request: key contains unsupported characters; allowed: [A-Za-z0-9._:-]", op)
	}
	if namespace == nil {
		return nil
	}
	ns := strings.TrimSpace(*namespace)
	if ns == "" {
		return fmt.Errorf("invalid %s request: namespace must not be blank when provided", op)
	}
	if strings.Contains(ns, "::") {
		return fmt.Errorf("invalid %s request: namespace must not contain '::'", op)
	}
	if !isStrictToken(ns) {
		return fmt.Errorf("invalid %s request: namespace contains unsupported characters; allowed: [A-Za-z0-9._:-]", op)
	}
	return nil
}

func isStrictToken(s string) bool {
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || r == '-' || r == '_' || r == '.' || r == ':' {
			continue
		}
		return false
	}
	return true
}

func normalizedNamespaceStrict(strict bool, namespace ...string) (*string, error) {
	if len(namespace) == 0 {
		return nil, nil
	}
	ns := strings.TrimSpace(namespace[0])
	if ns == "" {
		if strict {
			return nil, fmt.Errorf("invalid request: namespace must not be blank when provided")
		}
		return nil, nil
	}
	if strict {
		if strings.Contains(ns, "::") {
			return nil, fmt.Errorf("invalid request: namespace must not contain '::'")
		}
		if !isStrictToken(ns) {
			return nil, fmt.Errorf("invalid request: namespace contains unsupported characters; allowed: [A-Za-z0-9._:-]")
		}
	}
	return &ns, nil
}
