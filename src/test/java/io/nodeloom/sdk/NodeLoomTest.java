package io.nodeloom.sdk;

import io.nodeloom.sdk.batch.BatchProcessor;
import io.nodeloom.sdk.queue.TelemetryQueue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the NodeLoom client: builder validation, initialization,
 * trace creation, and shutdown behavior.
 */
class NodeLoomTest {

    // ---------------------------------------------------------------
    // Builder tests
    // ---------------------------------------------------------------

    @Test
    void builder_withRequiredFields_createsClient() {
        try (NodeLoom client = NodeLoom.builder()
                .apiKey("sdk_test_key")
                .build()) {
            assertNotNull(client);
            assertFalse(client.isClosed());
        }
    }

    @Test
    void builder_withAllFields_createsClientWithCorrectConfig() {
        try (NodeLoom client = NodeLoom.builder()
                .apiKey("sdk_full_key")
                .endpoint("https://custom.nodeloom.io")
                .environment("staging")
                .agentVersion("2.0.0")
                .maxQueueSize(5000)
                .maxBatchSize(50)
                .flushIntervalMs(2000)
                .maxRetries(5)
                .baseRetryDelayMs(500)
                .httpTimeoutMs(15000)
                .build()) {

            NodeLoomConfig config = client.getConfig();
            assertEquals("sdk_full_key", config.getApiKey());
            assertEquals("https://custom.nodeloom.io", config.getEndpoint());
            assertEquals("staging", config.getEnvironment());
            assertEquals("2.0.0", config.getAgentVersion());
            assertEquals(5000, config.getMaxQueueSize());
            assertEquals(50, config.getMaxBatchSize());
            assertEquals(2000, config.getFlushIntervalMs());
            assertEquals(5, config.getMaxRetries());
            assertEquals(500, config.getBaseRetryDelayMs());
            assertEquals(15000, config.getHttpTimeoutMs());
        }
    }

    @Test
    void builder_withDefaultValues_usesCorrectDefaults() {
        try (NodeLoom client = NodeLoom.builder()
                .apiKey("sdk_default_test")
                .build()) {

            NodeLoomConfig config = client.getConfig();
            assertEquals("https://api.nodeloom.io", config.getEndpoint());
            assertEquals("production", config.getEnvironment());
            assertNull(config.getAgentVersion());
            assertEquals(10_000, config.getMaxQueueSize());
            assertEquals(100, config.getMaxBatchSize());
            assertEquals(5_000, config.getFlushIntervalMs());
            assertEquals(3, config.getMaxRetries());
            assertEquals(1_000, config.getBaseRetryDelayMs());
            assertEquals(10_000, config.getHttpTimeoutMs());
        }
    }

    @Test
    void builder_withNullApiKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> {
            NodeLoom.builder().build();
        });
    }

    @Test
    void builder_withBlankApiKey_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> {
            NodeLoom.builder().apiKey("   ").build();
        });
    }

    // ---------------------------------------------------------------
    // Initialization and lifecycle tests
    // ---------------------------------------------------------------

    @Test
    void client_afterClose_isClosed() {
        NodeLoom client = NodeLoom.builder()
                .apiKey("sdk_lifecycle_test")
                .build();
        assertFalse(client.isClosed());

        client.close();
        assertTrue(client.isClosed());
    }

    @Test
    void client_closeIsIdempotent() {
        NodeLoom client = NodeLoom.builder()
                .apiKey("sdk_idempotent_test")
                .build();

        client.close();
        client.close(); // should not throw
        assertTrue(client.isClosed());
    }

    @Test
    void client_afterClose_traceThrowsIllegalState() {
        NodeLoom client = NodeLoom.builder()
                .apiKey("sdk_closed_trace")
                .build();
        client.close();

        assertThrows(IllegalStateException.class, () -> {
            client.trace("my-agent");
        });
    }

    // ---------------------------------------------------------------
    // Trace creation tests
    // ---------------------------------------------------------------

    @Test
    void trace_returnsNewTraceWithAgentName() {
        try (NodeLoom client = NodeLoom.builder()
                .apiKey("sdk_trace_test")
                .build()) {

            Trace trace = client.trace("test-agent");
            assertNotNull(trace);
            assertEquals("test-agent", trace.getAgentName());
            assertNotNull(trace.getTraceId());
            assertFalse(trace.isStarted());
            assertFalse(trace.isEnded());
        }
    }

    @Test
    void trace_eachCallReturnsUniqueTraceId() {
        try (NodeLoom client = NodeLoom.builder()
                .apiKey("sdk_unique_trace")
                .build()) {

            Trace trace1 = client.trace("agent-1");
            Trace trace2 = client.trace("agent-2");
            assertNotEquals(trace1.getTraceId(), trace2.getTraceId());

            // Clean up: start and end both traces
            trace1.start().end(TraceStatus.SUCCESS);
            trace2.start().end(TraceStatus.SUCCESS);
        }
    }

    // ---------------------------------------------------------------
    // Try-with-resources tests
    // ---------------------------------------------------------------

    @Test
    void tryWithResources_closesClientAutomatically() {
        NodeLoom client;
        try (NodeLoom c = NodeLoom.builder().apiKey("sdk_twr").build()) {
            client = c;
            assertFalse(client.isClosed());
        }
        assertTrue(client.isClosed());
    }

    @Test
    void tryWithResources_fullPipelineDoesNotThrow() {
        assertDoesNotThrow(() -> {
            try (NodeLoom client = NodeLoom.builder().apiKey("sdk_pipeline").build()) {
                try (Trace trace = client.trace("agent").input(Map.of("q", "hello")).start()) {
                    try (Span span = trace.span("llm-call", SpanType.LLM)) {
                        span.setInput(Map.of("prompt", "hello"));
                        span.setOutput(Map.of("response", "world"));
                        span.setTokenUsage(10, 20, "gpt-4o");
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------
    // Flush tests
    // ---------------------------------------------------------------

    @Test
    void flush_onClosedClient_doesNotThrow() {
        NodeLoom client = NodeLoom.builder()
                .apiKey("sdk_flush_closed")
                .build();
        client.close();
        assertDoesNotThrow(client::flush);
    }

    @Test
    void flush_withQueuedEvents_doesNotThrow() {
        try (NodeLoom client = NodeLoom.builder().apiKey("sdk_flush").build()) {
            Trace trace = client.trace("agent").start();
            trace.end(TraceStatus.SUCCESS);
            assertDoesNotThrow(client::flush);
        }
    }
}
