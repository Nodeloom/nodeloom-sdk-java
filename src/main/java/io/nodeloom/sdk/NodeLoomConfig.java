package io.nodeloom.sdk;

/**
 * Immutable configuration for the NodeLoom SDK client.
 *
 * <p>Use {@link NodeLoom#builder()} to construct instances. All fields
 * have sensible defaults except {@code apiKey}, which is required.</p>
 */
public final class NodeLoomConfig {

    private final String apiKey;
    private final String endpoint;
    private final String environment;
    private final String agentVersion;
    private final int maxQueueSize;
    private final int maxBatchSize;
    private final long flushIntervalMs;
    private final int maxRetries;
    private final long baseRetryDelayMs;
    private final long httpTimeoutMs;

    NodeLoomConfig(String apiKey,
                   String endpoint,
                   String environment,
                   String agentVersion,
                   int maxQueueSize,
                   int maxBatchSize,
                   long flushIntervalMs,
                   int maxRetries,
                   long baseRetryDelayMs,
                   long httpTimeoutMs) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.environment = environment;
        this.agentVersion = agentVersion;
        this.maxQueueSize = maxQueueSize;
        this.maxBatchSize = maxBatchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.maxRetries = maxRetries;
        this.baseRetryDelayMs = baseRetryDelayMs;
        this.httpTimeoutMs = httpTimeoutMs;
    }

    /** The API key used to authenticate with NodeLoom. */
    public String getApiKey() {
        return apiKey;
    }

    /** The base URL of the NodeLoom ingestion endpoint. */
    public String getEndpoint() {
        return endpoint;
    }

    /** The deployment environment (e.g., "production", "staging"). */
    public String getEnvironment() {
        return environment;
    }

    /** An optional version string for the agent being instrumented. */
    public String getAgentVersion() {
        return agentVersion;
    }

    /** Maximum number of events the internal queue will hold before dropping. */
    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    /** Maximum number of events to include in a single batch request. */
    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    /** Interval in milliseconds between automatic batch flushes. */
    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    /** Maximum number of retry attempts for failed HTTP requests. */
    public int getMaxRetries() {
        return maxRetries;
    }

    /** Base delay in milliseconds for exponential backoff on retries. */
    public long getBaseRetryDelayMs() {
        return baseRetryDelayMs;
    }

    /** HTTP request timeout in milliseconds. */
    public long getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    @Override
    public String toString() {
        String maskedKey = apiKey != null && apiKey.length() > 6 ? apiKey.substring(0, 6) + "***" : "***";
        return "NodeLoomConfig{endpoint='" + endpoint + "', apiKey='" + maskedKey + "', environment='" + environment + "'}";
    }
}
