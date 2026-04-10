package ditto

import "testing"

func TestValidatePatternInputsRejectsInvalidPattern(t *testing.T) {
	if err := validatePatternInputs(true, "deleteByPattern", "bad pattern*", nil); err == nil {
		t.Fatalf("expected validation error for invalid pattern")
	}
}

func TestValidatePatternInputsRejectsBlankNamespace(t *testing.T) {
	ns := "   "
	if err := validatePatternInputs(true, "setTtlByPattern", "ok:*", &ns); err == nil {
		t.Fatalf("expected validation error for blank namespace")
	}
}
