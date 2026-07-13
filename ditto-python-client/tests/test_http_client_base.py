import unittest
from unittest.mock import patch

from ditto_client import DittoError, DittoErrorCode
from ditto_client.http_client_base import DittoHttpClientBase


class HttpClientBaseTests(unittest.TestCase):
    def test_assert_ok_maps_statuses_and_payload_codes(self):
        client = DittoHttpClientBase()
        for status, code in [
            (404, DittoErrorCode.KEY_NOT_FOUND),
            (429, DittoErrorCode.RATE_LIMITED),
            (503, DittoErrorCode.NODE_INACTIVE),
            (504, DittoErrorCode.WRITE_TIMEOUT),
            (500, DittoErrorCode.INTERNAL_ERROR),
        ]:
            with self.subTest(status=status):
                with self.assertRaises(DittoError) as raised:
                    client._assert_ok(status, "")
                self.assertEqual(raised.exception.code, code)

        with self.assertRaises(DittoError) as raised:
            client._assert_ok(499, '{"error":"NamespaceQuotaExceeded","message":"quota"}')
        self.assertEqual(raised.exception.code, DittoErrorCode.NAMESPACE_QUOTA_EXCEEDED)
        self.assertEqual(str(raised.exception), "quota")

        with self.assertRaises(DittoError) as raised:
            client._assert_ok(499, '{"error":"FutureServerCode","message":"new"}')
        self.assertEqual(raised.exception.code, "FutureServerCode")

    def test_url_encode_and_context_manager_paths(self):
        client = DittoHttpClientBase(username="ditto", password="secret")
        self.assertEqual(client._url_encode("a b/c"), "a%20b%2Fc")
        with client as same:
            self.assertIs(same, client)

    def test_insecure_tls_option_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "no longer supported"):
            DittoHttpClientBase(tls=True, dev_insecure_tls=True)

    def test_reject_unauthorized_false_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "no longer supported"):
            DittoHttpClientBase(tls=True, reject_unauthorized=False)

    def test_trusted_cert_path_is_loaded_when_provided(self):
        with patch("ssl.create_default_context") as create_default_context:
            ctx = create_default_context.return_value
            DittoHttpClientBase(tls=True, trusted_cert_path="/tmp/ca.pem")
            ctx.load_verify_locations.assert_called_once_with(cafile="/tmp/ca.pem")


if __name__ == "__main__":
    unittest.main()
