package io.ditto.client;

/** Result returned by {@code setTtlByPattern()}. */
public final class DittoSetTtlByPatternResult {

    private final long updated;

    public DittoSetTtlByPatternResult(long updated) {
        this.updated = updated;
    }

    /** Number of keys whose TTL was updated by the pattern operation. */
    public long getUpdated() { return updated; }
}
