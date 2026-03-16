package io.nodeloom.sdk.event;

/**
 * Represents the response from the NodeLoom batch ingestion endpoint.
 */
public final class BatchResponse {

    private final int statusCode;
    private final String body;
    private final boolean success;

    public BatchResponse(int statusCode, String body) {
        this.statusCode = statusCode;
        this.body = body;
        this.success = statusCode >= 200 && statusCode < 300;
    }

    /** HTTP status code returned by the server. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Raw response body. */
    public String getBody() {
        return body;
    }

    /** True if the server returned a 2xx status. */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Determines whether the request should be retried based on the status code.
     * Retryable conditions: 429 (rate limited), 5xx (server errors).
     */
    public boolean isRetryable() {
        return statusCode == 429 || statusCode >= 500;
    }
}
