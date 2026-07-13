package ditto

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type validationContractSuite struct {
	Cases []validationContractCase `json:"cases"`
}

type validationContractCase struct {
	ID        string                   `json:"id"`
	Operation string                   `json:"operation"`
	Inputs    validationContractInputs `json:"inputs"`
	Expect    validationContractExpect `json:"expect"`
}

type validationContractInputs struct {
	Kind       string  `json:"kind"`
	StrictMode bool    `json:"strict_mode"`
	Op         string  `json:"op"`
	Key        string  `json:"key"`
	Pattern    string  `json:"pattern"`
	Namespace  *string `json:"namespace"`
}

type validationContractExpect struct {
	Valid         bool    `json:"valid"`
	ErrorContains string  `json:"error_contains"`
	Normalized    *string `json:"normalized"`
}

func TestStrictValidationContract(t *testing.T) {
	contractPath := filepath.Join("..", "contracts", "strict-validation.contract.json")
	raw, err := os.ReadFile(contractPath)
	if err != nil {
		t.Fatalf("read contract: %v", err)
	}

	var suite validationContractSuite
	if err := json.Unmarshal(raw, &suite); err != nil {
		t.Fatalf("parse contract: %v", err)
	}

	for _, tc := range suite.Cases {
		tc := tc
		t.Run(tc.ID, func(t *testing.T) {
			switch tc.Inputs.Kind {
			case "core":
				err := validateCoreInputs(tc.Inputs.StrictMode, tc.Inputs.Op, tc.Inputs.Key, tc.Inputs.Namespace)
				assertValidationOutcome(t, err, tc.Expect)
			case "pattern":
				err := validatePatternInputs(tc.Inputs.StrictMode, tc.Inputs.Op, tc.Inputs.Pattern, tc.Inputs.Namespace)
				assertValidationOutcome(t, err, tc.Expect)
			case "normalize_namespace":
				got, err := normalizedNamespaceStrict(tc.Inputs.StrictMode, namespaceArg(tc.Inputs.Namespace)...)
				if tc.Expect.ErrorContains != "" {
					if err == nil || !strings.Contains(err.Error(), tc.Expect.ErrorContains) {
						t.Fatalf("expected normalization error containing %q, got %v", tc.Expect.ErrorContains, err)
					}
					return
				}
				if err != nil {
					t.Fatalf("unexpected normalization error: %v", err)
				}
				assertNormalizedNamespace(t, got, tc.Expect.Normalized)
			default:
				t.Fatalf("unsupported contract kind: %s", tc.Inputs.Kind)
			}
		})
	}
}

func assertValidationOutcome(t *testing.T, err error, expect validationContractExpect) {
	t.Helper()
	if expect.Valid {
		if err != nil {
			t.Fatalf("expected valid input, got error: %v", err)
		}
		return
	}
	if err == nil {
		t.Fatalf("expected validation error containing %q, got nil", expect.ErrorContains)
	}
	if expect.ErrorContains != "" && !strings.Contains(err.Error(), expect.ErrorContains) {
		t.Fatalf("expected error containing %q, got %q", expect.ErrorContains, err.Error())
	}
}

func assertNormalizedNamespace(t *testing.T, got *string, want *string) {
	t.Helper()
	if want == nil {
		if got != nil {
			t.Fatalf("expected nil namespace, got %q", *got)
		}
		return
	}
	if got == nil {
		t.Fatalf("expected namespace %q, got nil", *want)
	}
	if *got != *want {
		t.Fatalf("expected namespace %q, got %q", *want, *got)
	}
}

func namespaceArg(namespace *string) []string {
	if namespace == nil {
		return nil
	}
	return []string{*namespace}
}
