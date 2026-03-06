package com.nodeloom.sdk;

/**
 * Defines the type of work a span represents.
 */
public enum SpanType {

    /** A call to a large language model. */
    LLM("llm"),

    /** A tool or function invocation. */
    TOOL("tool"),

    /** A retrieval operation (e.g., vector search). */
    RETRIEVAL("retrieval"),

    /** A sub-agent invocation. */
    AGENT("agent"),

    /** A chain or pipeline of steps. */
    CHAIN("chain"),

    /** A custom or unclassified span. */
    CUSTOM("custom");

    private final String value;

    SpanType(String value) {
        this.value = value;
    }

    /**
     * Returns the wire-format value used in JSON serialization.
     */
    public String getValue() {
        return value;
    }
}
