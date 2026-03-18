package io.ditto.client;

/** Error codes returned by the dittod server. */
public enum DittoErrorCode {
    NODE_INACTIVE,
    NO_QUORUM,
    KEY_NOT_FOUND,
    INTERNAL_ERROR,
    WRITE_TIMEOUT,
    VALUE_TOO_LARGE,
    KEY_LIMIT_REACHED,
    AUTH_FAILED;

    private static final DittoErrorCode[] VALUES = values();

    /** Decode from a bincode variant index (declaration order). */
    public static DittoErrorCode fromIndex(int index) {
        if (index >= 0 && index < VALUES.length) return VALUES[index];
        return INTERNAL_ERROR;
    }
}
