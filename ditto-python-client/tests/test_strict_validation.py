import unittest

from ditto_client.validation import validate_pattern_inputs


class StrictValidationTests(unittest.TestCase):
    def test_pattern_validation_rejects_invalid_chars(self):
        with self.assertRaisesRegex(ValueError, "pattern contains unsupported characters"):
            validate_pattern_inputs(True, "delete_by_pattern", "bad pattern*", None)

    def test_pattern_validation_rejects_blank_namespace(self):
        with self.assertRaisesRegex(ValueError, "namespace must not be blank"):
            validate_pattern_inputs(True, "set_ttl_by_pattern", "ok:*", "   ")


if __name__ == "__main__":
    unittest.main()
