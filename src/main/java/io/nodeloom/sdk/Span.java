package io.nodeloom.sdk;

import io.nodeloom.sdk.event.TelemetryEvent;
import io.nodeloom.sdk.queue.TelemetryQueue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a unit of work within a trace (e.g., an LLM call, tool invocation,
 * or retrieval operation).
 *
 * <p>Spans are created via {@link Trace#span(String, SpanType)} and must be
 * ended by calling {@link #end()} or by using try-with-resources. Forgetting
 * to end a span will result in it being ended automatically (with an error
 * status) when {@link #close()} is called.</p>
 *
 * <p>Spans are NOT thread-safe. Each span should be used by a single thread.</p>
 */
public final class Span implements AutoCloseable {

    private final String spanId;
    private final String traceId;
    private final String parentSpanId;
    private final String name;
    private final SpanType spanType;
    private final TelemetryQueue queue;
    private final List<Span> childSpans = new ArrayList<>();
    private final String startTimestamp;

    private Map<String, Object> input;
    private Map<String, Object> output;
    private Map<String, Object> metadata;
    private Map<String, Object> tokenUsage;
    private String promptTemplate;
    private Integer promptVersion;
    private String status = "success";
    private String errorMessage;
    private boolean ended = false;

    Span(String traceId, String parentSpanId, String name, SpanType spanType, TelemetryQueue queue) {
        this.spanId = UUID.randomUUID().toString();
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.name = name;
        this.spanType = spanType;
        this.queue = queue;
        this.startTimestamp = java.time.format.DateTimeFormatter.ISO_INSTANT
                .format(java.time.Instant.now());
    }

    /** Returns the unique identifier for this span. */
    public String getSpanId() {
        return spanId;
    }

    /** Returns the trace ID that this span belongs to. */
    public String getTraceId() {
        return traceId;
    }

    /** Returns the parent span ID, or null if this is a root-level span. */
    public String getParentSpanId() {
        return parentSpanId;
    }

    /** Returns the name of this span. */
    public String getName() {
        return name;
    }

    /** Returns the type of this span. */
    public SpanType getSpanType() {
        return spanType;
    }

    /**
     * Sets the input data for this span.
     *
     * @param input a map representing the input (will be serialized as JSON)
     * @return this span for chaining
     */
    public Span setInput(Map<String, Object> input) {
        this.input = input;
        return this;
    }

    /**
     * Sets the output data for this span.
     *
     * @param output a map representing the output (will be serialized as JSON)
     * @return this span for chaining
     */
    public Span setOutput(Map<String, Object> output) {
        this.output = output;
        return this;
    }

    /**
     * Sets arbitrary metadata on this span.
     *
     * @param metadata a map of key-value pairs
     * @return this span for chaining
     */
    public Span setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }

    /**
     * Records token usage for LLM spans.
     *
     * @param promptTokens     number of tokens in the prompt
     * @param completionTokens number of tokens in the completion
     * @param model            the model identifier (e.g., "gpt-4o")
     * @return this span for chaining
     */
    public Span setTokenUsage(int promptTokens, int completionTokens, String model) {
        this.tokenUsage = new LinkedHashMap<>();
        this.tokenUsage.put("prompt_tokens", promptTokens);
        this.tokenUsage.put("completion_tokens", completionTokens);
        this.tokenUsage.put("total_tokens", promptTokens + completionTokens);
        if (model != null) {
            this.tokenUsage.put("model", model);
        }
        return this;
    }

    /**
     * Marks this span as having an error.
     *
     * @param message a human-readable error description
     * @return this span for chaining
     */
    public Span setError(String message) {
        this.status = "error";
        this.errorMessage = message;
        return this;
    }

    /**
     * Records which prompt template and version was used.
     */
    public Span setPrompt(String template, int version) {
        this.promptTemplate = template;
        this.promptVersion = version;
        return this;
    }

    /**
     * Emit a custom metric tied to this span's trace.
     */
    public void metric(String name, double value, String unit, Map<String, String> tags) {
        TelemetryEvent event = new TelemetryEvent()
                .put("type", "metric")
                .put("trace_id", traceId)
                .put("metric_name", name)
                .put("metric_value", value);
        if (unit != null) event.put("metric_unit", unit);
        if (tags != null) event.put("metric_tags", tags);
        event.putTimestampNow("timestamp");
        queue.offer(event);
    }

    /**
     * Creates a child span nested under this span.
     *
     * @param name     the child span name
     * @param spanType the type of work the child span represents
     * @return the new child span
     */
    public Span span(String name, SpanType spanType) {
        Span child = new Span(traceId, this.spanId, name, spanType, queue);
        childSpans.add(child);
        return child;
    }

    /**
     * Ends the span, recording the end timestamp and enqueuing the
     * telemetry event. Calling end() more than once has no effect.
     */
    public void end() {
        if (ended) {
            return;
        }
        ended = true;

        // End any child spans that were not explicitly ended
        for (Span child : childSpans) {
            if (!child.ended) {
                child.end();
            }
        }

        TelemetryEvent event = new TelemetryEvent()
                .put("type", "span")
                .put("trace_id", traceId)
                .put("span_id", spanId)
                .put("parent_span_id", parentSpanId)
                .put("name", name)
                .put("span_type", spanType.getValue())
                .put("status", status);

        if (input != null) {
            event.put("input", input);
        }
        if (output != null) {
            event.put("output", output);
        }
        if (metadata != null) {
            event.put("metadata", metadata);
        }
        if (tokenUsage != null) {
            event.put("token_usage", tokenUsage);
        }
        if (errorMessage != null) {
            event.put("error", errorMessage);
        }
        if (promptTemplate != null) {
            event.put("prompt_template", promptTemplate);
        }
        if (promptVersion != null) {
            event.put("prompt_version", promptVersion);
        }

        event.put("timestamp", startTimestamp);
        event.putTimestampNow("end_timestamp");

        queue.offer(event);
    }

    /**
     * Returns true if this span has been ended.
     */
    public boolean isEnded() {
        return ended;
    }

    /**
     * AutoCloseable implementation. Ends the span if it has not been ended yet.
     */
    @Override
    public void close() {
        if (!ended) {
            end();
        }
    }
}
