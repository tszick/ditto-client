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

func TestValidatePatternInputsRejectsNamespaceWithDoubleColon(t *testing.T) {
	ns := "alpha::beta"
	if err := validatePatternInputs(true, "deleteByPattern", "tenant:*", &ns); err == nil {
		t.Fatalf("expected validation error for namespace with double colon")
	}
}

func TestValidatePatternInputsNoopWhenStrictModeDisabled(t *testing.T) {
	ns := "bad::ns"
	if err := validatePatternInputs(false, "deleteByPattern", "bad pattern*", &ns); err != nil {
		t.Fatalf("expected no validation error when strict mode is disabled, got: %v", err)
	}
}

func TestNormalizedNamespaceStrictRejectsDoubleColonWhenStrict(t *testing.T) {
	_, err := normalizedNamespaceStrict(true, "alpha::beta")
	if err == nil {
		t.Fatalf("expected strict namespace normalization to reject '::'")
	}
}

func TestNormalizedNamespaceStrictDropsBlankWhenNonStrict(t *testing.T) {
	ns, err := normalizedNamespaceStrict(false, "   ")
	if err != nil {
		t.Fatalf("expected non-strict blank namespace to be accepted, got: %v", err)
	}
	if ns != nil {
		t.Fatalf("expected nil namespace for blank input in non-strict mode, got: %v", *ns)
	}
}
