package io.nodeloom.sdk;

import io.nodeloom.sdk.batch.BatchProcessor;
import io.nodeloom.sdk.queue.TelemetryQueue;
import io.nodeloom.sdk.transport.HttpTransport;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * The main entry point for the NodeLoom Java SDK.
 *
 * <p>Use the {@link #builder()} method to configure and create a client instance.
 * The client manages an internal queue, a background batch processor, and an HTTP
 * transport layer. All telemetry collection is fire-and-forget, meaning it will
 * never throw exceptions or block the calling thread under normal operation.</p>
 *
 * <p>The client is thread-safe and should be shared across threads. Create a single
 * instance at application startup and close it during shutdown.</p>
 *
 * <h3>Usage example:</h3>
 * <pre>{@code
 * try (NodeLoom client = NodeLoom.builder()
 *         .apiKey("sdk_...")
 *         .endpoint("https://api.nodeloom.io")
 *         .build()) {
 *
 *     try (Trace trace = client.trace("my-agent").start()) {
 *         try (Span span = trace.span("llm-call", SpanType.LLM)) {
 *             span.setInput(Map.of("prompt", "Hello"));
 *             span.setOutput(Map.of("response", "Hi there!"));
 *             span.setTokenUsage(10, 20, "gpt-4o");
 *         }
 *     }
 * }
 * }</pre>
 */
public final class NodeLoom implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(NodeLoom.class.getName());

    private static final String DEFAULT_ENDPOINT = "https://api.nodeloom.io";
    private static final String DEFAULT_ENVIRONMENT = "production";
    private static final int DEFAULT_MAX_QUEUE_SIZE = 10_000;
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;
    private static final long DEFAULT_FLUSH_INTERVAL_MS = 5_000;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_BASE_RETRY_DELAY_MS = 1_000;
    private static final long DEFAULT_HTTP_TIMEOUT_MS = 10_000;
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000;

    private final NodeLoomConfig config;
    private final TelemetryQueue queue;
    private final BatchProcessor batchProcessor;
    private volatile boolean closed = false;

    private NodeLoom(NodeLoomConfig config) {
        this.config = config;
        this.queue = new TelemetryQueue(config.getMaxQueueSize());

        HttpTransport transport = new HttpTransport(config);
        this.batchProcessor = new BatchProcessor(queue, transport, config);
        this.batchProcessor.start(config.getFlushIntervalMs());

        logger.fine("NodeLoom SDK initialized (endpoint=" + config.getEndpoint()
                + ", environment=" + config.getEnvironment() + ")");
    }

    /**
     * Package-private constructor for testing, allowing injection of a custom
     * BatchProcessor.
     */
    NodeLoom(NodeLoomConfig config, TelemetryQueue queue, BatchProcessor batchProcessor) {
        this.config = config;
        this.queue = queue;
        this.batchProcessor = batchProcessor;
    }

    /**
     * Creates a new builder for configuring and constructing a NodeLoom client.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new trace for the given agent name.
     *
     * <p>The returned trace must be started by calling {@link Trace#start()}
     * before spans can be created. Input can be set before or after starting.</p>
     *
     * @param agentName the name of the agent being traced
     * @return a new trace (not yet started)
     * @throws IllegalStateException if the client has been closed
     */
    public Trace trace(String agentName) {
        if (closed) {
            throw new IllegalStateException("NodeLoom client has been closed");
        }
        return new Trace(agentName, queue, config);
    }

    /**
     * Forces an immediate flush of any queued events. This is useful for
     * testing or when you want to ensure events are sent before a specific
     * point in your application.
     */
    public void flush() {
        if (!closed) {
            batchProcessor.processQueue();
        }
    }

    /**
     * Returns the configuration used by this client.
     */
    public NodeLoomConfig getConfig() {
        return config;
    }

    /**
     * Returns true if the client has been closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Shuts down the client, flushing remaining events and releasing resources.
     * Blocks until all pending events are sent or the shutdown timeout expires.
     *
     * <p>After calling close(), any new calls to {@link #trace(String)} will
     * throw {@link IllegalStateException}.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        logger.fine("Shutting down NodeLoom SDK");
        batchProcessor.shutdown(SHUTDOWN_TIMEOUT_MS);
    }

    /**
     * Builder for constructing a {@link NodeLoom} client with custom configuration.
     */
    public static final class Builder {

        private String apiKey;
        private String endpoint = DEFAULT_ENDPOINT;
        private String environment = DEFAULT_ENVIRONMENT;
        private String agentVersion;
        private int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
        private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
        private long flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private long baseRetryDelayMs = DEFAULT_BASE_RETRY_DELAY_MS;
        private long httpTimeoutMs = DEFAULT_HTTP_TIMEOUT_MS;

        private Builder() {
        }

        /**
         * Sets the API key for authenticating with NodeLoom. Required.
         *
         * @param apiKey the API key (typically starts with "sdk_")
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the base URL of the NodeLoom ingestion API.
         * Defaults to "https://api.nodeloom.io".
         *
         * @param endpoint the base URL
         * @return this builder
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Sets the deployment environment name (e.g., "production", "staging", "development").
         * Defaults to "production".
         *
         * @param environment the environment name
         * @return this builder
         */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the version string for the agent being instrumented.
         *
         * @param agentVersion the agent version (e.g., "1.2.3")
         * @return this builder
         */
        public Builder agentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
            return this;
        }

        /**
         * Sets the maximum number of events the internal queue will hold.
         * Events are silently dropped when the queue is full.
         * Defaults to 10,000.
         *
         * @param maxQueueSize the maximum queue capacity
         * @return this builder
         */
        public Builder maxQueueSize(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        /**
         * Sets the maximum number of events per batch request.
         * Defaults to 100.
         *
         * @param maxBatchSize the max batch size
         * @return this builder
         */
        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        /**
         * Sets the interval between automatic batch flushes in milliseconds.
         * Defaults to 5,000 (5 seconds).
         *
         * @param flushIntervalMs the flush interval
         * @return this builder
         */
        public Builder flushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
            return this;
        }

        /**
         * Sets the maximum number of retry attempts for failed batch requests.
         * Defaults to 3.
         *
         * @param maxRetries the maximum retries
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the base delay for exponential backoff on retries, in milliseconds.
         * The actual delay is {@code baseRetryDelayMs * 2^(attempt-1)}.
         * Defaults to 1,000 (1 second).
         *
         * @param baseRetryDelayMs the base retry delay
         * @return this builder
         */
        public Builder baseRetryDelayMs(long baseRetryDelayMs) {
            this.baseRetryDelayMs = baseRetryDelayMs;
            return this;
        }

        /**
         * Sets the HTTP request timeout in milliseconds.
         * Defaults to 10,000 (10 seconds).
         *
         * @param httpTimeoutMs the timeout
         * @return this builder
         */
        public Builder httpTimeoutMs(long httpTimeoutMs) {
            this.httpTimeoutMs = httpTimeoutMs;
            return this;
        }

        /**
         * Builds and returns a new {@link NodeLoom} client.
         *
         * @return a configured, ready-to-use NodeLoom client
         * @throws NullPointerException if apiKey is null
         * @throws IllegalArgumentException if apiKey is blank
         */
        public NodeLoom build() {
            Objects.requireNonNull(apiKey, "apiKey must not be null");
            if (apiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("apiKey must not be blank");
            }

            NodeLoomConfig config = new NodeLoomConfig(
                    apiKey,
                    endpoint,
                    environment,
                    agentVersion,
                    maxQueueSize,
                    maxBatchSize,
                    flushIntervalMs,
                    maxRetries,
                    baseRetryDelayMs,
                    httpTimeoutMs
            );
            return new NodeLoom(config);
        }
    }
}
