from __future__ import annotations

import socket
import ssl
import struct
import sys
import threading
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ditto_client import DittoTcpClient
from ditto_client.wire import (
    REQUEST_FIELDS,
    RESPONSE_FIELDS,
    decode_client_request_variant,
    frame_client_response,
)


TEST_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIC5jCCAc6gAwIBAgIBATANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDEwkxMjcu
MC4wLjEwHhcNMjYwNzEzMDgwNDI2WhcNMjYwNzE0MDkwNDI2WjAUMRIwEAYDVQQD
EwkxMjcuMC4wLjEwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC5Hc/T
72VvvrgR436eYOmPSsUjlfnB3vvnKax1Nw+nhlBYPmVJn/iNvXDulJ0HqFWLFHN1
cJysrebCDRcoFZscliEwYu7zuh3MbqTissvydiMjRK+OjrqFicvIWci3XG/oN5kN
r2gFGXZJ9hrUpjtNIBUIU9M2putkbFemRuV9czDqVTsTQWj9FefMzhOdfatcnPkc
ORCJe9cQuKynHe3Qc21b6nKSMg9RzH+bTwGFotcBlpsLWkcVM9Jodb5yL9RsRuV4
YhqsNOkaXwO0q9PmSzehkbvu2odXz/TFi7HI08RuU97dC3RsPQwVGn44l17fnZRm
RHPbxjSh4/0OXmdHAgMBAAGjQzBBMA4GA1UdDwEB/wQEAwIFoDATBgNVHSUEDDAK
BggrBgEFBQcDATAaBgNVHREEEzARgglsb2NhbGhvc3SHBH8AAAEwDQYJKoZIhvcN
AQELBQADggEBAJSpGSTK81AziK4EK5vpd1WoaKLb4G+OZsF8UwiHR/u8GR2jr4MC
V54N5FQXpINB9OABIKtBxTfrNiXWebCpLLSj42xg1qBw1D3AZ58lMqzqBR5D6ZWg
kWk29NNu2Kw9WHJvLkifCHQK6wmKEcaIfAmy+1xn0IaYiM6X+ELVWj34ri9Ja4d1
yNLBLtmrFy2LuGdoQjqB3pUKgcX6wZ6ogKmpAjpAG1phqnRCqstWEASaMqJ5xv+Z
QA16rmRMPOiirSi4e0/WUQqH1nF+6Qyi5nQLQpZPh9ds9pEnCuB6ECrXFFQPjL2y
GnjWYu2HHO1qQR7nTZ+e9TQ0sZIM4LV5qIE=
-----END CERTIFICATE-----"""

TEST_KEY_PEM = """-----BEGIN RSA PRIVATE KEY-----
MIIEpQIBAAKCAQEAuR3P0+9lb764EeN+nmDpj0rFI5X5wd775ymsdTcPp4ZQWD5l
SZ/4jb1w7pSdB6hVixRzdXCcrK3mwg0XKBWbHJYhMGLu87odzG6k4rLL8nYjI0Sv
jo66hYnLyFnIt1xv6DeZDa9oBRl2SfYa1KY7TSAVCFPTNqbrZGxXpkblfXMw6lU7
E0Fo/RXnzM4TnX2rXJz5HDkQiXvXELispx3t0HNtW+pykjIPUcx/m08BhaLXAZab
C1pHFTPSaHW+ci/UbEbleGIarDTpGl8DtKvT5ks3oZG77tqHV8/0xYuxyNPEblPe
3Qt0bD0MFRp+OJde352UZkRz28Y0oeP9Dl5nRwIDAQABAoIBAA9jFnzX19cng7Zc
8g/pH1DdVrCkDTwbtFWdJawilQcIR5JmMVYi2W6ysfnq2Xii+eVTIFvBLgy+ccFs
hCG9VgTUx9J1TsZskICHK+Z6FTDEuBv84BjZ7VAfSZSQPfpb0SN8x5iXHW7bFHWG
YumNHb3F7mmgShyvWD6jMM/t8bJxJe8rgpNMSu/yA/VsfpEy9fOiBtJ6Jo4o+kPZ
lEZShe0st21KJigN0JwhoA8bQpWyKpFtQAcqRN9Mlc780ODRMrZXrs1jQ8at7KSN
uC7tReTNPUg0j9ql7Ey6J8qe0UtWea+BF8RRkG+1bmdnQZmqa1qShiuK5ZnAWMal
CIhpZFkCgYEAyMGuHI150XYsgBd4v8I94eYlc5CkwClTTbULe2ghzi1i9Z/0o+9E
Dn/9NQWxidqC69SGCx+vBbm7RUV14ubrlNRwVq/VAqsIqBTnhwpbRY/vqPNWNgIx
mPNFRLlTnC5nKwqbDWixTwjfHl++1ctG42+yMIHJWJsMKg5o5sW63Z8CgYEA7A5c
vVkSLxJSXuxdk1ATNL7owYJqswBiCYrdqS3vfnYtpW+FVFkyiUrzBXgEciYSoWQa
bt1AV5UQaFA/tI5jW2ljspDoNR65Am0kXeygFA4rJkkz4kIC82P63I/GFLP57sFS
K6PlAF4WF8ae7gFeQCGQC8CG/T18kmOmXDakxVkCgYEArRR+PdOjcPkHSK/zxK98
lqPLKiVMRPfcACTUb2LJsm3i4Y00Z5nC/RVPgkUUWZtwQE4L+s8oIDGOyRwnlKYt
+TRmXfZeGVzHq9HKAtzk78Y2g1y3uPyPMiSaVbPJ598Bx1PvddIK++7UHeXCK6SD
y1XjNHrQ0nlqNWATBNL4VlUCgYEAqouR20dgCNwu4N/al5Tx21jWpwBHgH4VVpma
niFO98oAHpdc99zd0y1wORJF/AafzTSamGCHnP9YdFUOQa/h/ug8nIVvDvncZvFd
pfJQkUzPRgD7WEujAB/K3dGOJeUF/MZ1TIxD5ikTwyfAKWqZorHc9XCq1om217jh
N5xPHTkCgYEAvfMPDvvsysu1nvL6wQMG5/5Iu4s7/EzDQFLBJ7Eq8fp4UGZ1i12d
CTzc/gn1Sf6aOlUnujUc7hVSr9DMt/4rIs/9z4BX3yEJbsuKKVXQUr4P38uHYL9V
STR6d5nV/3BCZT0NTgw74oMe1ZGGEmVIg1Pki/6yQ4jcjVg4QnoqUD4=
-----END RSA PRIVATE KEY-----"""


class TcpTlsTests(unittest.TestCase):
    def test_tcp_client_can_connect_over_tls_with_ca_verification(self) -> None:
        server_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        cert_path = Path(__file__).with_name("tmp_test_cert.pem")
        key_path = Path(__file__).with_name("tmp_test_key.pem")
        cert_path.write_text(TEST_CERT_PEM, encoding="utf-8")
        key_path.write_text(TEST_KEY_PEM, encoding="utf-8")
        self.addCleanup(lambda: cert_path.unlink(missing_ok=True))
        self.addCleanup(lambda: key_path.unlink(missing_ok=True))
        server_ctx.load_cert_chain(certfile=str(cert_path), keyfile=str(key_path))

        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        host, port = listener.getsockname()
        errors: list[Exception] = []

        def recv_exact(conn: socket.socket, n: int) -> bytes:
            buf = bytearray()
            while len(buf) < n:
                chunk = conn.recv(n - len(buf))
                if not chunk:
                    raise ConnectionError("connection closed")
                buf.extend(chunk)
            return bytes(buf)

        def recv_variant(conn: socket.socket) -> int:
            payload_len = struct.unpack(">I", recv_exact(conn, 4))[0]
            payload = recv_exact(conn, payload_len)
            field, _ = decode_client_request_variant(payload)
            return field

        def mock_server() -> None:
            try:
                conn, _ = listener.accept()
                with server_ctx.wrap_socket(conn, server_side=True) as tls_conn:
                    self.assertEqual(REQUEST_FIELDS["AUTH"], recv_variant(tls_conn))
                    tls_conn.sendall(frame_client_response(RESPONSE_FIELDS["AUTH_OK"], b""))

                    self.assertEqual(REQUEST_FIELDS["PING"], recv_variant(tls_conn))
                    tls_conn.sendall(frame_client_response(RESPONSE_FIELDS["PONG"], b""))
            except Exception as exc:  # pragma: no cover
                errors.append(exc)
            finally:
                listener.close()

        th = threading.Thread(target=mock_server, daemon=True)
        th.start()

        with DittoTcpClient(
            host=host,
            port=port,
            auth_token="token",
            connect_timeout_secs=2.0,
            socket_timeout_secs=2.0,
            tls=True,
            tls_ca_cert=TEST_CERT_PEM,
            tls_server_name="localhost",
        ) as client:
            self.assertTrue(client.ping())

        th.join(timeout=2.0)
        self.assertFalse(th.is_alive(), "mock server thread did not finish")
        if errors:
            raise errors[0]
