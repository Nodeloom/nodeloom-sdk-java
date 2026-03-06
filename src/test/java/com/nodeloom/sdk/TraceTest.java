package com.nodeloom.sdk;

import com.nodeloom.sdk.event.TelemetryEvent;
import com.nodeloom.sdk.queue.TelemetryQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Trace and Span: lifecycle, event emission, span nesting,
 * token usage, and AutoCloseable behavior.
 */
class TraceTest {

    private TelemetryQueue queue;
    private NodeLoomConfig config;

    @BeforeEach
    void setUp() {
        queue = new TelemetryQueue(1000);
        config = new NodeLoomConfig(
                "sdk_test",
                "https://api.nodeloom.io",
                "test",
                "1.0.0",
                1000,
                100,
                5000,
                3,
                1000,
                10000
        );
    }

    // ---------------------------------------------------------------
    // Trace lifecycle tests
    // ---------------------------------------------------------------

    @Test
    void trace_start_emitsTraceStartEvent() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.input(Map.of("query", "hello world"));
        trace.start();

        assertEquals(1, queue.size());
        List<TelemetryEvent> events = queue.drain(10);
        TelemetryEvent event = events.get(0);

        assertEquals("trace_start", event.getFields().get("type"));
        assertEquals(trace.getTraceId(), event.getFields().get("trace_id"));
        assertEquals("test-agent", event.getFields().get("agent_name"));
        assertEquals("1.0.0", event.getFields().get("agent_version"));
        assertEquals("test", event.getFields().get("environment"));
        assertNotNull(event.getFields().get("timestamp"));

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) event.getFields().get("input");
        assertEquals("hello world", input.get("query"));
    }

    @Test
    void trace_end_emitsTraceEndEvent() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10); // clear the trace_start event

        trace.end(TraceStatus.SUCCESS, Map.of("result", "42"));

        assertEquals(1, queue.size());
        List<TelemetryEvent> events = queue.drain(10);
        TelemetryEvent event = events.get(0);

        assertEquals("trace_end", event.getFields().get("type"));
        assertEquals(trace.getTraceId(), event.getFields().get("trace_id"));
        assertEquals("success", event.getFields().get("status"));
        assertNotNull(event.getFields().get("timestamp"));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) event.getFields().get("output");
        assertEquals("42", output.get("result"));
    }

    @Test
    void trace_endWithErrorStatus_setsStatusCorrectly() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        trace.end(TraceStatus.ERROR, Map.of("error", "something failed"));

        List<TelemetryEvent> events = queue.drain(10);
        assertEquals("error", events.get(0).getFields().get("status"));
    }

    @Test
    void trace_endWithNoOutput_omitsOutputField() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        trace.end(TraceStatus.SUCCESS);

        List<TelemetryEvent> events = queue.drain(10);
        assertFalse(events.get(0).getFields().containsKey("output"));
    }

    @Test
    void trace_doubleEnd_isIdempotent() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        trace.end(TraceStatus.SUCCESS);
        int sizeAfterFirstEnd = queue.size();

        trace.end(TraceStatus.ERROR); // should have no effect

        assertEquals(sizeAfterFirstEnd, queue.size()); // second end() adds nothing
        assertEquals(1, sizeAfterFirstEnd); // first end() added exactly one trace_end
        assertTrue(trace.isEnded());
    }

    @Test
    void trace_doubleStart_throwsIllegalState() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();

        assertThrows(IllegalStateException.class, trace::start);
    }

    @Test
    void trace_endBeforeStart_throwsIllegalState() {
        Trace trace = new Trace("test-agent", queue, config);

        assertThrows(IllegalStateException.class, () -> {
            trace.end(TraceStatus.SUCCESS);
        });
    }

    @Test
    void trace_spanBeforeStart_throwsIllegalState() {
        Trace trace = new Trace("test-agent", queue, config);

        assertThrows(IllegalStateException.class, () -> {
            trace.span("test", SpanType.LLM);
        });
    }

    @Test
    void trace_spanAfterEnd_throwsIllegalState() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        trace.end(TraceStatus.SUCCESS);

        assertThrows(IllegalStateException.class, () -> {
            trace.span("test", SpanType.LLM);
        });
    }

    // ---------------------------------------------------------------
    // Span creation and lifecycle tests
    // ---------------------------------------------------------------

    @Test
    void span_createdFromTrace_hasCorrectFields() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span span = trace.span("openai-call", SpanType.LLM);
        assertNotNull(span.getSpanId());
        assertEquals(trace.getTraceId(), span.getTraceId());
        assertNull(span.getParentSpanId());
        assertEquals("openai-call", span.getName());
        assertEquals(SpanType.LLM, span.getSpanType());
        assertFalse(span.isEnded());
    }

    @Test
    void span_end_emitsSpanEvent() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span span = trace.span("openai-call", SpanType.LLM);
        span.setInput(Map.of("prompt", "hello"));
        span.setOutput(Map.of("response", "world"));
        span.setTokenUsage(150, 200, "gpt-4o");
        span.end();

        assertEquals(1, queue.size());
        List<TelemetryEvent> events = queue.drain(10);
        TelemetryEvent event = events.get(0);

        assertEquals("span", event.getFields().get("type"));
        assertEquals(trace.getTraceId(), event.getFields().get("trace_id"));
        assertEquals(span.getSpanId(), event.getFields().get("span_id"));
        assertNull(event.getFields().get("parent_span_id"));
        assertEquals("openai-call", event.getFields().get("name"));
        assertEquals("llm", event.getFields().get("span_type"));
        assertEquals("success", event.getFields().get("status"));
        assertNotNull(event.getFields().get("timestamp"));
        assertNotNull(event.getFields().get("end_timestamp"));

        @SuppressWarnings("unchecked")
        Map<String, Object> tokenUsage = (Map<String, Object>) event.getFields().get("token_usage");
        assertEquals(150, tokenUsage.get("prompt_tokens"));
        assertEquals(200, tokenUsage.get("completion_tokens"));
        assertEquals(350, tokenUsage.get("total_tokens"));
        assertEquals("gpt-4o", tokenUsage.get("model"));
    }

    @Test
    void span_withError_setsErrorStatus() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span span = trace.span("failing-call", SpanType.TOOL);
        span.setError("Connection timeout");
        span.end();

        List<TelemetryEvent> events = queue.drain(10);
        TelemetryEvent event = events.get(0);
        assertEquals("error", event.getFields().get("status"));
        assertEquals("Connection timeout", event.getFields().get("error"));
    }

    @Test
    void span_doubleEnd_isIdempotent() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span span = trace.span("test", SpanType.CUSTOM);
        span.end();
        int sizeAfterFirstEnd = queue.size();

        span.end(); // no-op
        assertEquals(sizeAfterFirstEnd, queue.size());
    }

    @Test
    void span_withMetadata_includesMetadataInEvent() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span span = trace.span("retrieval", SpanType.RETRIEVAL);
        span.setMetadata(Map.of("source", "pinecone", "top_k", 5));
        span.end();

        List<TelemetryEvent> events = queue.drain(10);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) events.get(0).getFields().get("metadata");
        assertEquals("pinecone", metadata.get("source"));
        assertEquals(5, metadata.get("top_k"));
    }

    // ---------------------------------------------------------------
    // Nested span tests
    // ---------------------------------------------------------------

    @Test
    void span_childSpan_hasCorrectParentId() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span parent = trace.span("agent-step", SpanType.AGENT);
        Span child = parent.span("llm-call", SpanType.LLM);

        assertEquals(parent.getSpanId(), child.getParentSpanId());
        assertEquals(trace.getTraceId(), child.getTraceId());

        child.end();
        parent.end();

        List<TelemetryEvent> events = queue.drain(10);
        assertEquals(2, events.size());
    }

    @Test
    void span_deepNesting_allSpansHaveCorrectParentChain() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span level1 = trace.span("step-1", SpanType.AGENT);
        Span level2 = level1.span("step-2", SpanType.TOOL);
        Span level3 = level2.span("step-3", SpanType.LLM);

        assertNull(level1.getParentSpanId());
        assertEquals(level1.getSpanId(), level2.getParentSpanId());
        assertEquals(level2.getSpanId(), level3.getParentSpanId());

        level3.end();
        level2.end();
        level1.end();

        List<TelemetryEvent> events = queue.drain(10);
        assertEquals(3, events.size());
    }

    // ---------------------------------------------------------------
    // AutoCloseable (try-with-resources) tests
    // ---------------------------------------------------------------

    @Test
    void trace_autoClose_endsTraceWithSuccess() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        trace.close(); // simulates try-with-resources exit

        assertTrue(trace.isEnded());
        List<TelemetryEvent> events = queue.drain(10);
        assertEquals(1, events.size());
        assertEquals("trace_end", events.get(0).getFields().get("type"));
        assertEquals("success", events.get(0).getFields().get("status"));
    }

    @Test
    void trace_autoClose_endsWithError_whenSetErrorCalled() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        trace.setError("agent crashed");
        trace.close();

        assertTrue(trace.isEnded());
        List<TelemetryEvent> events = queue.drain(10);
        assertEquals(1, events.size());
        assertEquals("trace_end", events.get(0).getFields().get("type"));
        assertEquals("error", events.get(0).getFields().get("status"));
        assertEquals("agent crashed", events.get(0).getFields().get("error"));
    }

    @Test
    void trace_end_includesErrorMessage() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        trace.end(TraceStatus.ERROR, Map.of("partial", true), "something failed");

        List<TelemetryEvent> events = queue.drain(10);
        assertEquals(1, events.size());
        assertEquals("error", events.get(0).getFields().get("status"));
        assertEquals("something failed", events.get(0).getFields().get("error"));
    }

    @Test
    void trace_autoClose_whenNotStarted_isNoOp() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.close(); // should not throw, should not emit events

        assertFalse(trace.isStarted());
        assertEquals(0, queue.size());
    }

    @Test
    void trace_autoClose_whenAlreadyEnded_isIdempotent() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        trace.end(TraceStatus.ERROR);
        queue.drain(100);

        trace.close(); // should not emit a second trace_end
        assertEquals(0, queue.size());
    }

    @Test
    void span_autoClose_endsSpanAutomatically() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span span = trace.span("auto-close-span", SpanType.CUSTOM);
        assertFalse(span.isEnded());

        span.close();
        assertTrue(span.isEnded());
        assertEquals(1, queue.size());
    }

    @Test
    void trace_endAutomaticallyEndsOpenSpans() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span span1 = trace.span("open-span-1", SpanType.LLM);
        Span span2 = trace.span("open-span-2", SpanType.TOOL);
        // Don't explicitly end spans

        trace.end(TraceStatus.SUCCESS);

        assertTrue(span1.isEnded());
        assertTrue(span2.isEnded());

        // 2 span events + 1 trace_end event
        List<TelemetryEvent> events = queue.drain(10);
        assertEquals(3, events.size());
    }

    @Test
    void parentSpan_end_automaticallyEndsChildSpans() {
        Trace trace = new Trace("test-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span parent = trace.span("parent", SpanType.AGENT);
        Span child = parent.span("child", SpanType.LLM);
        // Don't explicitly end child

        parent.end();

        assertTrue(child.isEnded());
        assertTrue(parent.isEnded());

        List<TelemetryEvent> events = queue.drain(10);
        assertEquals(2, events.size());
    }

    // ---------------------------------------------------------------
    // Span type tests
    // ---------------------------------------------------------------

    @Test
    void spanType_allValuesHaveCorrectWireFormat() {
        assertEquals("llm", SpanType.LLM.getValue());
        assertEquals("tool", SpanType.TOOL.getValue());
        assertEquals("retrieval", SpanType.RETRIEVAL.getValue());
        assertEquals("agent", SpanType.AGENT.getValue());
        assertEquals("chain", SpanType.CHAIN.getValue());
        assertEquals("custom", SpanType.CUSTOM.getValue());
    }

    // ---------------------------------------------------------------
    // JSON serialization tests
    // ---------------------------------------------------------------

    @Test
    void traceStartEvent_serializesToValidJson() {
        Trace trace = new Trace("json-agent", queue, config);
        trace.input(Map.of("query", "test \"quoted\" input"));
        trace.start();

        List<TelemetryEvent> events = queue.drain(10);
        String json = events.get(0).toJson();

        assertTrue(json.contains("\"type\":\"trace_start\""));
        assertTrue(json.contains("\"agent_name\":\"json-agent\""));
        assertTrue(json.contains("\"query\":\"test \\\"quoted\\\" input\""));
    }

    @Test
    void spanEvent_withTokenUsage_serializesToValidJson() {
        Trace trace = new Trace("json-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span span = trace.span("llm", SpanType.LLM);
        span.setTokenUsage(150, 200, "gpt-4o");
        span.end();

        List<TelemetryEvent> events = queue.drain(10);
        String json = events.get(0).toJson();

        assertTrue(json.contains("\"prompt_tokens\":150"));
        assertTrue(json.contains("\"completion_tokens\":200"));
        assertTrue(json.contains("\"total_tokens\":350"));
        assertTrue(json.contains("\"model\":\"gpt-4o\""));
    }

    @Test
    void spanEvent_withNullParent_serializesParentAsNull() {
        Trace trace = new Trace("json-agent", queue, config);
        trace.start();
        queue.drain(10);

        Span span = trace.span("root-span", SpanType.CUSTOM);
        span.end();

        List<TelemetryEvent> events = queue.drain(10);
        String json = events.get(0).toJson();

        assertTrue(json.contains("\"parent_span_id\":null"));
    }
}
