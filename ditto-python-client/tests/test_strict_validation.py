import unittest

from ditto_client.validation import validate_core_inputs, validate_pattern_inputs


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

    def test_core_validation_covers_key_and_namespace_branches(self):
        validate_core_inputs(False, "get", "bad key", "bad::ns")
        validate_core_inputs(True, "get", "key-1._:ok", "tenant-a")
        with self.assertRaisesRegex(ValueError, "key must not be empty"):
            validate_core_inputs(True, "get", " ", None)
        with self.assertRaisesRegex(ValueError, "key contains unsupported characters"):
            validate_core_inputs(True, "set", "bad key", None)
        with self.assertRaisesRegex(ValueError, "namespace must not be blank"):
            validate_core_inputs(True, "delete", "key", " ")
        with self.assertRaisesRegex(ValueError, "namespace contains unsupported characters"):
            validate_core_inputs(True, "delete", "key", "bad ns")


if __name__ == "__main__":
    unittest.main()
