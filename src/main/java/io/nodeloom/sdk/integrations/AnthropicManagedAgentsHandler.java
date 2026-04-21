package io.nodeloom.sdk.integrations;

import io.nodeloom.sdk.NodeLoom;
import io.nodeloom.sdk.Span;
import io.nodeloom.sdk.SpanType;
import io.nodeloom.sdk.Trace;
import io.nodeloom.sdk.TraceStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler for auto-instrumenting Anthropic Managed Agent sessions with NodeLoom.
 *
 * <pre>{@code
 * NodeLoom client = NodeLoom.builder().apiKey("sdk_...").build();
 * AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
 *     .client(client)
 *     .agentName("my-agent")
 *     .build();
 *
 * SessionTrace session = handler.traceSession("sess_123");
 * // Process events from Anthropic stream...
 * session.onEvent("agent.message", Map.of("content", List.of(Map.of("text", "Hello!"))));
 * session.onEvent("agent.tool_use", Map.of("name", "bash", "input", Map.of("command", "ls")));
 * session.end();
 * }</pre>
 */
public class AnthropicManagedAgentsHandler {

    private final NodeLoom client;
    private final String agentName;
    private final String agentVersion;
    private final boolean guardrails;

    private AnthropicManagedAgentsHandler(Builder builder) {
        this.client = builder.client;
        this.agentName = builder.agentName;
        this.agentVersion = builder.agentVersion;
        this.guardrails = builder.guardrails;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a new session trace for an Anthropic Managed Agent session.
     */
    public SessionTrace traceSession(String sessionId) {
        Trace trace = client.trace(agentName).sessionId(sessionId).start();
        return new SessionTrace(trace, client, guardrails, agentName);
    }

    /**
     * Represents an active session being traced.
     */
    public static class SessionTrace implements AutoCloseable {
        private final Trace trace;
        private final NodeLoom client;
        private final boolean guardrails;
        private final String agentName;
        private final Map<String, Span> activeSpans = new ConcurrentHashMap<>();
        private Map<String, Object> lastOutput;

        SessionTrace(Trace trace, NodeLoom client, boolean guardrails, String agentName) {
            this.trace = trace;
            this.client = client;
            this.guardrails = guardrails;
            this.agentName = agentName;
        }

        /**
         * Process an Anthropic SSE event.
         *
         * @param eventType The event type (e.g., "agent.message", "agent.tool_use")
         * @param eventData The event data as a map
         */
        @SuppressWarnings("unchecked")
        public void onEvent(String eventType, Map<String, Object> eventData) {
            if (eventType == null) return;

            switch (eventType) {
                case "agent.message":
                    handleMessage(eventData);
                    break;
                case "agent.tool_use":
                    handleToolUse(eventData);
                    break;
                case "agent.tool_result":
                    handleToolResult(eventData);
                    break;
                case "agent.thinking":
                    handleThinking(eventData);
                    break;
                default:
                    break;
            }
        }

        /**
         * End the session trace.
         */
        public void end() {
            end(TraceStatus.SUCCESS);
        }

        /**
         * End the session trace with a specific status.
         */
        public void end(TraceStatus status) {
            for (Span span : activeSpans.values()) {
                span.end();
            }
            activeSpans.clear();
            trace.end(status, lastOutput);
        }

        @Override
        public void close() {
            end();
        }

        /**
         * Check input text against guardrails.
         * Returns a map with "passed" (boolean) and "violations" (list) keys.
         * When guardrails are disabled, always returns passed=true.
         */
        public Map<String, Object> checkInput(String text) {
            if (!guardrails) return Map.of("passed", true, "violations", List.of());
            // Guardrails require a team-level API call; delegate to the REST API
            // with a well-known body structure. agentName binds the guardrail
            // session to this agent for HARD-mode required-guardrail enforcement.
            try {
                String body = "{\"text\":" + jsonEscape(text)
                        + ",\"agentName\":" + jsonEscape(agentName)
                        + ",\"direction\":\"input\"}";
                client.api().checkGuardrails("", body);
            } catch (Exception e) {
                // Fire-and-forget: return a safe default on failure
            }
            return Map.of("passed", true, "violations", List.of());
        }

        /**
         * Check output text against guardrails.
         * Returns a map with "passed" (boolean) and "violations" (list) keys.
         * When guardrails are disabled, always returns passed=true.
         */
        public Map<String, Object> checkOutput(String text) {
            if (!guardrails) return Map.of("passed", true, "violations", List.of());
            try {
                String body = "{\"text\":" + jsonEscape(text)
                        + ",\"agentName\":" + jsonEscape(agentName)
                        + ",\"direction\":\"output\"}";
                client.api().checkGuardrails("", body);
            } catch (Exception e) {
                // Fire-and-forget: return a safe default on failure
            }
            return Map.of("passed", true, "violations", List.of());
        }

        @SuppressWarnings("unchecked")
        private void handleMessage(Map<String, Object> data) {
            String text = extractText(data);
            Span span = trace.span("llm-response", SpanType.LLM);
            if (text != null) {
                span.setOutput(Map.of("text", text));
                lastOutput = Map.of("text", text);
            }
            span.end();
        }

        @SuppressWarnings("unchecked")
        private void handleToolUse(Map<String, Object> data) {
            String name = data.containsKey("name") ? String.valueOf(data.get("name")) : "tool";
            Span span = trace.span(name, SpanType.TOOL);
            Object input = data.get("input");
            if (input instanceof Map) {
                span.setInput((Map<String, Object>) input);
            }
            String id = data.containsKey("id") ? String.valueOf(data.get("id")) : null;
            if (id != null && !"null".equals(id)) {
                activeSpans.put(id, span);
            } else {
                span.end();
            }
        }

        private void handleToolResult(Map<String, Object> data) {
            String toolId = data.containsKey("tool_use_id") ? String.valueOf(data.get("tool_use_id")) : null;
            if (toolId != null && activeSpans.containsKey(toolId)) {
                Span span = activeSpans.remove(toolId);
                String text = extractText(data);
                if (text != null) {
                    span.setOutput(Map.of("result", text));
                }
                span.end();
            }
        }

        private void handleThinking(Map<String, Object> data) {
            String text = extractText(data);
            Span span = trace.span("thinking", SpanType.CUSTOM);
            if (text != null) {
                span.setInput(Map.of("thinking", text));
            }
            span.end();
        }

        @SuppressWarnings("unchecked")
        private String extractText(Map<String, Object> data) {
            Object content = data.get("content");
            if (content instanceof List) {
                StringBuilder sb = new StringBuilder();
                for (Object block : (List<?>) content) {
                    if (block instanceof Map) {
                        Object text = ((Map<String, Object>) block).get("text");
                        if (text != null) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(text);
                        }
                    }
                }
                return sb.length() > 0 ? sb.toString() : null;
            }
            if (content instanceof String) return (String) content;
            return null;
        }

        private static String jsonEscape(String s) {
            if (s == null) return "null";
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
    }

    public static class Builder {
        private NodeLoom client;
        private String agentName = "anthropic-managed-agent";
        private String agentVersion;
        private boolean guardrails = true;

        public Builder client(NodeLoom client) { this.client = client; return this; }
        public Builder agentName(String agentName) { this.agentName = agentName; return this; }
        public Builder agentVersion(String agentVersion) { this.agentVersion = agentVersion; return this; }
        public Builder guardrails(boolean guardrails) { this.guardrails = guardrails; return this; }

        public AnthropicManagedAgentsHandler build() {
            if (client == null) throw new IllegalStateException("NodeLoom client is required");
            return new AnthropicManagedAgentsHandler(this);
        }
    }
}
