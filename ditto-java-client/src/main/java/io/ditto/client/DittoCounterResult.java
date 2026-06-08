package io.ditto.client;

/**
 * Result returned by {@code incr()}. {@code value} is the post-increment
 * counter value (signed int64); {@code version} is the new write version.
 */
public final class DittoCounterResult {

    private final long value;
    private final long version;

    public DittoCounterResult(long value, long version) {
        this.value = value;
        this.version = version;
    }

    /** The counter value after applying the increment. */
    public long getValue() { return value; }

    /** Monotonically increasing write version assigned to this key. */
    public long getVersion() { return version; }
}
