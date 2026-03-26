package io.nodeloom.sdk.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a batch of telemetry events to be sent in a single HTTP request.
 *
 * <p>The JSON format is:
 * <pre>
 * {
 *   "events": [...],
 *   "sdk_version": "0.1.0",
 *   "sdk_language": "java"
 * }
 * </pre>
 */
public final class BatchRequest {

    public static final String SDK_VERSION = "0.3.1";
    public static final String SDK_LANGUAGE = "java";

    private final List<TelemetryEvent> events;

    public BatchRequest(List<TelemetryEvent> events) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    public List<TelemetryEvent> getEvents() {
        return events;
    }

    public int size() {
        return events.size();
    }

    /**
     * Serializes the batch to JSON.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(events.get(i).toJson());
        }
        sb.append("],\"sdk_version\":\"").append(SDK_VERSION).append("\"");
        sb.append(",\"sdk_language\":\"").append(SDK_LANGUAGE).append("\"");
        sb.append('}');
        return sb.toString();
    }
}
