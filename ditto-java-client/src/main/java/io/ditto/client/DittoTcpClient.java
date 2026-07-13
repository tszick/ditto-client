package io.ditto.client;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * DittoTcpClient – connects directly to dittod TCP port 7777.
 *
 * <p>Uses the protobuf-encoded {@code Envelope} wire protocol defined in
 * {@code ditto-protocol/proto/ditto.proto} over a persistent TCP connection.
 * All public methods are {@code synchronized}, so concurrent calls from
 * multiple threads are safely serialized over the single socket.
 *
 * <p>Wire framing: 4-byte big-endian length prefix before each protobuf
 * Envelope. Each outbound payload is an {@code Envelope { version=1,
 * client_request: <variant> }} and each inbound payload carries a
 * {@code client_response: <variant>}.
 *
 * <p>Usage:
 * <pre>{@code
 *   try (DittoTcpClient client = new DittoTcpClient("localhost", 7777)) {
 *       client.connect();
 *       client.set("key", "value", 60);
 *       DittoGetResult result = client.get("key");
 *   }
 * }</pre>
 */
public class DittoTcpClient implements Closeable {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_READ_TIMEOUT_MS    = 10_000;
    private static final int DEFAULT_MAX_FRAME_BYTES    = 8 * 1024 * 1024;

    private final String host;
    private final int    port;
    private final String authToken;
    private final boolean strictMode;
    private final boolean autoReconnect;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean tlsEnabled;
    private final String tlsCaCert;
    private final String tlsServerName;

    private Socket           socket;
    private DataInputStream  in;
    private OutputStream     out;

    // ── Constructors ──────────────────────────────────────────────────────────

    public DittoTcpClient() {
        this("localhost", 7777, null, false);
    }

    public DittoTcpClient(String host, int port) {
        this(host, port, null, false);
    }

    public DittoTcpClient(String host, int port, String authToken) {
        this(host, port, authToken, false);
    }

    public DittoTcpClient(String host, int port, String authToken, boolean strictMode) {
        this(host, port, authToken, strictMode, false);
    }

