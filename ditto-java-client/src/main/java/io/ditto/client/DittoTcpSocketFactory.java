package io.ditto.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

final class DittoTcpSocketFactory {

    Socket openSocket(
            String host,
            int port,
            int connectTimeoutMs,
            int readTimeoutMs,
            boolean tlsEnabled,
            String tlsCaCert,
            String tlsServerName
    ) throws IOException {
        Socket rawSocket = new Socket();
        rawSocket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        rawSocket.setTcpNoDelay(true);
        rawSocket.setSoTimeout(readTimeoutMs);
        if (!tlsEnabled) {
            return rawSocket;
        }

        try {
            SSLSocketFactory factory = buildTlsContext(tlsCaCert).getSocketFactory();
            String serverName = tlsServerName != null ? tlsServerName : host;
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(rawSocket, serverName, port, true);
            SSLParameters parameters = sslSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            sslSocket.setSSLParameters(parameters);
            sslSocket.startHandshake();
            return sslSocket;
        } catch (GeneralSecurityException e) {
            try {
                rawSocket.close();
            } catch (IOException ignored) {
                // best effort cleanup if TLS bootstrap fails
            }
            throw new IOException("Failed to initialize TCP TLS connection", e);
        }
    }

    private SSLContext buildTlsContext(String tlsCaCert) throws GeneralSecurityException, IOException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (tlsCaCert != null) {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            try (InputStream in = new ByteArrayInputStream(loadTlsCaCertBytes(tlsCaCert))) {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                Certificate certificate = certificateFactory.generateCertificate(in);
                trustStore.setCertificateEntry("ditto-ca", certificate);
            }
            trustManagerFactory.init(trustStore);
        } else {
            trustManagerFactory.init((KeyStore) null);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagerFactory.getTrustManagers(), null);
        return context;
    }

    private static byte[] loadTlsCaCertBytes(String input) throws IOException {
        if (input.contains("BEGIN CERTIFICATE")) {
            return input.getBytes(StandardCharsets.UTF_8);
        }
        return Files.readAllBytes(Path.of(input));
    }
}
