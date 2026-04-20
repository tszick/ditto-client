package io.ditto.client;

/** Thrown when the dittod server returns an error response. */
public class DittoException extends RuntimeException {

    private final DittoErrorCode code;
    private final String rawCode;

    public DittoException(DittoErrorCode code, String message) {
        this(code, message, code.name());
    }

    public DittoException(DittoErrorCode code, String message, String rawCode) {
        super(message);
        this.code = code;
        this.rawCode = rawCode;
    }

    /** The error code that identifies the failure type. */
    public DittoErrorCode getCode() {
        return code;
    }

    /** Raw server error code string (preserved for forward-compatible unknown codes). */
    public String getRawCode() {
        return rawCode;
    }
}
