package io.ditto.client;

import java.nio.charset.StandardCharsets;

/** Result returned by {@code get()}. */
public final class DittoGetResult {

    private final byte[] value;
    private final long   version;

    public DittoGetResult(byte[] value, long version) {
        this.value   = value;
        this.version = version;
    }

    /** Raw value bytes as stored on the server. */
    public byte[] getValue() { return value; }

    /** Monotonically increasing write version for this key. */
    public long getVersion() { return version; }

    /** Convenience: decode the value bytes as a UTF-8 string. */
    public String getValueAsString() {
        return new String(value, StandardCharsets.UTF_8);
    }
}
