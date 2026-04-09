package ditto

import "fmt"

const (
	ErrNodeInactive    = "NodeInactive"
	ErrNoQuorum        = "NoQuorum"
	ErrKeyNotFound     = "KeyNotFound"
	ErrInternalError   = "InternalError"
	ErrWriteTimeout    = "WriteTimeout"
	ErrValueTooLarge   = "ValueTooLarge"
	ErrKeyLimitReached = "KeyLimitReached"
	ErrRateLimited     = "RateLimited"
	ErrCircuitOpen     = "CircuitOpen"
	ErrAuthFailed      = "AuthFailed"
)

type DittoError struct {
	Code    string
	Message string
}

func (e *DittoError) Error() string {
	if e == nil {
		return "<nil>"
	}
	if e.Code == "" {
		return e.Message
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Message)
}
