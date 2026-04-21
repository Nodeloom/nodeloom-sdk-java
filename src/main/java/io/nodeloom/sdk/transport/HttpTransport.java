package io.nodeloom.sdk.transport;

import io.nodeloom.sdk.NodeLoomConfig;
import io.nodeloom.sdk.control.AgentControlPayload;
import io.nodeloom.sdk.control.ControlRegistry;
import io.nodeloom.sdk.event.BatchRequest;
import io.nodeloom.sdk.event.BatchResponse;
import io.nodeloom.sdk.event.JsonReader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends batch telemetry requests to the NodeLoom ingestion API using
 * {@link java.net.http.HttpClient}.
 *
 * <p>This class is thread-safe. The underlying HttpClient manages its own
 * connection pool.</p>
 */
public class HttpTransport {

    private static final Logger logger = Logger.getLogger(HttpTransport.class.getName());

    private static final String BATCH_PATH = "/api/sdk/v1/telemetry";

    private final HttpClient httpClient;
    private final String batchUrl;
    private final String apiKey;
    private final Duration timeout;
    private final ControlRegistry controlRegistry;

    public HttpTransport(NodeLoomConfig config) {
        this(config, null);
    }

    public HttpTransport(NodeLoomConfig config, ControlRegistry controlRegistry) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getHttpTimeoutMs()))
                .build();
        this.batchUrl = stripTrailingSlash(config.getEndpoint()) + BATCH_PATH;
        this.apiKey = config.getApiKey();
        this.timeout = Duration.ofMillis(config.getHttpTimeoutMs());
        this.controlRegistry = controlRegistry;
    }

    /**
     * Sends a batch of events to the NodeLoom API.
     *
     * @param batch the batch request to send
     * @return the response from the server
     * @throws Exception if the HTTP call fails for any reason (network error, timeout, etc.)
     */
    public BatchResponse send(BatchRequest batch) throws Exception {
        String json = batch.toJson();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(batchUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "nodeloom-java-sdk/" + BatchRequest.SDK_VERSION)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Sending batch of " + batch.size() + " events to " + batchUrl);
        }

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        String body = response.body();

        // Forward the piggy-backed control payload BEFORE truncation so the
        // registry sees fresh halt state without an extra round-trip.
        if (controlRegistry != null && response.statusCode() >= 200 && response.statusCode() < 300 && body != null) {
            try {
                Map<String, Object> parsed = JsonReader.parseObject(body);
                Object control = parsed.get("control");
                if (control instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> controlMap = (Map<String, Object>) control;
                    AgentControlPayload payload = AgentControlPayload.fromMap(controlMap);
                    controlRegistry.update(payload);
                }
            } catch (RuntimeException ex) {
                logger.log(Level.FINE, "Failed to parse control payload from telemetry response", ex);
            }
        }

        if (body != null && body.length() > 4096) {
            body = body.substring(0, 4096) + "...[truncated]";
        }

        return new BatchResponse(response.statusCode(), body);
    }

    /**
     * Shuts down the underlying HTTP client, releasing resources.
     */
    public void shutdown() {
        // HttpClient in Java 11+ does not have an explicit close/shutdown.
        // In Java 21+, HttpClient implements AutoCloseable.
        // For compatibility, we simply null out the reference.
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
