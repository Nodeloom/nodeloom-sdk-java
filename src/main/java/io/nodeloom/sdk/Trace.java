package io.nodeloom.sdk;

import io.nodeloom.sdk.event.TelemetryEvent;
import io.nodeloom.sdk.queue.TelemetryQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a complete trace (one end-to-end agent execution).
 *
 * <p>A trace is created via {@link NodeLoom#trace(String)} and must be
 * started with {@link #start()} before spans can be added. The trace must
 * be ended by calling {@link #end(TraceStatus, Map)} or by using
 * try-with-resources.</p>
 *
 * <p>Traces are NOT thread-safe. Each trace should be used by a single thread.</p>
 */
public final class Trace implements AutoCloseable {

    private final String traceId;
    private final String agentName;
    private final TelemetryQueue queue;
    private final NodeLoomConfig config;
    private final List<Span> spans = new ArrayList<>();

    private Map<String, Object> input;
    private Map<String, Object> output;
    private String errorMessage;
    private String sessionId;
    private TraceStatus finalStatus;
    private boolean started = false;
    private boolean ended = false;

    Trace(String agentName, TelemetryQueue queue, NodeLoomConfig config) {
        this.traceId = UUID.randomUUID().toString();
        this.agentName = agentName;
        this.queue = queue;
        this.config = config;
    }

    /** Returns the unique identifier for this trace. */
    public String getTraceId() {
        return traceId;
    }

    /** Returns the agent name associated with this trace. */
    public String getAgentName() {
        return agentName;
    }

    /**
     * Sets the input data for the trace.
     *
     * @param input a map representing the input to the agent
     * @return this trace for chaining (builder-style before start)
     */
    public Trace input(Map<String, Object> input) {
        this.input = input;
        return this;
    }

    /**
     * Sets the session ID for conversation tracking.
     */
    public Trace sessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /**
     * Starts the trace, emitting a trace_start event.
     *
     * @return this trace for use in try-with-resources or further span creation
     * @throws IllegalStateException if the trace has already been started
     */
    public Trace start() {
        if (started) {
            throw new IllegalStateException("Trace has already been started");
        }
        started = true;

        TelemetryEvent event = new TelemetryEvent()
                .put("type", "trace_start")
                .put("trace_id", traceId)
                .put("agent_name", agentName);

        if (config.getAgentVersion() != null) {
            event.put("agent_version", config.getAgentVersion());
        }
        if (config.getEnvironment() != null) {
            event.put("environment", config.getEnvironment());
        }
        if (input != null) {
            event.put("input", input);
        }
        if (sessionId != null) {
            event.put("session_id", sessionId);
        }
        event.putTimestampNow("timestamp");

        queue.offer(event);
        return this;
    }

    /**
     * Submit feedback for this trace.
     */
    public void feedback(int rating, String comment) {
        TelemetryEvent event = new TelemetryEvent()
                .put("type", "feedback")
                .put("trace_id", traceId)
                .put("rating", rating);
        if (comment != null) {
            event.put("comment", comment);
        }
        event.putTimestampNow("timestamp");
        queue.offer(event);
    }

    /**
     * Creates a new top-level span within this trace.
     *
     * @param name     the span name
     * @param spanType the type of work this span represents
     * @return the new span
     * @throws IllegalStateException if the trace has not been started or has already ended
     */
    public Span span(String name, SpanType spanType) {
        if (!started) {
            throw new IllegalStateException("Trace has not been started. Call start() first.");
        }
        if (ended) {
            throw new IllegalStateException("Cannot create spans on an ended trace");
        }
        Span span = new Span(traceId, null, name, spanType, queue);
        spans.add(span);
        return span;
    }

    /**
     * Ends the trace with the given status and output, emitting a trace_end event.
     *
     * @param status the final status of the trace
     * @param output the output data produced by the agent (may be null)
     * @throws IllegalStateException if the trace has not been started
     */
    public void end(TraceStatus status, Map<String, Object> output) {
        end(status, output, null);
    }

    /**
     * Ends the trace with the given status, output, and error message.
     *
     * @param status       the final status of the trace
     * @param output       the output data produced by the agent (may be null)
     * @param errorMessage the error message if the trace failed (may be null)
     * @throws IllegalStateException if the trace has not been started
     */
    public void end(TraceStatus status, Map<String, Object> output, String errorMessage) {
        if (!started) {
            throw new IllegalStateException("Trace has not been started. Call start() first.");
        }
        if (ended) {
            return;
        }
        ended = true;
        this.finalStatus = status;
        this.output = output;
        this.errorMessage = errorMessage;

        // End any spans that were not explicitly ended
        for (Span span : spans) {
            if (!span.isEnded()) {
                span.end();
            }
        }

        TelemetryEvent event = new TelemetryEvent()
                .put("type", "trace_end")
                .put("trace_id", traceId)
                .put("status", status.getValue());

        if (output != null) {
            event.put("output", output);
        }
        if (errorMessage != null) {
            event.put("error", errorMessage);
        }
        event.putTimestampNow("timestamp");

        queue.offer(event);
    }

    /**
     * Convenience overload that ends the trace with no output.
     *
     * @param status the final status of the trace
     */
    public void end(TraceStatus status) {
        end(status, null, null);
    }

    /** Returns true if this trace has been started. */
    public boolean isStarted() {
        return started;
    }

    /** Returns true if this trace has been ended. */
    public boolean isEnded() {
        return ended;
    }

    /**
     * Sets the error message for this trace. This is used to record an error
     * that can be included when the trace ends. Call this before close() or
     * end() to ensure the error is captured.
     *
     * @param errorMessage the error message
     */
    public void setError(String errorMessage) {
        this.errorMessage = errorMessage;
        this.finalStatus = TraceStatus.ERROR;
    }

    /**
     * AutoCloseable implementation. Ends the trace if it was started but not
     * yet ended. If setError() was called, the trace ends with ERROR status;
     * otherwise it ends with SUCCESS.
     */
    @Override
    public void close() {
        if (started && !ended) {
            TraceStatus status = finalStatus != null ? finalStatus : TraceStatus.SUCCESS;
            end(status, output, errorMessage);
        }
    }
}