    public DittoTcpClient(String host, int port, String authToken, boolean strictMode, boolean autoReconnect) {
        this(host, port, authToken, strictMode, autoReconnect, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public DittoTcpClient(
            String host,
            int port,
            String authToken,
            boolean strictMode,
            boolean autoReconnect,
            boolean tlsEnabled,
            String tlsCaCert,
            String tlsServerName
    ) {
        this(
                host,
                port,
                authToken,
                strictMode,
                autoReconnect,
                DEFAULT_CONNECT_TIMEOUT_MS,
                DEFAULT_READ_TIMEOUT_MS,
                tlsEnabled,
                tlsCaCert,
                tlsServerName
        );
    }

    public DittoTcpClient(
            String host,
            int port,
            String authToken,
            boolean strictMode,
            boolean autoReconnect,
            int connectTimeoutMs,
            int readTimeoutMs
    ) {
        this(host, port, authToken, strictMode, autoReconnect, connectTimeoutMs, readTimeoutMs, false, null, null);
    }

    public DittoTcpClient(
            String host,
            int port,
            String authToken,
            boolean strictMode,
            boolean autoReconnect,
            int connectTimeoutMs,
            int readTimeoutMs,
            boolean tlsEnabled,
            String tlsCaCert,
            String tlsServerName
    ) {
        this.host = host;
        this.port = port;
        this.authToken = authToken;
        this.strictMode = strictMode;
        this.autoReconnect = autoReconnect;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
        this.tlsEnabled = tlsEnabled;
        this.tlsCaCert = normalizeOptionalText(tlsCaCert);
        this.tlsServerName = normalizeOptionalText(tlsServerName);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Open the TCP connection. Must be called before any other method. */
    public synchronized void connect() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        socket = openSocket();
        in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new BufferedOutputStream(socket.getOutputStream());

        if (authToken != null) {
            sendFrame(encodeAuth(authToken));
            Response resp = readResponse();
            if (resp.type == ResponseType.ERROR) {
                close();
                throw new DittoException(resp.errorCode, resp.message);
            }
            if (resp.type != ResponseType.AUTH_OK) {
                close();
                throw new IOException("Unexpected auth response: " + resp.type);
            }
        }
    }

    /** Gracefully close the TCP connection. */
    @Override
    public synchronized void close() throws IOException {
        closeSocketOnly();
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /** Send a Ping and return {@code true} when Pong is received. */
    public synchronized boolean ping() throws IOException {
        return sendAndRead(encodePing()).type == ResponseType.PONG;
    }

    /**
     * Get a key. Returns {@code null} when the key does not exist or has expired.
     */
    public synchronized DittoGetResult get(String key) throws IOException {
        return get(key, null);
    }

    public synchronized DittoGetResult get(String key, String namespace) throws IOException {
        validateCoreInputs("get", key, namespace);
        Response resp = sendAndRead(encodeGet(key, namespace));
        return switch (resp.type) {
            case VALUE     -> new DittoGetResult(resp.value, resp.version);
            case NOT_FOUND -> null;
            case ERROR     -> throw new DittoException(resp.errorCode, resp.message);
            default        -> throw new IOException("Unexpected response: " + resp.type);
        };
    }

    /**
     * Set a key with no TTL (persists until deleted or evicted).
     */
    public synchronized DittoSetResult set(String key, String value) throws IOException {
        return set(key, value.getBytes(StandardCharsets.UTF_8), 0);
    }

    /**
     * Set a key. {@code ttlSecs} is optional; 0 means no expiry.
     */
    public synchronized DittoSetResult set(String key, String value, long ttlSecs)
            throws IOException {
        return set(key, value.getBytes(StandardCharsets.UTF_8), ttlSecs);
    }

    /**
     * Set a key in a specific namespace with optional TTL.
     */
    public synchronized DittoSetResult set(String key, String value, long ttlSecs, String namespace)
            throws IOException {
        return set(key, value.getBytes(StandardCharsets.UTF_8), ttlSecs, namespace);
    }

    /**
     * Set a key with a raw byte array value and no TTL.
     */
    public synchronized DittoSetResult set(String key, byte[] value) throws IOException {
        return set(key, value, 0);
    }

    /**
     * Set a key with a raw byte array value and optional TTL.
     */
    public synchronized DittoSetResult set(String key, byte[] value, long ttlSecs)
            throws IOException {
        return set(key, value, ttlSecs, null);
    }

    /**
     * Set a key with a raw byte array value, optional TTL, and optional namespace.
     */
    public synchronized DittoSetResult set(String key, byte[] value, long ttlSecs, String namespace)
            throws IOException {
        validateCoreInputs("set", key, namespace);
        Response resp = sendAndRead(encodeSet(key, value, ttlSecs, namespace));
        return switch (resp.type) {
            case OK    -> new DittoSetResult(resp.version);
            case ERROR -> throw new DittoException(resp.errorCode, resp.message);
            default    -> throw new IOException("Unexpected response: " + resp.type);
        };
    }

    /** Atomic create-if-absent with no TTL. */
    public synchronized DittoSetNxResult setNx(String key, String value) throws IOException {
        return setNx(key, value.getBytes(StandardCharsets.UTF_8), 0, null);
    }

    /** Atomic create-if-absent with optional TTL. */
    public synchronized DittoSetNxResult setNx(String key, String value, long ttlSecs) throws IOException {
        return setNx(key, value.getBytes(StandardCharsets.UTF_8), ttlSecs, null);
    }

    /** Atomic create-if-absent with optional TTL and namespace. */
    public synchronized DittoSetNxResult setNx(String key, byte[] value, long ttlSecs, String namespace)
            throws IOException {
        validateCoreInputs("set", key, namespace);
        Response resp = sendAndRead(encodeSetNx(key, value, ttlSecs, namespace));
        return switch (resp.type) {
            case SET_NX -> new DittoSetNxResult(resp.created, resp.version);
            case ERROR  -> throw new DittoException(resp.errorCode, resp.message);
            default     -> throw new IOException("Unexpected response: " + resp.type);
        };
    }

    /** Atomic counter increment by 1, creating the key at 1 if absent. */
    public synchronized DittoCounterResult incr(String key) throws IOException {
        return incr(key, 1, 0, null);
    }

    /** Atomic counter increment by {@code delta}, creating the key at {@code delta} if absent. */
    public synchronized DittoCounterResult incr(String key, long delta) throws IOException {
        return incr(key, delta, 0, null);
    }

    /**
     * Atomic counter increment. Creates the key at {@code delta} if absent
     * (with {@code ttlSecsOnCreate}); never resets the TTL of an existing key.
     */
    public synchronized DittoCounterResult incr(String key, long delta, long ttlSecsOnCreate, String namespace)
            throws IOException {
        validateCoreInputs("set", key, namespace);
        Response resp = sendAndRead(encodeIncr(key, delta, ttlSecsOnCreate, namespace));
        return switch (resp.type) {
            case COUNTER -> new DittoCounterResult(resp.counterValue, resp.version);
            case ERROR   -> throw new DittoException(resp.errorCode, resp.message);
            default      -> throw new IOException("Unexpected response: " + resp.type);
        };
    }

    /**
     * Delete a key. Returns {@code true} if the key existed, {@code false} if not found.
     */
    public synchronized boolean delete(String key) throws IOException {
        return delete(key, null);
    }

    public synchronized boolean delete(String key, String namespace) throws IOException {
        validateCoreInputs("delete", key, namespace);
        Response resp = sendAndRead(encodeDelete(key, namespace));
        return switch (resp.type) {
            case DELETED   -> true;
            case NOT_FOUND -> false;
            case ERROR     -> throw new DittoException(resp.errorCode, resp.message);
            default        -> throw new IOException("Unexpected response: " + resp.type);
        };
    }

    /**
     * Delete all keys matching a glob-style pattern ('*' wildcard).
     */
    public synchronized DittoDeleteByPatternResult deleteByPattern(String pattern) throws IOException {
        return deleteByPattern(pattern, null);
    }

    public synchronized DittoDeleteByPatternResult deleteByPattern(String pattern, String namespace) throws IOException {
        validatePatternInputs("deleteByPattern", pattern, namespace);
        Response resp = sendAndRead(encodeDeleteByPattern(pattern, namespace));
        return switch (resp.type) {
            case PATTERN_DELETED -> new DittoDeleteByPatternResult(resp.count);
            case ERROR           -> throw new DittoException(resp.errorCode, resp.message);
            default              -> throw new IOException("Unexpected response: " + resp.type);
        };
    }

    /**
     * Update TTL for all keys matching a glob-style pattern ('*' wildcard).
     * {@code ttlSecs <= 0} removes TTL from matched keys.
     */
    public synchronized DittoSetTtlByPatternResult setTtlByPattern(String pattern, long ttlSecs)
            throws IOException {
        return setTtlByPattern(pattern, ttlSecs, null);
    }

    public synchronized DittoSetTtlByPatternResult setTtlByPattern(String pattern, long ttlSecs, String namespace)
            throws IOException {
        validatePatternInputs("setTtlByPattern", pattern, namespace);
        Response resp = sendAndRead(encodeSetTtlByPattern(pattern, ttlSecs, namespace));
        return switch (resp.type) {
            case PATTERN_TTL_UPDATED -> new DittoSetTtlByPatternResult(resp.count);
            case ERROR               -> throw new DittoException(resp.errorCode, resp.message);
            default                  -> throw new IOException("Unexpected response: " + resp.type);
        };
    }

    /** Subscribe to updates on a key. */
    public synchronized void watch(String key) throws IOException {
        watch(key, null);
    }

    public synchronized void watch(String key, String namespace) throws IOException {
        validateCoreInputs("watch", key, namespace);
        Response resp = sendAndRead(encodeWatch(key, namespace));
        switch (resp.type) {
            case WATCHING -> {
                return;
            }
            case ERROR -> throw new DittoException(resp.errorCode, resp.message);
            default -> throw new IOException("Unexpected response: " + resp.type);
        }
    }

    /** Cancel a key subscription. */
    public synchronized void unwatch(String key) throws IOException {
        unwatch(key, null);
    }

    public synchronized void unwatch(String key, String namespace) throws IOException {
        validateCoreInputs("unwatch", key, namespace);
        Response resp = sendAndRead(encodeUnwatch(key, namespace));
        switch (resp.type) {
            case UNWATCHED -> {
                return;
            }
            case ERROR -> throw new DittoException(resp.errorCode, resp.message);
            default -> throw new IOException("Unexpected response: " + resp.type);
        }
    }

    /** Block until the next watch event frame arrives. */
    public synchronized DittoWatchEvent waitForWatchEvent() throws IOException {
        Response resp = readResponse();
        return switch (resp.type) {
            case WATCH_EVENT -> new DittoWatchEvent(resp.key, resp.hasValue ? resp.value : null, resp.version);
            case ERROR -> throw new DittoException(resp.errorCode, resp.message);
            default -> throw new IOException("Unexpected response: " + resp.type);
        };
    }

    // ── Protobuf wire — request encoders ──────────────────────────────────────

    private byte[] encodeGet(String key, String namespace) {
        return Wire.wrapClientRequest(Wire.REQ_GET, Wire.encodeKeyNamespace(key, namespace));
    }

    private byte[] encodeSet(String key, byte[] value, long ttlSecs, String namespace) {
        return Wire.wrapClientRequest(Wire.REQ_SET, Wire.encodeSetRequest(key, value, ttlSecs, namespace));
    }

    private byte[] encodeDelete(String key, String namespace) {
        return Wire.wrapClientRequest(Wire.REQ_DELETE, Wire.encodeKeyNamespace(key, namespace));
    }

    private byte[] encodeSetNx(String key, byte[] value, long ttlSecs, String namespace) {
        // SET_NX reuses the SetRequest wire shape (proto: SetRequest set_nx = 10).
        return Wire.wrapClientRequest(Wire.REQ_SET_NX, Wire.encodeSetRequest(key, value, ttlSecs, namespace));
    }

    private byte[] encodeIncr(String key, long delta, long ttlSecsOnCreate, String namespace) {
        return Wire.wrapClientRequest(Wire.REQ_INCR, Wire.encodeIncrRequest(key, delta, ttlSecsOnCreate, namespace));
    }

    private byte[] encodePing() {
        return Wire.wrapClientRequest(Wire.REQ_PING, new byte[0]);
    }

    private byte[] encodeAuth(String token) {
        return Wire.wrapClientRequest(Wire.REQ_AUTH, Wire.encodeAuthRequest(token));
    }

    private byte[] encodeWatch(String key, String namespace) {
        return Wire.wrapClientRequest(Wire.REQ_WATCH, Wire.encodeKeyNamespace(key, namespace));
    }

    private byte[] encodeUnwatch(String key, String namespace) {
        return Wire.wrapClientRequest(Wire.REQ_UNWATCH, Wire.encodeKeyNamespace(key, namespace));
    }

    private byte[] encodeDeleteByPattern(String pattern, String namespace) {
        return Wire.wrapClientRequest(Wire.REQ_DELETE_BY_PATTERN, Wire.encodePatternNamespace(pattern, namespace));
    }

    private byte[] encodeSetTtlByPattern(String pattern, long ttlSecs, String namespace) {
        return Wire.wrapClientRequest(Wire.REQ_SET_TTL_BY_PATTERN, Wire.encodeSetTtlByPatternRequest(pattern, ttlSecs, namespace));
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateCoreInputs(String op, String key, String namespace) {
        if (!strictMode) return;
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid " + op + " request: key must not be empty.");
        }
        if (!isStrictToken(key)) {
            throw new IllegalArgumentException(
                    "Invalid " + op + " request: key contains unsupported characters. Allowed: [A-Za-z0-9._:-]"
            );
        }
        if (namespace == null) return;
        String ns = namespace.trim();
        if (ns.isEmpty()) {
            throw new IllegalArgumentException("Invalid " + op + " request: namespace must not be blank when provided.");
        }
        if (ns.contains("::")) {
            throw new IllegalArgumentException("Invalid " + op + " request: namespace must not contain '::'.");
        }
        if (!isStrictToken(ns)) {
            throw new IllegalArgumentException(
                    "Invalid " + op + " request: namespace contains unsupported characters. Allowed: [A-Za-z0-9._:-]"
            );
        }
    }

    private void validatePatternInputs(String op, String pattern, String namespace) {
        if (!strictMode) return;
        if (pattern == null || pattern.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid " + op + " request: pattern must not be empty.");
        }
        if (!isStrictPattern(pattern.trim())) {
            throw new IllegalArgumentException(
                    "Invalid " + op + " request: pattern contains unsupported characters. Allowed: [A-Za-z0-9._:-*]"
            );
        }
        if (namespace == null) return;
        String ns = namespace.trim();
        if (ns.isEmpty()) {
            throw new IllegalArgumentException("Invalid " + op + " request: namespace must not be blank when provided.");
        }
        if (ns.contains("::")) {
            throw new IllegalArgumentException("Invalid " + op + " request: namespace must not contain '::'.");
        }
        if (!isStrictToken(ns)) {
            throw new IllegalArgumentException(
                    "Invalid " + op + " request: namespace contains unsupported characters. Allowed: [A-Za-z0-9._:-]"
            );
        }
    }

    private static boolean isStrictToken(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isStrictPattern(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':' || c == '*') {
                continue;
            }
            return false;
        }
        return true;
    }

    // ── Network I/O ───────────────────────────────────────────────────────────

    private void sendFrame(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    private Response sendAndRead(byte[] data) throws IOException {
        try {
            sendFrame(data);
            return readResponse();
        } catch (IOException first) {
            closeSocketOnly();
            if (!autoReconnect) {
                throw first;
            }
            connect();
            sendFrame(data);
            return readResponse();
        }
    }

    private void closeSocketOnly() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
            // best effort cleanup before reconnect
        } finally {
            socket = null;
            in = null;
            out = null;
        }
    }

    private Socket openSocket() throws IOException {
        Socket rawSocket = new Socket();
        rawSocket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        rawSocket.setTcpNoDelay(true);
        rawSocket.setSoTimeout(readTimeoutMs);
        if (!tlsEnabled) {
            return rawSocket;
        }

        try {
            SSLSocketFactory factory = buildTlsContext().getSocketFactory();
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

    private SSLContext buildTlsContext() throws GeneralSecurityException, IOException {
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

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Response readResponse() throws IOException {
        // 4-byte big-endian length prefix
        int payloadLen = in.readInt();
        if (payloadLen <= 0 || payloadLen > DEFAULT_MAX_FRAME_BYTES) {
            throw new IOException(
                    "Incoming frame has invalid size: " + payloadLen + " (limit " + DEFAULT_MAX_FRAME_BYTES + ")"
            );
        }
        byte[] payload = new byte[payloadLen];
        in.readFully(payload);
        return Wire.decodeResponse(payload);
    }

    // ── Response container ────────────────────────────────────────────────────

    enum ResponseType {
        VALUE, OK, DELETED, NOT_FOUND, PONG, AUTH_OK, ERROR, WATCHING, UNWATCHED, WATCH_EVENT, PATTERN_DELETED, PATTERN_TTL_UPDATED, SET_NX, COUNTER
    }

    static final class Response {
        ResponseType   type;
        String         key;
        byte[]         value;
        boolean        hasValue;
        long           version;
        DittoErrorCode errorCode;
        String         message;
        long           count;
        boolean        created;
        long           counterValue;
    }

    // ── Protobuf wire format helper ──────────────────────────────────────────
    //
    // Source of truth: ditto-protocol/proto/ditto.proto (proto3, package
    // ditto.protocol.v1). This class is hand-rolled to avoid pulling in
    // protoc/protobuf-java; the field numbers below MUST stay in sync with
    // ditto.proto.

    static final class Wire {
        static final int PROTOCOL_VERSION = 1;

        // Envelope field numbers
        static final int ENV_VERSION         = 1;
        static final int ENV_CLIENT_REQUEST  = 2;
        static final int ENV_CLIENT_RESPONSE = 3;

        // ClientRequest oneof field numbers
        static final int REQ_GET                 = 1;
        static final int REQ_SET                 = 2;
        static final int REQ_DELETE              = 3;
        static final int REQ_PING                = 4;
        static final int REQ_AUTH                = 5;
        static final int REQ_WATCH               = 6;
        static final int REQ_UNWATCH             = 7;
        static final int REQ_DELETE_BY_PATTERN   = 8;
        static final int REQ_SET_TTL_BY_PATTERN  = 9;
        static final int REQ_SET_NX              = 10;
        static final int REQ_INCR                = 11;

        // ClientResponse oneof field numbers
        static final int RESP_VALUE                = 1;
        static final int RESP_OK                   = 2;
        static final int RESP_DELETED              = 3;
        static final int RESP_NOT_FOUND            = 4;
        static final int RESP_PONG                 = 5;
        static final int RESP_AUTH_OK              = 6;
        static final int RESP_ERROR                = 7;
        static final int RESP_WATCHING             = 8;
        static final int RESP_UNWATCHED            = 9;
        static final int RESP_WATCH_EVENT          = 10;
        static final int RESP_PATTERN_DELETED      = 11;
        static final int RESP_PATTERN_TTL_UPDATED  = 12;
        static final int RESP_SET_NX               = 13;
        static final int RESP_COUNTER              = 14;

        // Wire types
        static final int WT_VARINT = 0;
        static final int WT_LD     = 2;

        // Inner-message field numbers
        static final int KN_KEY        = 1;
        static final int KN_NAMESPACE  = 2;
        static final int PN_PATTERN    = 1;
        static final int PN_NAMESPACE  = 2;
        static final int SR_KEY        = 1;
        static final int SR_VALUE      = 2;
        static final int SR_TTL_SECS   = 3;
        static final int SR_NAMESPACE  = 4;
        static final int STBP_PATTERN  = 1;
        static final int STBP_TTL_SECS = 2;
        static final int STBP_NAMESPACE = 3;
        static final int INCR_KEY                = 1;
        static final int INCR_DELTA              = 2;
        static final int INCR_TTL_SECS_ON_CREATE = 3;
        static final int INCR_NAMESPACE          = 4;
        static final int SNX_CREATED   = 1;
        static final int SNX_VERSION   = 2;
        static final int CTR_VALUE     = 1;
        static final int CTR_VERSION   = 2;
        static final int AUTH_TOKEN    = 1;
        static final int VAL_KEY       = 1;
        static final int VAL_VALUE     = 2;
        static final int VAL_VERSION   = 3;
        static final int VR_VERSION    = 1;
        static final int ERR_CODE      = 1;
        static final int ERR_MESSAGE   = 2;
        static final int WE_KEY        = 1;
        static final int WE_VALUE      = 2;
        static final int WE_VERSION    = 3;
        static final int COUNT_FIELD   = 1;
        static final int OPT_VALUE     = 1;

        private Wire() {}

        // ── Writer ────────────────────────────────────────────────────────────

        static final class Writer {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

            void varint(long v) {
                if (v < 0) throw new IllegalArgumentException("negative varint");
                while ((v & ~0x7FL) != 0) {
                    buf.write((int) ((v & 0x7F) | 0x80));
                    v >>>= 7;
                }
                buf.write((int) v);
            }

            void tag(int field, int wire) { varint(((long) field << 3) | wire); }

            void uint64Field(int field, long value) {
                if (value == 0) return;
                tag(field, WT_VARINT); varint(value);
            }

            /**
             * Encode a proto {@code int64} field. Always emitted (even for 0, since
             * INCR delta is explicit-presence and 0 is meaningful); negatives use a
             * 10-byte two's-complement varint (NOT zigzag), via an unsigned shift.
             */
            void int64Field(int field, long value) {
                tag(field, WT_VARINT);
                long v = value;
                while ((v & ~0x7FL) != 0) {
                    buf.write((int) ((v & 0x7F) | 0x80));
                    v >>>= 7;
                }
                buf.write((int) v);
            }

            void enumField(int field, int value) {
                if (value == 0) return;
                tag(field, WT_VARINT); varint(value);
            }

            /** Length-delimited; emit only when payload non-empty. */
            void ldField(int field, byte[] payload) {
                if (payload.length == 0) return;
                tag(field, WT_LD);
                varint(payload.length);
                buf.writeBytes(payload);
            }

            /** Always emit a length-delimited field — used for oneof presence. */
            void ldFieldAlways(int field, byte[] payload) {
                tag(field, WT_LD);
                varint(payload.length);
                if (payload.length > 0) buf.writeBytes(payload);
            }

            void stringField(int field, String value) {
                if (value == null || value.isEmpty()) return;
                byte[] raw = value.getBytes(StandardCharsets.UTF_8);
                tag(field, WT_LD); varint(raw.length); buf.writeBytes(raw);
            }

            void bytesField(int field, byte[] value) {
                if (value == null || value.length == 0) return;
                tag(field, WT_LD); varint(value.length); buf.writeBytes(value);
            }

            byte[] toByteArray() { return buf.toByteArray(); }
        }

        // ── Reader ────────────────────────────────────────────────────────────

        static final class Reader {
            private final byte[] buf;
            private int off;
            private final int end;

            Reader(byte[] buf) { this(buf, 0, buf.length); }
            Reader(byte[] buf, int off, int end) { this.buf = buf; this.off = off; this.end = end; }

            int remaining() { return end - off; }

            long readVarint() throws IOException {
                long result = 0;
                int shift = 0;
                while (off < end) {
                    int b = buf[off++] & 0xFF;
                    result |= ((long) (b & 0x7F)) << shift;
                    if ((b & 0x80) == 0) return result;
                    shift += 7;
                    if (shift > 70) throw new IOException("varint too long");
                }
                throw new IOException("truncated varint");
            }

            int readVarintAsInt() throws IOException {
                long v = readVarint();
                if (v > Integer.MAX_VALUE || v < 0) throw new IOException("varint out of int range: " + v);
                return (int) v;
            }

            int[] readTag() throws IOException {
                long t = readVarint();
                return new int[] { (int) (t >>> 3), (int) (t & 0x7) };
            }

            byte[] readLD() throws IOException {
                int len = readVarintAsInt();
                if (off + len > end) throw new IOException("truncated length-delimited field");
                byte[] out = new byte[len];
                System.arraycopy(buf, off, out, 0, len);
                off += len;
                return out;
            }

            void skip(int wire) throws IOException {
                switch (wire) {
                    case WT_VARINT -> readVarint();
                    case WT_LD     -> readLD();
                    case 1         -> off += 8;  // fixed64
                    case 5         -> off += 4;  // fixed32
                    default        -> throw new IOException("unsupported wire type: " + wire);
                }
            }
        }

        // ── Inner-message encoders ───────────────────────────────────────────

        private static boolean hasNamespace(String ns) {
            return ns != null && !ns.isBlank();
        }

        static byte[] encodeOptionalString(String value) {
            Writer w = new Writer();
            w.stringField(OPT_VALUE, value);
            return w.toByteArray();
        }

        static byte[] encodeOptionalUint64(long value) {
            Writer w = new Writer();
            w.uint64Field(OPT_VALUE, value);
            return w.toByteArray();
        }

        static byte[] encodeKeyNamespace(String key, String namespace) {
            Writer w = new Writer();
            w.stringField(KN_KEY, key);
            if (hasNamespace(namespace)) {
                w.ldField(KN_NAMESPACE, encodeOptionalString(namespace));
            }
            return w.toByteArray();
        }

        static byte[] encodePatternNamespace(String pattern, String namespace) {
            Writer w = new Writer();
            w.stringField(PN_PATTERN, pattern);
            if (hasNamespace(namespace)) {
                w.ldField(PN_NAMESPACE, encodeOptionalString(namespace));
            }
            return w.toByteArray();
        }

        static byte[] encodeSetRequest(String key, byte[] value, long ttlSecs, String namespace) {
            Writer w = new Writer();
            w.stringField(SR_KEY, key);
            w.bytesField(SR_VALUE, value);
            if (ttlSecs > 0) {
                w.ldField(SR_TTL_SECS, encodeOptionalUint64(ttlSecs));
            }
            if (hasNamespace(namespace)) {
                w.ldField(SR_NAMESPACE, encodeOptionalString(namespace));
            }
            return w.toByteArray();
        }

        static byte[] encodeIncrRequest(String key, long delta, long ttlSecsOnCreate, String namespace) {
            Writer w = new Writer();
            w.stringField(INCR_KEY, key);
            // Always emit delta so the server uses the caller's value verbatim
            // rather than its absent-default of 1.
            w.int64Field(INCR_DELTA, delta);
            if (ttlSecsOnCreate > 0) {
                w.ldField(INCR_TTL_SECS_ON_CREATE, encodeOptionalUint64(ttlSecsOnCreate));
            }
            if (hasNamespace(namespace)) {
                w.ldField(INCR_NAMESPACE, encodeOptionalString(namespace));
            }
            return w.toByteArray();
        }

        static byte[] encodeSetTtlByPatternRequest(String pattern, long ttlSecs, String namespace) {
            Writer w = new Writer();
            w.stringField(STBP_PATTERN, pattern);
            if (ttlSecs > 0) {
                w.ldField(STBP_TTL_SECS, encodeOptionalUint64(ttlSecs));
            }
            if (hasNamespace(namespace)) {
                w.ldField(STBP_NAMESPACE, encodeOptionalString(namespace));
            }
            return w.toByteArray();
        }

        static byte[] encodeAuthRequest(String token) {
            Writer w = new Writer();
            w.stringField(AUTH_TOKEN, token);
            return w.toByteArray();
        }

        // ── ClientRequest envelope wrapper ───────────────────────────────────

        static byte[] wrapClientRequest(int variantField, byte[] inner) {
            Writer reqWriter = new Writer();
            reqWriter.ldFieldAlways(variantField, inner);
            byte[] requestBytes = reqWriter.toByteArray();

            Writer envWriter = new Writer();
            envWriter.enumField(ENV_VERSION, PROTOCOL_VERSION);
            envWriter.ldFieldAlways(ENV_CLIENT_REQUEST, requestBytes);
            byte[] envelope = envWriter.toByteArray();

            byte[] out = new byte[4 + envelope.length];
            out[0] = (byte) ((envelope.length >>> 24) & 0xFF);
            out[1] = (byte) ((envelope.length >>> 16) & 0xFF);
            out[2] = (byte) ((envelope.length >>> 8)  & 0xFF);
            out[3] = (byte) (envelope.length & 0xFF);
            System.arraycopy(envelope, 0, out, 4, envelope.length);
            return out;
        }

        // ── Decoder ──────────────────────────────────────────────────────────

        static Response decodeResponse(byte[] payload) throws IOException {
            Reader env = new Reader(payload);
            byte[] responseBytes = null;
            long version = 0;
            while (env.remaining() > 0) {
                int[] t = env.readTag();
                int field = t[0], wire = t[1];
                if (field == ENV_VERSION && wire == WT_VARINT) {
                    version = env.readVarint();
                } else if (field == ENV_CLIENT_RESPONSE && wire == WT_LD) {
                    responseBytes = env.readLD();
                } else {
                    env.skip(wire);
                }
            }
            if (version != 0 && version != PROTOCOL_VERSION) {
                throw new IOException("unsupported protocol version: " + version);
            }
            if (responseBytes == null) {
                throw new IOException("Envelope is missing client_response payload");
            }

            Reader r = new Reader(responseBytes);
            while (r.remaining() > 0) {
                int[] t = r.readTag();
                int field = t[0], wire = t[1];
                if (wire != WT_LD) { r.skip(wire); continue; }
                byte[] inner = r.readLD();
                Response out = new Response();
                switch (field) {
                    case RESP_VALUE -> { decodeValue(inner, out);   out.type = ResponseType.VALUE;        return out; }
                    case RESP_OK -> {    decodeOk(inner, out);      out.type = ResponseType.OK;           return out; }
                    case RESP_DELETED -> {                          out.type = ResponseType.DELETED;      return out; }
                    case RESP_NOT_FOUND -> {                        out.type = ResponseType.NOT_FOUND;    return out; }
                    case RESP_PONG -> {                             out.type = ResponseType.PONG;         return out; }
                    case RESP_AUTH_OK -> {                          out.type = ResponseType.AUTH_OK;      return out; }
                    case RESP_ERROR -> { decodeError(inner, out);   out.type = ResponseType.ERROR;        return out; }
                    case RESP_WATCHING -> {                         out.type = ResponseType.WATCHING;     return out; }
                    case RESP_UNWATCHED -> {                        out.type = ResponseType.UNWATCHED;    return out; }
                    case RESP_WATCH_EVENT -> { decodeWatchEvent(inner, out); out.type = ResponseType.WATCH_EVENT; return out; }
                    case RESP_PATTERN_DELETED -> {     out.count = decodeCount(inner); out.type = ResponseType.PATTERN_DELETED;     return out; }
                    case RESP_PATTERN_TTL_UPDATED -> { out.count = decodeCount(inner); out.type = ResponseType.PATTERN_TTL_UPDATED; return out; }
                    case RESP_SET_NX -> { decodeSetNx(inner, out);   out.type = ResponseType.SET_NX;  return out; }
                    case RESP_COUNTER -> { decodeCounter(inner, out); out.type = ResponseType.COUNTER; return out; }
                    default -> {} // unknown variant — keep scanning
                }
            }
            throw new IOException("ClientResponse oneof has no active field");
        }

        private static void decodeValue(byte[] buf, Response out) throws IOException {
            Reader r = new Reader(buf);
            while (r.remaining() > 0) {
                int[] t = r.readTag();
                int field = t[0], wire = t[1];
                if (field == VAL_KEY && wire == WT_LD)        out.key     = new String(r.readLD(), StandardCharsets.UTF_8);
                else if (field == VAL_VALUE && wire == WT_LD) out.value   = r.readLD();
                else if (field == VAL_VERSION && wire == WT_VARINT) out.version = r.readVarint();
                else r.skip(wire);
            }
            if (out.value == null) out.value = new byte[0];
        }

        private static void decodeOk(byte[] buf, Response out) throws IOException {
            Reader r = new Reader(buf);
            while (r.remaining() > 0) {
                int[] t = r.readTag();
                int field = t[0], wire = t[1];
                if (field == VR_VERSION && wire == WT_VARINT) out.version = r.readVarint();
                else r.skip(wire);
            }
        }

        private static void decodeSetNx(byte[] buf, Response out) throws IOException {
            Reader r = new Reader(buf);
            while (r.remaining() > 0) {
                int[] t = r.readTag();
                int field = t[0], wire = t[1];
                if (field == SNX_CREATED && wire == WT_VARINT)      out.created = r.readVarint() != 0;
                else if (field == SNX_VERSION && wire == WT_VARINT) out.version = r.readVarint();
                else r.skip(wire);
            }
        }

        private static void decodeCounter(byte[] buf, Response out) throws IOException {
            Reader r = new Reader(buf);
            while (r.remaining() > 0) {
                int[] t = r.readTag();
                int field = t[0], wire = t[1];
                // proto int64 read as a Java long is already the correct signed value
                // (a -1 arrives as a 10-byte two's-complement varint == 0xFFFF..FF).
                if (field == CTR_VALUE && wire == WT_VARINT)        out.counterValue = r.readVarint();
                else if (field == CTR_VERSION && wire == WT_VARINT) out.version = r.readVarint();
                else r.skip(wire);
            }
        }

        private static void decodeError(byte[] buf, Response out) throws IOException {
            Reader r = new Reader(buf);
            int codeIdx = 0;
            String message = "";
            while (r.remaining() > 0) {
                int[] t = r.readTag();
                int field = t[0], wire = t[1];
                if (field == ERR_CODE && wire == WT_VARINT)        codeIdx = r.readVarintAsInt();
                else if (field == ERR_MESSAGE && wire == WT_LD)    message = new String(r.readLD(), StandardCharsets.UTF_8);
                else r.skip(wire);
            }
            out.errorCode = DittoErrorCode.fromIndex(codeIdx);
            out.message = message;
        }

        private static void decodeWatchEvent(byte[] buf, Response out) throws IOException {
            Reader r = new Reader(buf);
            while (r.remaining() > 0) {
                int[] t = r.readTag();
                int field = t[0], wire = t[1];
                if (field == WE_KEY && wire == WT_LD)        out.key = new String(r.readLD(), StandardCharsets.UTF_8);
                else if (field == WE_VALUE && wire == WT_LD) {
                    out.value = decodeOptionalBytes(r.readLD());
                    out.hasValue = true;
                } else if (field == WE_VERSION && wire == WT_VARINT) out.version = r.readVarint();
                else r.skip(wire);
            }
        }

        private static byte[] decodeOptionalBytes(byte[] buf) throws IOException {
            Reader r = new Reader(buf);
            byte[] out = new byte[0];
            while (r.remaining() > 0) {
                int[] t = r.readTag();
                int field = t[0], wire = t[1];
                if (field == OPT_VALUE && wire == WT_LD) out = r.readLD();
                else r.skip(wire);
            }
            return out;
        }

        private static long decodeCount(byte[] buf) throws IOException {
            Reader r = new Reader(buf);
            long count = 0;
            while (r.remaining() > 0) {
                int[] t = r.readTag();
                int field = t[0], wire = t[1];
                if (field == COUNT_FIELD && wire == WT_VARINT) count = r.readVarint();
                else r.skip(wire);
            }
            return count;
        }
    }
}
