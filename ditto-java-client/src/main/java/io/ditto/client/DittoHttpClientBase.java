package io.ditto.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * DittoHttpClientBase – infrastructure for the generated {@link DittoHttpClient}.
 *
 * <p>This file is maintained <em>manually</em>. It contains the constructor,
 * builder, and internal helpers ({@code requestBuilder}, {@code send},
 * {@code assertOk}, {@code urlEncode}). The public endpoint methods live in
 * {@link DittoHttpClient}, which is <em>generated</em> from
 * {@code api/ditto-http-api.yaml}.
 *
 * <p>Connects to dittod HTTP REST port 7778. Uses the JDK built-in
 * {@link HttpClient} (Java 11+). JSON is parsed with Jackson.
 *
 * <p>Usage:
 * <pre>{@code
 *   DittoHttpClient client = DittoHttpClient.builder()
 *       .host("localhost").port(7778).build();
 *   client.set("key", "value", 60);
 *   DittoGetResult result = client.get("key");
 *   client.close();
 * }</pre>
 */
public abstract class DittoHttpClientBase {

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Jackson mapper shared by all generated endpoint methods. */
    protected final ObjectMapper mapper = new ObjectMapper();

    private final String     baseUrl;
    private final String     authHeader;
    private final HttpClient httpClient;

    // ── Constructor ───────────────────────────────────────────────────────────

    protected DittoHttpClientBase(Builder<?> b) {
        String scheme  = b.tls ? "https" : "http";
        this.baseUrl   = scheme + "://" + b.host + ":" + b.port;

        if (b.username != null && b.password != null) {
            String creds = b.username + ":" + b.password;
            this.authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
        } else {
            this.authHeader = null;
        }

        HttpClient.Builder httpBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10));

        if (b.tls && !b.rejectUnauthorized) {
            throw new IllegalArgumentException(
                    "rejectUnauthorized(false) is insecure and is no longer supported. "
                            + "Use trustedCertPath(...) to trust a specific self-signed/server certificate."
            );
        }

        if (b.tls && b.trustedCertPath != null && !b.trustedCertPath.isBlank()) {
            try {
                SSLContext sslContext = buildPinnedTlsContext(b.trustedCertPath);
                httpBuilder.sslContext(sslContext);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize TLS context from trustedCertPath", e);
            }
        }

        this.httpClient = httpBuilder.build();
    }

    // ── Public lifecycle ──────────────────────────────────────────────────────

    /** Close the underlying HTTP client (Java 21+). */
    public void close() {
        httpClient.close();
    }

    // ── Internal helpers (used by generated endpoint methods) ─────────────────

    /** Build an {@link HttpRequest.Builder} for {@code path}, with auth header set. */
    protected HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10));
        if (authHeader != null) b.header("Authorization", authHeader);
        return b;
    }

    /** Send {@code req} and return the response body as a String. */
    protected HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Throw a {@link DittoException} if the response status is not 2xx. */
    protected void assertOk(HttpResponse<String> resp) throws IOException {
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) return;
        String message = "HTTP " + resp.statusCode();
        try {
            Map<?, ?> body = mapper.readValue(resp.body(), Map.class);
            Object msg = body.get("message");
            if (msg == null) msg = body.get("error");
            if (msg != null) message = msg.toString();
        } catch (Exception ignored) {}
        throw switch (resp.statusCode()) {
            case 503 -> new DittoException(DittoErrorCode.NODE_INACTIVE,  message);
            case 504 -> new DittoException(DittoErrorCode.WRITE_TIMEOUT,  message);
            case 404 -> new DittoException(DittoErrorCode.KEY_NOT_FOUND,  message);
            default  -> new DittoException(DittoErrorCode.INTERNAL_ERROR, message);
        };
    }

    /** URL-encode a key for use in a path segment. */
    protected static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Build an SSL context that trusts exactly one provided X.509 certificate.
     * Useful for self-signed cert deployments without disabling TLS validation.
     */
    private static SSLContext buildPinnedTlsContext(String certPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (InputStream in = Files.newInputStream(Path.of(certPath))) {
            cert = cf.generateCertificate(in);
        }

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("ditto-trusted-cert", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        return sslContext;
    }

    // ── Builder base ──────────────────────────────────────────────────────────

    /**
     * Base builder for {@link DittoHttpClient}.
     * Subclassed by the generated {@code DittoHttpClient.Builder}.
     */
    @SuppressWarnings("unchecked")
    public abstract static class Builder<B extends Builder<B>> {
        String  host     = "localhost";
        int     port     = 7778;
        boolean tls      = false;
        boolean rejectUnauthorized = true;
        String  trustedCertPath;
        String  username;
        String  password;

        public B host(String host)   { this.host     = host;   return (B) this; }
        public B port(int port)      { this.port     = port;   return (B) this; }
        public B tls(boolean tls)    { this.tls      = tls;    return (B) this; }
        public B rejectUnauthorized(boolean r) { this.rejectUnauthorized = r; return (B) this; }
        public B trustedCertPath(String path) { this.trustedCertPath = path; return (B) this; }
        public B username(String u)  { this.username = u;      return (B) this; }
        public B password(String p)  { this.password = p;      return (B) this; }

        public abstract DittoHttpClient build();
    }
}
