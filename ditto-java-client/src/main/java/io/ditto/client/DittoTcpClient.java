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

    private final String host;
    private final int    port;
    private final String authToken;

    private Socket           socket;
    private DataInputStream  in;
    private OutputStream     out;

    // ── Constructors ──────────────────────────────────────────────────────────

    public DittoTcpClient() {
        this("localhost", 7777, null);
    }

    public DittoTcpClient(String host, int port) {
        this(host, port, null);
    }

    public DittoTcpClient(String host, int port, String authToken) {
        this.host = host;
        this.port = port;
        this.authToken = authToken;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Open the TCP connection. Must be called before any other method. */
    public synchronized void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
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
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /** Send a Ping and return {@code true} when Pong is received. */
    public synchronized boolean ping() throws IOException {
        sendFrame(encodePing());
        return readResponse().type == ResponseType.PONG;
    }

    /**
     * Get a key. Returns {@code null} when the key does not exist or has expired.
     */
    public synchronized DittoGetResult get(String key) throws IOException {
        sendFrame(encodeGet(key));
        Response resp = readResponse();
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
        sendFrame(encodeSet(key, value, ttlSecs));
        Response resp = readResponse();
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
        sendFrame(encodeDelete(key));
        Response resp = readResponse();
        return switch (resp.type) {
            case DELETED   -> true;
            case NOT_FOUND -> false;
            case ERROR     -> throw new DittoException(resp.errorCode, resp.message);
            default        -> throw new IOException("Unexpected response: " + resp.type);
        };
    }

    // ── Bincode encoding ──────────────────────────────────────────────────────

    /** Variant 0: Get { key } */
    private byte[] encodeGet(String key) {
        byte[]     kb  = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + kb.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0);
        buf.putLong(kb.length);
        buf.put(kb);
        return frame(buf.array());
    }

    /** Variant 1: Set { key, value, ttl_secs } */
    private byte[] encodeSet(String key, byte[] value, long ttlSecs) {
        byte[]     kb     = key.getBytes(StandardCharsets.UTF_8);
        boolean    hasTtl = ttlSecs > 0;
        int        size   = 4 + 8 + kb.length + 8 + value.length + 1 + (hasTtl ? 8 : 0);
        ByteBuffer buf    = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(1);
        buf.putLong(kb.length);
        buf.put(kb);
        buf.putLong(value.length);
        buf.put(value);
        buf.put(hasTtl ? (byte) 1 : (byte) 0);
        if (hasTtl) buf.putLong(ttlSecs);
        return frame(buf.array());
    }

    /** Variant 2: Delete { key } */
    private byte[] encodeDelete(String key) {
        byte[]     kb  = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + kb.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(2);
        buf.putLong(kb.length);
        buf.put(kb);
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

    private Response readResponse() throws IOException {
        // 4-byte big-endian length prefix
        int payloadLen = in.readInt();
        byte[] payload = new byte[payloadLen];
        in.readFully(payload);
        return decodeResponse(payload);
    }

    // ── Bincode decoding ──────────────────────────────────────────────────────

    private enum ResponseType { VALUE, OK, DELETED, NOT_FOUND, PONG, AUTH_OK, ERROR }

    private static final class Response {
        ResponseType   type;
        byte[]         value;
        long           version;
        DittoErrorCode errorCode;
        String         message;
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
            default -> throw new IOException("Unknown ClientResponse variant: " + variant);
        }
        return r;
    }
}
