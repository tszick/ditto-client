package io.ditto.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

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
    private final DittoTcpSocketFactory socketFactory;
    private final DittoTcpRequestFactory requestFactory;
    private final DittoTcpResponseReader responseReader;

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
        this.tlsCaCert = DittoClientValidators.normalizeOptionalText(tlsCaCert);
        this.tlsServerName = DittoClientValidators.normalizeOptionalText(tlsServerName);
        this.socketFactory = new DittoTcpSocketFactory();
        this.requestFactory = new DittoTcpRequestFactory();
        this.responseReader = new DittoTcpResponseReader(DEFAULT_MAX_FRAME_BYTES);
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
            sendFrame(requestFactory.encodeAuth(authToken));
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
        return sendAndRead(requestFactory.encodePing()).type == ResponseType.PONG;
    }

    /**
     * Get a key. Returns {@code null} when the key does not exist or has expired.
     */
    public synchronized DittoGetResult get(String key) throws IOException {
        return get(key, null);
    }

    public synchronized DittoGetResult get(String key, String namespace) throws IOException {
        validateCoreInputs("get", key, namespace);
        Response resp = sendAndRead(requestFactory.encodeGet(key, namespace));
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
        Response resp = sendAndRead(requestFactory.encodeSet(key, value, ttlSecs, namespace));
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
        try {
            validateCoreInputs("set", key, namespace);
            Response resp = sendAndRead(requestFactory.encodeSetNx(key, value, ttlSecs, namespace));
            return switch (resp.type) {
                case SET_NX -> new DittoSetNxResult(resp.created, resp.version);
                case ERROR  -> throw new DittoException(resp.errorCode, resp.message);
                default     -> throw new IOException("Unexpected response: " + resp.type);
            };
        } catch (DittoException e) {
            throw e;
        } catch (IOException e) {
            throw DittoAtomicErrorNormalizer.normalizeTcpAtomicError(e, "SET_NX");
        }
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
        try {
            validateCoreInputs("set", key, namespace);
            Response resp = sendAndRead(requestFactory.encodeIncr(key, delta, ttlSecsOnCreate, namespace));
            return switch (resp.type) {
                case COUNTER -> new DittoCounterResult(resp.counterValue, resp.version);
                case ERROR   -> throw new DittoException(resp.errorCode, resp.message);
                default      -> throw new IOException("Unexpected response: " + resp.type);
            };
        } catch (DittoException e) {
            throw e;
        } catch (IOException e) {
            throw DittoAtomicErrorNormalizer.normalizeTcpAtomicError(e, "INCR");
        }
    }

    /**
     * Delete a key. Returns {@code true} if the key existed, {@code false} if not found.
     */
    public synchronized boolean delete(String key) throws IOException {
        return delete(key, null);
    }

    public synchronized boolean delete(String key, String namespace) throws IOException {
        validateCoreInputs("delete", key, namespace);
        Response resp = sendAndRead(requestFactory.encodeDelete(key, namespace));
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
        Response resp = sendAndRead(requestFactory.encodeDeleteByPattern(pattern, namespace));
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
        Response resp = sendAndRead(requestFactory.encodeSetTtlByPattern(pattern, ttlSecs, namespace));
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
        Response resp = sendAndRead(requestFactory.encodeWatch(key, namespace));
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
        Response resp = sendAndRead(requestFactory.encodeUnwatch(key, namespace));
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

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateCoreInputs(String op, String key, String namespace) {
        DittoClientValidators.validateCoreInputs(strictMode, op, key, namespace);
    }

    private void validatePatternInputs(String op, String pattern, String namespace) {
        DittoClientValidators.validatePatternInputs(strictMode, op, pattern, namespace);
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
        return socketFactory.openSocket(
                host,
                port,
                connectTimeoutMs,
                readTimeoutMs,
                tlsEnabled,
                tlsCaCert,
                tlsServerName
        );
    }

    private Response readResponse() throws IOException {
        return responseReader.readResponse(in);
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

    }
}
