package io.ditto.client;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

final class TlsTestSupport {

    static final String CERT_PEM = """
            -----BEGIN CERTIFICATE-----
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
            -----END CERTIFICATE-----
            """;

    static final String KEY_PEM = """
            -----BEGIN RSA PRIVATE KEY-----
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
            -----END RSA PRIVATE KEY-----
            """;

    private TlsTestSupport() {}

    static SSLContext serverContext() throws GeneralSecurityException, IOException {
        X509Certificate certificate = parseCertificate(CERT_PEM);
        PrivateKey privateKey = parseRsaPrivateKey(KEY_PEM);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", privateKey, "changeit".toCharArray(), new java.security.cert.Certificate[]{certificate});

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "changeit".toCharArray());

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        return context;
    }

    private static X509Certificate parseCertificate(String pem) throws GeneralSecurityException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    private static PrivateKey parseRsaPrivateKey(String pem) throws GeneralSecurityException {
        byte[] pkcs1 = parsePemBlock(pem, "RSA PRIVATE KEY");
        byte[] pkcs8 = wrapPkcs1RsaAsPkcs8(pkcs1);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private static byte[] parsePemBlock(String pem, String label) {
        String normalized = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    private static byte[] wrapPkcs1RsaAsPkcs8(byte[] pkcs1) {
        byte[] version = derInteger(BigInteger.ZERO.toByteArray());
        byte[] algorithmIdentifier = derSequence(
                derOid(new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01}),
                derNull()
        );
        byte[] privateKey = derOctetString(pkcs1);
        return derSequence(version, algorithmIdentifier, privateKey);
    }

    private static byte[] derSequence(byte[]... values) {
        return derTag((byte) 0x30, concat(values));
    }

    private static byte[] derInteger(byte[] value) {
        return derTag((byte) 0x02, value);
    }

    private static byte[] derOid(byte[] value) {
        return derTag((byte) 0x06, value);
    }

    private static byte[] derNull() {
        return new byte[]{0x05, 0x00};
    }

    private static byte[] derOctetString(byte[] value) {
        return derTag((byte) 0x04, value);
    }

    private static byte[] derTag(byte tag, byte[] value) {
        byte[] length = derLength(value.length);
        byte[] out = new byte[1 + length.length + value.length];
        out[0] = tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(value, 0, out, 1 + length.length, value.length);
        return out;
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        byte[] raw = BigInteger.valueOf(length).toByteArray();
        if (raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            raw = trimmed;
        }
        byte[] out = new byte[1 + raw.length];
        out[0] = (byte) (0x80 | raw.length);
        System.arraycopy(raw, 0, out, 1, raw.length);
        return out;
    }

    private static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) {
            size += value.length;
        }
        byte[] out = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, out, offset, value.length);
            offset += value.length;
        }
        return out;
    }
}
