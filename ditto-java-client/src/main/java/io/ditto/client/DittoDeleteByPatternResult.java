package io.ditto.client;

/** Result returned by {@code deleteByPattern()}. */
public final class DittoDeleteByPatternResult {

    private final long deleted;

    public DittoDeleteByPatternResult(long deleted) {
        this.deleted = deleted;
    }

    /** Number of keys deleted by the pattern operation. */
    public long getDeleted() { return deleted; }
}
