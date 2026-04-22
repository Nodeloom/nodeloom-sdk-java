package io.nodeloom.sdk.integrations;

import io.nodeloom.sdk.NodeLoom;
import io.nodeloom.sdk.TraceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnthropicManagedAgentsHandler Tests")
class AnthropicManagedAgentsHandlerTest {

    @Test
    @DisplayName("Builder requires client")
    void builderRequiresClient() {
        assertThrows(IllegalStateException.class, () ->
            AnthropicManagedAgentsHandler.builder().build()
        );
    }

    @Test
    @DisplayName("Builder creates handler with defaults")
    void builderCreatesWithDefaults() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .build();
        assertNotNull(handler);
        client.close();
    }

    @Test
    @DisplayName("Builder accepts custom agent name and version")
    void builderAcceptsCustomNameAndVersion() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("custom-agent")
            .agentVersion("1.0.0")
            .guardrails(false)
            .build();
        assertNotNull(handler);
        client.close();
    }

    @Test
    @DisplayName("SessionTrace handles unknown event types gracefully")
    void sessionTraceHandlesUnknown() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_test");
        session.onEvent("unknown.type", Map.of());
        session.onEvent(null, Map.of());
        session.end();
        client.close();
    }

    @Test
    @DisplayName("SessionTrace processes message events")
    void sessionTraceProcessesMessageEvents() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_msg");
        session.onEvent("agent.message", Map.of(
            "content", List.of(Map.of("text", "Hello!"))
        ));
        session.end();
        client.close();
    }

    @Test
    @DisplayName("SessionTrace processes tool use and result events")
    void sessionTraceProcessesToolEvents() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_tool");
        session.onEvent("agent.tool_use", Map.of(
            "id", "tool_123",
            "name", "bash",
            "input", Map.of("command", "ls")
        ));
        session.onEvent("agent.tool_result", Map.of(
            "tool_use_id", "tool_123",
            "content", "file1.txt\nfile2.txt"
        ));
        session.end();
        client.close();
    }

    @Test
    @DisplayName("SessionTrace processes tool use without id")
    void sessionTraceProcessesToolUseWithoutId() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_tool_noid");
        session.onEvent("agent.tool_use", Map.of(
            "name", "bash"
        ));
        session.end();
        client.close();
    }

    @Test
    @DisplayName("SessionTrace processes thinking events")
    void sessionTraceProcessesThinkingEvents() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_think");
        session.onEvent("agent.thinking", Map.of(
            "content", List.of(Map.of("text", "Let me think..."))
        ));
        session.end();
        client.close();
    }

    @Test
    @DisplayName("SessionTrace end with error status")
    void sessionTraceEndWithStatus() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_err");
        session.end(TraceStatus.ERROR);
        client.close();
    }

    @Test
    @DisplayName("SessionTrace close via AutoCloseable")
    void sessionTraceAutoCloseable() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        try (var session = handler.traceSession("sess_auto")) {
            session.onEvent("agent.message", Map.of(
                "content", "Simple text response"
            ));
        }
        client.close();
    }

    @Test
    @DisplayName("checkInput returns passed when guardrails disabled")
    void checkInputNoGuardrails() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_test");
        Map<String, Object> result = session.checkInput("hello");
        assertTrue((Boolean) result.get("passed"));
        assertEquals(List.of(), result.get("violations"));
        session.end();
        client.close();
    }

    @Test
    @DisplayName("checkOutput returns passed when guardrails disabled")
    void checkOutputNoGuardrails() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_test");
        Map<String, Object> result = session.checkOutput("goodbye");
        assertTrue((Boolean) result.get("passed"));
        assertEquals(List.of(), result.get("violations"));
        session.end();
        client.close();
    }

    @Test
    @DisplayName("SessionTrace handles tool result with no matching tool use")
    void sessionTraceHandlesOrphanToolResult() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_orphan");
        // Send tool_result without a matching tool_use -- should not throw
        session.onEvent("agent.tool_result", Map.of(
            "tool_use_id", "nonexistent",
            "content", "result"
        ));
        session.end();
        client.close();
    }

    @Test
    @DisplayName("extractText handles multiple content blocks")
    void extractTextMultipleBlocks() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_multi");
        session.onEvent("agent.message", Map.of(
            "content", List.of(
                Map.of("text", "Hello"),
                Map.of("text", "World"),
                Map.of("type", "image") // block without text
            )
        ));
        session.end();
        client.close();
    }

    @Test
    @DisplayName("extractText handles empty content list")
    void extractTextEmptyContent() {
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(false)
            .build();

        var session = handler.traceSession("sess_empty");
        session.onEvent("agent.message", Map.of("content", List.of()));
        session.onEvent("agent.message", Map.of()); // no content key
        session.end();
        client.close();
    }

    @Test
    @DisplayName("jsonEscape produces null literal for null input")
    void jsonEscapeNull() {
        assertEquals("null", AnthropicManagedAgentsHandler.SessionTrace.jsonEscape(null));
    }

    @Test
    @DisplayName("jsonEscape wraps empty string in quotes")
    void jsonEscapeEmpty() {
        assertEquals("\"\"", AnthropicManagedAgentsHandler.SessionTrace.jsonEscape(""));
    }

    @Test
    @DisplayName("jsonEscape handles short-escape characters per RFC 8259")
    void jsonEscapeShortEscapes() {
        assertEquals("\"\\\"\\\\\\b\\f\\n\\r\\t\"",
            AnthropicManagedAgentsHandler.SessionTrace.jsonEscape("\"\\\b\f\n\r\t"));
    }

    @Test
    @DisplayName("jsonEscape uses six-hex escape for control chars U+0000..U+001F without short escape")
    void jsonEscapeControlChars() {
        // Every control code that isn't one of the RFC 8259 short escapes
        // (b, t, n, f, r) must be rendered as the six-character hex form.
        // Without this the SDK would emit invalid JSON the backend rejects.
        String input = "A\u0000B\u0001C\u000BD\u001FE\u007F"; // U+007F (DEL) is printable in JSON
        String out = AnthropicManagedAgentsHandler.SessionTrace.jsonEscape(input);
        assertEquals("\"A\\u0000B\\u0001C\\u000bD\\u001fE\u007f\"", out);
    }

    @Test
    @DisplayName("jsonEscape preserves ASCII printable characters")
    void jsonEscapeAsciiPrintable() {
        assertEquals("\"hello, world!\"",
            AnthropicManagedAgentsHandler.SessionTrace.jsonEscape("hello, world!"));
    }

    @Test
    @DisplayName("jsonEscape preserves non-ASCII Unicode")
    void jsonEscapeUnicode() {
        // RFC 8259 allows non-ASCII chars unescaped in UTF-8 JSON.
        assertEquals("\"héllo 🌍 日本\"",
            AnthropicManagedAgentsHandler.SessionTrace.jsonEscape("héllo 🌍 日本"));
    }

    @Test
    @DisplayName("checkInput with control-char text does not throw")
    void checkInputControlCharText() {
        // Previously, the hand-rolled escaper left control chars unescaped,
        // which could produce invalid JSON the backend rejects. Now the
        // escaper is RFC 8259 compliant; the handler falls through to the
        // safe default on any transport failure.
        NodeLoom client = NodeLoom.builder().apiKey("test").build();
        AnthropicManagedAgentsHandler handler = AnthropicManagedAgentsHandler.builder()
            .client(client)
            .agentName("test")
            .guardrails(true)
            .build();

        var session = handler.traceSession("sess_ctrl");
        Map<String, Object> result = session.checkInput("tool output\u0001 with raw bytes\u001b[0m");
        assertTrue((Boolean) result.get("passed"));
        session.end();
        client.close();
    }
}
