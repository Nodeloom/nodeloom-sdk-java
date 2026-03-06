package com.nodeloom.sdk.event;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a single telemetry event (trace_start, span, or trace_end)
 * that will be serialized to JSON and sent to the NodeLoom ingestion API.
 *
 * <p>This class is not thread-safe. It is designed to be fully populated
 * by a single thread before being enqueued.</p>
 */
public final class TelemetryEvent {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    public TelemetryEvent() {
    }

    /**
     * Sets a top-level field on the event.
     *
     * @param key   the JSON field name (snake_case)
     * @param value the value (String, Number, Boolean, Map, or null)
     * @return this event for chaining
     */
    public TelemetryEvent put(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    /**
     * Convenience method to set the timestamp field to the current instant.
     *
     * @return this event for chaining
     */
    public TelemetryEvent putTimestampNow(String key) {
        fields.put(key, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        return this;
    }

    /**
     * Returns the raw field map. Used by the JSON serializer.
     */
    public Map<String, Object> getFields() {
        return fields;
    }

    /**
     * Serializes this event to a JSON string.
     */
    public String toJson() {
        return JsonWriter.toJson(fields);
    }

    /**
     * Minimal JSON writer that handles the types used by telemetry events.
     * Supports: String, Number, Boolean, null, Map, and Iterable.
     * No external dependencies.
     */
    static final class JsonWriter {

        private JsonWriter() {
        }

        static String toJson(Object value) {
            StringBuilder sb = new StringBuilder();
            writeValue(sb, value);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void writeValue(StringBuilder sb, Object value) {
            if (value == null) {
                sb.append("null");
            } else if (value instanceof String) {
                writeString(sb, (String) value);
            } else if (value instanceof Number) {
                sb.append(value);
            } else if (value instanceof Boolean) {
                sb.append(value);
            } else if (value instanceof Map) {
                writeMap(sb, (Map<String, Object>) value);
            } else if (value instanceof Iterable) {
                writeArray(sb, (Iterable<?>) value);
            } else {
                // Fallback: treat as string
                writeString(sb, value.toString());
            }
        }

        private static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }

        private static void writeMap(StringBuilder sb, Map<String, Object> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, entry.getKey());
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        }

        private static void writeArray(StringBuilder sb, Iterable<?> items) {
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        }
    }
}
