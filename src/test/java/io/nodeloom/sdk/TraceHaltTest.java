package io.nodeloom.sdk;

import io.nodeloom.sdk.control.AgentControlPayload;
import io.nodeloom.sdk.control.AgentHaltedException;
import io.nodeloom.sdk.control.ControlRegistry;
import io.nodeloom.sdk.event.TelemetryEvent;
import io.nodeloom.sdk.queue.TelemetryQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for halt enforcement and guardrail-session attachment in {@link Trace}.
 */
class TraceHaltTest {

    private NodeLoomConfig newConfig() {
        return new NodeLoomConfig(
                "sdk_test",
                "https://api.example.com",
                "test",
                null,
                100,
                10,
                5_000L,
                3,
                100L,
                1_000L
        );
    }

    @Test
    void traceStartThrowsWhenAgentIsHalted() {
        TelemetryQueue queue = new TelemetryQueue(100);
        ControlRegistry registry = new ControlRegistry();
        registry.update(new AgentControlPayload("halted", true, "manual", "agent", 1L, "OFF", 300L));

        Trace trace = new Trace("halted", queue, newConfig(), registry);
        AgentHaltedException ex = assertThrows(AgentHaltedException.class, trace::start);
        assertEquals("halted", ex.getAgentName());
        assertEquals(AgentControlPayload.SOURCE_AGENT, ex.getSource());
    }

    @Test
    void teamHaltAffectsUnknownAgents() {
        TelemetryQueue queue = new TelemetryQueue(100);
        ControlRegistry registry = new ControlRegistry();
        registry.update(new AgentControlPayload(null, true, "incident", "team", 1_000_000L, "OFF", 300L));

        Trace trace = new Trace("never-seen", queue, newConfig(), registry);
        assertThrows(AgentHaltedException.class, trace::start);
    }

    @Test
    void traceStartAttachesGuardrailSessionId() {
        TelemetryQueue queue = new TelemetryQueue(100);
        ControlRegistry registry = new ControlRegistry();
        registry.recordGuardrailSession("ok", "sess-xyz", 300L);

        Trace trace = new Trace("ok", queue, newConfig(), registry);
        trace.start();

        TelemetryEvent event = queue.drain(10).get(0);
        assertNotNull(event);
        assertEquals("trace_start", event.getFields().get("type"));
        assertEquals("sess-xyz", event.getFields().get("guardrail_session_id"));
    }

    @Test
    void traceStartWithoutGuardrailSessionDoesNotAttachField() {
        TelemetryQueue queue = new TelemetryQueue(100);
        ControlRegistry registry = new ControlRegistry();

        Trace trace = new Trace("ok", queue, newConfig(), registry);
        trace.start();

        TelemetryEvent event = queue.drain(10).get(0);
        assertNotNull(event);
        assertFalse(event.getFields().containsKey("guardrail_session_id"));
    }
}
