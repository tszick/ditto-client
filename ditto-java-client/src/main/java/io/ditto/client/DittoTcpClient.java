package io.ditto.client;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * DittoTcpClient – connects directly to dittod TCP port 7777.
 *
 * <p>Uses the bincode 1.x binary protocol over a persistent TCP connection.
 * All public methods are {@code synchronized}, so concurrent calls from multiple
 * threads are safely serialized over the single socket.
 *
 * <p>Bincode 1.x wire rules (matches Rust defaults):
 * <ul>
 *   <li>Integers: little-endian</li>
 *   <li>Enum variant: {@code u32 LE} (declaration order index)</li>
 *   <li>String/Bytes: {@code u64 LE} length prefix + raw bytes</li>
 *   <li>Option&lt;T&gt;: {@code u8} (0 = None, 1 = Some) + T if Some</li>
 * </ul>
 * Wire framing: 4-byte big-endian length prefix before each bincode payload.
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
            int connectTimeoutMs,
            int readTimeoutMs
    ) {
        this.host = host;
        this.port = port;
        this.authToken = authToken;
        this.strictMode = strictMode;
        this.autoReconnect = autoReconnect;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Open the TCP connection. Must be called before any other method. */
    public synchronized void connect() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), connectTimeoutMs);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(readTimeoutMs);
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

    // ── Bincode encoding ──────────────────────────────────────────────────────

    /** Variant 0: Get { key } */
    private byte[] encodeGet(String key, String namespace) {
        byte[]     kb  = key.getBytes(StandardCharsets.UTF_8);
        byte[]     ns  = namespaceBytes(namespace);
        int        nsSize = ns == null ? 1 : 1 + 8 + ns.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + kb.length + nsSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0);
        buf.putLong(kb.length);
        buf.put(kb);
        putOptionalString(buf, ns);
        return frame(buf.array());
    }

    /** Variant 1: Set { key, value, ttl_secs } */
    private byte[] encodeSet(String key, byte[] value, long ttlSecs, String namespace) {
        byte[]     kb     = key.getBytes(StandardCharsets.UTF_8);
        byte[]     ns     = namespaceBytes(namespace);
        boolean    hasTtl = ttlSecs > 0;
        int        nsSize = ns == null ? 1 : 1 + 8 + ns.length;
        int        size   = 4 + 8 + kb.length + 8 + value.length + 1 + (hasTtl ? 8 : 0) + nsSize;
        ByteBuffer buf    = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(1);
        buf.putLong(kb.length);
        buf.put(kb);
        buf.putLong(value.length);
        buf.put(value);
        buf.put(hasTtl ? (byte) 1 : (byte) 0);
        if (hasTtl) buf.putLong(ttlSecs);
        putOptionalString(buf, ns);
        return frame(buf.array());
    }

    /** Variant 2: Delete { key } */
    private byte[] encodeDelete(String key, String namespace) {
        byte[]     kb  = key.getBytes(StandardCharsets.UTF_8);
        byte[]     ns  = namespaceBytes(namespace);
        int        nsSize = ns == null ? 1 : 1 + 8 + ns.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + kb.length + nsSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(2);
        buf.putLong(kb.length);
        buf.put(kb);
        putOptionalString(buf, ns);
        return frame(buf.array());
    }

    /** Variant 3: Ping */
    private byte[] encodePing() {
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(3);
        return frame(buf.array());
    }

    /** Variant 4: Auth { token } */
    private byte[] encodeAuth(String token) {
        byte[]     tb  = token.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + tb.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(4);
        buf.putLong(tb.length);
        buf.put(tb);
        return frame(buf.array());
    }

    /** Variant 5: Watch { key } */
    private byte[] encodeWatch(String key, String namespace) {
        byte[]     kb  = key.getBytes(StandardCharsets.UTF_8);
        byte[]     ns  = namespaceBytes(namespace);
        int        nsSize = ns == null ? 1 : 1 + 8 + ns.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + kb.length + nsSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(5);
        buf.putLong(kb.length);
        buf.put(kb);
        putOptionalString(buf, ns);
        return frame(buf.array());
    }

    /** Variant 6: Unwatch { key } */
    private byte[] encodeUnwatch(String key, String namespace) {
        byte[]     kb  = key.getBytes(StandardCharsets.UTF_8);
        byte[]     ns  = namespaceBytes(namespace);
        int        nsSize = ns == null ? 1 : 1 + 8 + ns.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + kb.length + nsSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(6);
        buf.putLong(kb.length);
        buf.put(kb);
        putOptionalString(buf, ns);
        return frame(buf.array());
    }

    /** Variant 7: DeleteByPattern { pattern } */
    private byte[] encodeDeleteByPattern(String pattern, String namespace) {
        byte[]     pb  = pattern.getBytes(StandardCharsets.UTF_8);
        byte[]     ns  = namespaceBytes(namespace);
        int        nsSize = ns == null ? 1 : 1 + 8 + ns.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + pb.length + nsSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(7);
        buf.putLong(pb.length);
        buf.put(pb);
        putOptionalString(buf, ns);
        return frame(buf.array());
    }

    /** Variant 8: SetTtlByPattern { pattern, ttl_secs } */
    private byte[] encodeSetTtlByPattern(String pattern, long ttlSecs, String namespace) {
        byte[]  pb     = pattern.getBytes(StandardCharsets.UTF_8);
        byte[]  ns     = namespaceBytes(namespace);
        boolean hasTtl = ttlSecs > 0;
        int     nsSize = ns == null ? 1 : 1 + 8 + ns.length;
        int     size   = 4 + 8 + pb.length + 1 + (hasTtl ? 8 : 0) + nsSize;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(8);
        buf.putLong(pb.length);
        buf.put(pb);
        buf.put(hasTtl ? (byte) 1 : (byte) 0);
        if (hasTtl) buf.putLong(ttlSecs);
        putOptionalString(buf, ns);
        return frame(buf.array());
    }

    private static byte[] namespaceBytes(String namespace) {
        if (namespace == null || namespace.isBlank()) return null;
        return namespace.getBytes(StandardCharsets.UTF_8);
    }

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

    private static void putOptionalString(ByteBuffer buf, byte[] value) {
        if (value == null) {
            buf.put((byte) 0);
            return;
        }
        buf.put((byte) 1);
        buf.putLong(value.length);
        buf.put(value);
    }

    /** Prepend a 4-byte big-endian length prefix to the payload. */
    private static byte[] frame(byte[] payload) {
        byte[] result = new byte[4 + payload.length];
        ByteBuffer.wrap(result).putInt(payload.length); // big-endian by default
        System.arraycopy(payload, 0, result, 4, payload.length);
        return result;
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
        return decodeResponse(payload);
    }

    // ── Bincode decoding ──────────────────────────────────────────────────────

    private enum ResponseType {
        VALUE, OK, DELETED, NOT_FOUND, PONG, AUTH_OK, ERROR, WATCHING, UNWATCHED, WATCH_EVENT, PATTERN_DELETED, PATTERN_TTL_UPDATED
    }

    private static final class Response {
        ResponseType   type;
        String         key;
        byte[]         value;
        boolean        hasValue;
        long           version;
        DittoErrorCode errorCode;
        String         message;
        long           count;
    }

    private static Response decodeResponse(byte[] payload) throws IOException {
        ByteBuffer buf  = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int        variant = buf.getInt();
        Response   r    = new Response();

        switch (variant) {
            case 0 -> { // Value { key, value, version }
                long keyLen = buf.getLong();
                buf.position(buf.position() + (int) keyLen); // skip key (already known)
                long valLen = buf.getLong();
                r.value = new byte[(int) valLen];
                buf.get(r.value);
                r.version = buf.getLong();
                r.type = ResponseType.VALUE;
            }
            case 1 -> { // Ok { version }
                r.version = buf.getLong();
                r.type = ResponseType.OK;
            }
            case 2 -> r.type = ResponseType.DELETED;
            case 3 -> r.type = ResponseType.NOT_FOUND;
            case 4 -> r.type = ResponseType.PONG;
            case 5 -> r.type = ResponseType.AUTH_OK;
            case 6 -> { // Error { code, message }
                int    codeIdx  = buf.getInt();
                long   msgLen   = buf.getLong();
                byte[] msgBytes = new byte[(int) msgLen];
                buf.get(msgBytes);
                r.errorCode = DittoErrorCode.fromIndex(codeIdx);
                r.message   = new String(msgBytes, StandardCharsets.UTF_8);
                r.type      = ResponseType.ERROR;
            }
            case 7 -> r.type = ResponseType.WATCHING;
            case 8 -> r.type = ResponseType.UNWATCHED;
            case 9 -> { // WatchEvent { key, value: Option<Bytes>, version }
                long keyLen = buf.getLong();
                byte[] keyBytes = new byte[(int) keyLen];
                buf.get(keyBytes);
                r.key = new String(keyBytes, StandardCharsets.UTF_8);
                byte hasValue = buf.get();
                r.hasValue = hasValue == 1;
                if (r.hasValue) {
                    long valLen = buf.getLong();
                    r.value = new byte[(int) valLen];
                    buf.get(r.value);
                }
                r.version = buf.getLong();
                r.type = ResponseType.WATCH_EVENT;
            }
            case 10 -> { // PatternDeleted { deleted }
                r.count = buf.getLong();
                r.type = ResponseType.PATTERN_DELETED;
            }
            case 11 -> { // PatternTtlUpdated { updated }
                r.count = buf.getLong();
                r.type = ResponseType.PATTERN_TTL_UPDATED;
            }
            default -> throw new IOException("Unknown ClientResponse variant: " + variant);
        }
        return r;
    }
}
