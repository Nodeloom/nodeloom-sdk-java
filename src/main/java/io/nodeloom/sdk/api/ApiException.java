package io.nodeloom.sdk.api;

/**
 * Thrown when a NodeLoom API request returns a non-2xx status code.
 */
public class ApiException extends Exception {

    private final int statusCode;
    private final String responseBody;

    public ApiException(int statusCode, String responseBody) {
        super("API error " + statusCode + ": " + (responseBody != null && responseBody.length() > 1024 ? responseBody.substring(0, 1024) + "...[truncated]" : responseBody));
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /** HTTP status code of the failed response. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Response body (usually JSON). */
    public String getResponseBody() {
        return responseBody;
    }
}
