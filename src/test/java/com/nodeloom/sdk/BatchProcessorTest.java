package com.nodeloom.sdk;

import com.nodeloom.sdk.batch.BatchProcessor;
import com.nodeloom.sdk.event.BatchRequest;
import com.nodeloom.sdk.event.BatchResponse;
import com.nodeloom.sdk.event.TelemetryEvent;
import com.nodeloom.sdk.queue.TelemetryQueue;
import com.nodeloom.sdk.transport.HttpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BatchProcessor: batching behavior, flush on shutdown,
 * retry logic, and queue interaction.
 */
class BatchProcessorTest {

    private TelemetryQueue queue;
    private NodeLoomConfig config;

    @BeforeEach
    void setUp() {
        queue = new TelemetryQueue(1000);
        config = new NodeLoomConfig(
                "sdk_test",
                "https://api.nodeloom.io",
                "test",
                null,
                1000,   // maxQueueSize
                10,     // maxBatchSize (small for testing)
                5000,   // flushIntervalMs
                3,      // maxRetries
                10,     // baseRetryDelayMs (short for testing)
                10000   // httpTimeoutMs
        );
    }

    // ---------------------------------------------------------------
    // Batching behavior tests
    // ---------------------------------------------------------------

    @Test
    void processQueue_drainsBatchesOfCorrectSize() {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        // Enqueue 25 events with maxBatchSize=10
        for (int i = 0; i < 25; i++) {
            queue.offer(createEvent("event_" + i));
        }

        processor.processQueue();

        // Should send 3 batches: 10, 10, 5
        assertEquals(3, transport.batches.size());
        assertEquals(10, transport.batches.get(0).size());
        assertEquals(10, transport.batches.get(1).size());
        assertEquals(5, transport.batches.get(2).size());
    }

    @Test
    void processQueue_emptyQueue_sendsNoBatches() {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        processor.processQueue();

        assertEquals(0, transport.batches.size());
    }

    @Test
    void processQueue_singleEvent_sendsSingleBatch() {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        queue.offer(createEvent("single"));
        processor.processQueue();

        assertEquals(1, transport.batches.size());
        assertEquals(1, transport.batches.get(0).size());
    }

    // ---------------------------------------------------------------
    // Flush on shutdown tests
    // ---------------------------------------------------------------

    @Test
    void shutdown_flushesRemainingEvents() {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);
        processor.start(60_000); // long interval so timer won't fire

        for (int i = 0; i < 5; i++) {
            queue.offer(createEvent("pending_" + i));
        }

        processor.shutdown(5000);

        assertFalse(processor.isRunning());
        assertTrue(transport.batches.size() >= 1);

