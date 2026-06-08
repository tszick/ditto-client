package io.ditto.client;

/**
 * Result returned by {@code setNx()}. {@code created} is {@code false} when the
 * key already existed (no write performed); {@code version} is the existing or
 * newly-assigned write version.
 */
public final class DittoSetNxResult {

    private final boolean created;
    private final long version;

    public DittoSetNxResult(boolean created, long version) {
        this.created = created;
        this.version = version;
    }

    /** True when this call created the key; false when it already existed. */
    public boolean isCreated() { return created; }

    /** The existing or newly-assigned write version for the key. */
    public long getVersion() { return version; }
}
