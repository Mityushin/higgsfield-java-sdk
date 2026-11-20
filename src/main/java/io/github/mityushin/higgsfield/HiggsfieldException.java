package io.github.mityushin.higgsfield;

/** A failed Higgsfield operation. The response body may contain provider details. */
public class HiggsfieldException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;
    private final String correlationId;
    public HiggsfieldException(String message, int statusCode, String responseBody) {
        this(message, statusCode, responseBody, null);
    }
    public HiggsfieldException(String message, int statusCode, String responseBody, String correlationId) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.correlationId = correlationId;
    }
    public HiggsfieldException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
        this.correlationId = null;
    }
    public int statusCode() { return statusCode; }
    public String responseBody() { return responseBody; }
    public String correlationId() { return correlationId; }
}
