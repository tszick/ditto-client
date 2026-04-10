import unittest

from ditto_client.validation import validate_pattern_inputs


class StrictValidationTests(unittest.TestCase):
    def test_pattern_validation_rejects_invalid_chars(self):
        with self.assertRaisesRegex(ValueError, "pattern contains unsupported characters"):
            validate_pattern_inputs(True, "delete_by_pattern", "bad pattern*", None)

    def test_pattern_validation_rejects_blank_namespace(self):
        with self.assertRaisesRegex(ValueError, "namespace must not be blank"):
            validate_pattern_inputs(True, "set_ttl_by_pattern", "ok:*", "   ")

    def test_pattern_validation_rejects_namespace_with_double_colon(self):
        with self.assertRaisesRegex(ValueError, "namespace must not contain '::'"):
            validate_pattern_inputs(True, "delete_by_pattern", "tenant:*", "alpha::beta")

    def test_pattern_validation_noop_when_strict_mode_disabled(self):
        # Should not raise even with malformed values when strict mode is off.
        validate_pattern_inputs(False, "delete_by_pattern", "bad pattern*", "bad::ns")


if __name__ == "__main__":
    unittest.main()
