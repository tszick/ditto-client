package io.ditto.client;

/** Result returned by {@code set()}. */
public final class DittoSetResult {

    private final long version;

    public DittoSetResult(long version) {
        this.version = version;
    }

    /** Monotonically increasing write version assigned to this key. */
    public long getVersion() { return version; }
}
