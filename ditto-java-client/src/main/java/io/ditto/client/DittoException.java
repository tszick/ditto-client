package io.ditto.client;

/** Thrown when the dittod server returns an error response. */
public class DittoException extends RuntimeException {

    private final DittoErrorCode code;

    public DittoException(DittoErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /** The error code that identifies the failure type. */
    public DittoErrorCode getCode() {
        return code;
    }
}