        int totalEvents = transport.batches.stream().mapToInt(BatchRequest::size).sum();
        assertEquals(5, totalEvents);
    }

    @Test
    void shutdown_whenAlreadyStopped_isIdempotent() {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);
        processor.start(5000);

        processor.shutdown(1000);
        processor.shutdown(1000); // should not throw

        assertFalse(processor.isRunning());
    }

    // ---------------------------------------------------------------
    // Start / running state tests
    // ---------------------------------------------------------------

    @Test
    void start_setsRunningToTrue() {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        assertFalse(processor.isRunning());
        processor.start(5000);
        assertTrue(processor.isRunning());

        processor.shutdown(1000);
        assertFalse(processor.isRunning());
    }

    @Test
    void start_calledTwice_isIdempotent() {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        processor.start(5000);
        processor.start(5000); // should not throw or create a second timer
        assertTrue(processor.isRunning());

        processor.shutdown(1000);
    }

    // ---------------------------------------------------------------
    // Periodic flush tests
    // ---------------------------------------------------------------

    @Test
    void periodicFlush_eventuallyDrainsQueue() throws InterruptedException {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);
        processor.start(100); // 100ms flush interval

        queue.offer(createEvent("periodic_1"));
        queue.offer(createEvent("periodic_2"));

        // Wait for at least one flush cycle
        Thread.sleep(500);

        processor.shutdown(2000);

        int totalEvents = transport.batches.stream().mapToInt(BatchRequest::size).sum();
        assertEquals(2, totalEvents);
    }

    // ---------------------------------------------------------------
    // Retry logic tests
    // ---------------------------------------------------------------

    @Test
    void retry_onRetryableError_retriesUpToMaxRetries() {
        // Transport that always returns 500
        FailingTransport transport = new FailingTransport(500);
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        queue.offer(createEvent("retry_test"));
        processor.processQueue();

        // Initial attempt + 3 retries = 4 total attempts
        assertEquals(4, transport.attemptCount.get());
    }

    @Test
    void retry_on429_retriesUpToMaxRetries() {
        FailingTransport transport = new FailingTransport(429);
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        queue.offer(createEvent("rate_limit_test"));
        processor.processQueue();

        assertEquals(4, transport.attemptCount.get());
    }

    @Test
    void retry_onNonRetryableError_doesNotRetry() {
        FailingTransport transport = new FailingTransport(400);
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        queue.offer(createEvent("bad_request_test"));
        processor.processQueue();

        // Only 1 attempt, no retries for 400
        assertEquals(1, transport.attemptCount.get());
    }

    @Test
    void retry_on401_doesNotRetry() {
        FailingTransport transport = new FailingTransport(401);
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        queue.offer(createEvent("auth_failure"));
        processor.processQueue();

        assertEquals(1, transport.attemptCount.get());
    }

    @Test
    void retry_eventualSuccess_stopsRetrying() {
        // Fails twice with 500, then succeeds on the third attempt
        EventuallySuccessTransport transport = new EventuallySuccessTransport(2);
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        queue.offer(createEvent("eventual_success"));
        processor.processQueue();

        assertEquals(3, transport.attemptCount.get());
        assertTrue(transport.lastSuccess);
    }

    @Test
    void retry_onException_retriesUpToMaxRetries() {
        ExceptionTransport transport = new ExceptionTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        queue.offer(createEvent("exception_test"));
        processor.processQueue();

        // Initial attempt + 3 retries = 4 total
        assertEquals(4, transport.attemptCount.get());
    }

    // ---------------------------------------------------------------
    // Batch request serialization tests
    // ---------------------------------------------------------------

    @Test
    void batchRequest_containsSdkVersionAndLanguage() {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        queue.offer(createEvent("sdk_meta_test"));
        processor.processQueue();

        String json = transport.batches.get(0).toJson();
        assertTrue(json.contains("\"sdk_version\":\"0.1.0\""));
        assertTrue(json.contains("\"sdk_language\":\"java\""));
    }

    @Test
    void batchRequest_serializesAllEvents() {
        RecordingTransport transport = new RecordingTransport();
        BatchProcessor processor = new BatchProcessor(queue, transport, config);

        queue.offer(createEvent("event_a"));
        queue.offer(createEvent("event_b"));
        processor.processQueue();

        String json = transport.batches.get(0).toJson();
        assertTrue(json.contains("\"event_a\""));
        assertTrue(json.contains("\"event_b\""));
    }

    // ---------------------------------------------------------------
    // Queue interaction tests
    // ---------------------------------------------------------------

    @Test
    void queue_boundedCapacity_dropsEventsWhenFull() {
        TelemetryQueue smallQueue = new TelemetryQueue(5);

        for (int i = 0; i < 10; i++) {
            smallQueue.offer(createEvent("overflow_" + i));
        }

        assertEquals(5, smallQueue.size());
        assertEquals(5, smallQueue.getDroppedCount());
    }

    @Test
    void queue_drain_returnsUpToMaxElements() {
        for (int i = 0; i < 20; i++) {
            queue.offer(createEvent("drain_" + i));
        }

        List<TelemetryEvent> batch = queue.drain(7);
        assertEquals(7, batch.size());
        assertEquals(13, queue.size());
    }

    @Test
    void queue_drain_emptyQueue_returnsEmptyList() {
        List<TelemetryEvent> batch = queue.drain(10);
        assertTrue(batch.isEmpty());
    }

    @Test
    void queue_offerNull_returnsFalse() {
        assertFalse(queue.offer(null));
        assertEquals(0, queue.size());
    }

    @Test
    void queue_invalidMaxSize_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryQueue(-1));
    }

    // ---------------------------------------------------------------
    // BatchResponse tests
    // ---------------------------------------------------------------

    @Test
    void batchResponse_2xx_isSuccess() {
        assertTrue(new BatchResponse(200, "ok").isSuccess());
        assertTrue(new BatchResponse(201, "created").isSuccess());
        assertTrue(new BatchResponse(204, "").isSuccess());
    }

    @Test
    void batchResponse_4xx_isNotSuccess() {
        assertFalse(new BatchResponse(400, "bad request").isSuccess());
        assertFalse(new BatchResponse(401, "unauthorized").isSuccess());
        assertFalse(new BatchResponse(429, "rate limited").isSuccess());
    }

    @Test
    void batchResponse_retryableStatusCodes() {
        assertTrue(new BatchResponse(429, "").isRetryable());
        assertTrue(new BatchResponse(500, "").isRetryable());
        assertTrue(new BatchResponse(502, "").isRetryable());
        assertTrue(new BatchResponse(503, "").isRetryable());

        assertFalse(new BatchResponse(200, "").isRetryable());
        assertFalse(new BatchResponse(400, "").isRetryable());
        assertFalse(new BatchResponse(401, "").isRetryable());
        assertFalse(new BatchResponse(403, "").isRetryable());
        assertFalse(new BatchResponse(404, "").isRetryable());
    }

    // ---------------------------------------------------------------
    // JSON writer edge cases
    // ---------------------------------------------------------------

    @Test
    void jsonWriter_handlesSpecialCharacters() {
        TelemetryEvent event = new TelemetryEvent()
                .put("text", "line1\nline2\ttab")
                .put("quote", "say \"hello\"")
                .put("backslash", "path\\to\\file");

        String json = event.toJson();
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\t"));
        assertTrue(json.contains("\\\"hello\\\""));
        assertTrue(json.contains("\\\\"));
    }

    @Test
    void jsonWriter_handlesNullValues() {
        TelemetryEvent event = new TelemetryEvent()
                .put("present", "yes")
                .put("absent", null);

        String json = event.toJson();
        assertTrue(json.contains("\"present\":\"yes\""));
        assertTrue(json.contains("\"absent\":null"));
    }

    @Test
    void jsonWriter_handlesBooleans() {
        TelemetryEvent event = new TelemetryEvent()
                .put("flag_true", true)
                .put("flag_false", false);

        String json = event.toJson();
        assertTrue(json.contains("\"flag_true\":true"));
        assertTrue(json.contains("\"flag_false\":false"));
    }

    @Test
    void jsonWriter_handlesNestedMaps() {
        TelemetryEvent event = new TelemetryEvent()
                .put("outer", Map.of("inner", Map.of("deep", "value")));

        String json = event.toJson();
        assertTrue(json.contains("\"outer\":{\"inner\":{\"deep\":\"value\"}}"));
    }

    @Test
    void jsonWriter_handlesNumbers() {
        TelemetryEvent event = new TelemetryEvent()
                .put("integer", 42)
                .put("long_val", 1234567890123L)
                .put("double_val", 3.14);

        String json = event.toJson();
        assertTrue(json.contains("\"integer\":42"));
        assertTrue(json.contains("\"long_val\":1234567890123"));
        assertTrue(json.contains("\"double_val\":3.14"));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private TelemetryEvent createEvent(String name) {
        return new TelemetryEvent()
                .put("type", "test")
                .put("name", name)
                .putTimestampNow("timestamp");
    }

    /**
     * A transport that records all batches sent to it and always returns 200.
     */
    private static class RecordingTransport extends HttpTransport {
        final CopyOnWriteArrayList<BatchRequest> batches = new CopyOnWriteArrayList<>();

        RecordingTransport() {
            super(new NodeLoomConfig("test", "https://localhost", "test", null,
                    1000, 100, 5000, 3, 1000, 10000));
        }

        @Override
        public BatchResponse send(BatchRequest batch) {
            batches.add(batch);
            return new BatchResponse(200, "{\"status\":\"ok\"}");
        }
    }

    /**
     * A transport that always returns the given error status code.
     */
    private static class FailingTransport extends HttpTransport {
        final AtomicInteger attemptCount = new AtomicInteger(0);
        private final int statusCode;

        FailingTransport(int statusCode) {
            super(new NodeLoomConfig("test", "https://localhost", "test", null,
                    1000, 100, 5000, 3, 10, 10000));
            this.statusCode = statusCode;
        }

        @Override
        public BatchResponse send(BatchRequest batch) {
            attemptCount.incrementAndGet();
            return new BatchResponse(statusCode, "error");
        }
    }

    /**
     * A transport that fails N times, then succeeds.
     */
    private static class EventuallySuccessTransport extends HttpTransport {
        final AtomicInteger attemptCount = new AtomicInteger(0);
        volatile boolean lastSuccess = false;
        private final int failCount;

        EventuallySuccessTransport(int failCount) {
            super(new NodeLoomConfig("test", "https://localhost", "test", null,
                    1000, 100, 5000, 3, 10, 10000));
            this.failCount = failCount;
        }

        @Override
        public BatchResponse send(BatchRequest batch) {
            int attempt = attemptCount.incrementAndGet();
            if (attempt <= failCount) {
                return new BatchResponse(500, "error");
            }
            lastSuccess = true;
            return new BatchResponse(200, "{\"status\":\"ok\"}");
        }
    }

    /**
     * A transport that always throws an exception.
     */
    private static class ExceptionTransport extends HttpTransport {
        final AtomicInteger attemptCount = new AtomicInteger(0);

        ExceptionTransport() {
            super(new NodeLoomConfig("test", "https://localhost", "test", null,
                    1000, 100, 5000, 3, 10, 10000));
        }

        @Override
        public BatchResponse send(BatchRequest batch) throws Exception {
            attemptCount.incrementAndGet();
            throw new java.io.IOException("Connection refused");
        }
    }
}
