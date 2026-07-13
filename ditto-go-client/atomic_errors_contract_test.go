package ditto

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type atomicErrorContractSuite struct {
	Cases []atomicErrorContractCase `json:"cases"`
}

type atomicErrorContractCase struct {
	ID        string                    `json:"id"`
	Operation string                    `json:"operation"`
	Inputs    atomicErrorContractInputs `json:"inputs"`
	Expect    atomicErrorContractExpect `json:"expect"`
}

type atomicErrorContractInputs struct {
	Transport     string `json:"transport"`
	Status        int    `json:"status"`
	OperationName string `json:"operation_name"`
	Body          string `json:"body"`
	ErrorMessage  string `json:"error_message"`
	ErrorKind     string `json:"error_kind"`
	ErrorCode     string `json:"error_code"`
}

type atomicErrorContractExpect struct {
	Code            string `json:"code"`
	MessageContains string `json:"message_contains"`
}

func TestAtomicErrorsContract(t *testing.T) {
	contractPath := filepath.Join("..", "contracts", "atomic-errors.contract.json")
	raw, err := os.ReadFile(contractPath)
	if err != nil {
		t.Fatalf("read contract: %v", err)
	}

	var suite atomicErrorContractSuite
	if err := json.Unmarshal(raw, &suite); err != nil {
		t.Fatalf("parse contract: %v", err)
	}

	for _, tc := range suite.Cases {
		tc := tc
		t.Run(tc.ID, func(t *testing.T) {
			switch tc.Operation {
			case "normalize_http_atomic_error":
				assertAtomicDittoError(t, parseAtomicHTTPUnsupported([]byte(tc.Inputs.Body), tc.Inputs.OperationName), tc.Expect)
			case "normalize_tcp_atomic_error":
				var input error
				if tc.Inputs.ErrorKind == "ditto" {
					input = &DittoError{Code: tc.Inputs.ErrorCode, Message: tc.Inputs.ErrorMessage}
				} else {
					input = errors.New(tc.Inputs.ErrorMessage)
				}
				assertAtomicDittoError(t, normalizeAtomicTCPErr(input, tc.Inputs.OperationName), tc.Expect)
			default:
				t.Fatalf("unsupported contract operation: %s", tc.Operation)
			}
		})
	}
}

func assertAtomicDittoError(t *testing.T, err error, expect atomicErrorContractExpect) {
	t.Helper()
	de, ok := err.(*DittoError)
	if !ok {
		t.Fatalf("expected DittoError, got %T (%v)", err, err)
	}
	if de.Code != expect.Code {
		t.Fatalf("expected code %q, got %q", expect.Code, de.Code)
	}
	if !strings.Contains(de.Message, expect.MessageContains) {
		t.Fatalf("expected message containing %q, got %q", expect.MessageContains, de.Message)
	}
}
