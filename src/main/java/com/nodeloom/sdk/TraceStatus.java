package com.nodeloom.sdk;

/**
 * Represents the final status of a trace.
 */
public enum TraceStatus {

    SUCCESS("success"),
    ERROR("error");

    private final String value;

    TraceStatus(String value) {
        this.value = value;
    }

    /**
     * Returns the wire-format value used in JSON serialization.
     */
    public String getValue() {
        return value;
    }
}
