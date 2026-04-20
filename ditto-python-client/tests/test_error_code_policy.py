import unittest

from ditto_client import DittoError
from ditto_client.http_client_base import DittoHttpClientBase


class ErrorCodePolicyTests(unittest.TestCase):
    def test_assert_ok_preserves_unknown_payload_error_code(self):
        client = DittoHttpClientBase()
        with self.assertRaises(DittoError) as ctx:
            client._assert_ok(409, '{"error":"NamespaceQuotaExceeded","message":"quota hit"}')
        self.assertEqual("NamespaceQuotaExceeded", ctx.exception.code)
        self.assertEqual("quota hit", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
