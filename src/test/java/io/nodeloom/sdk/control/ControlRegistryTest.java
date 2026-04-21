package io.nodeloom.sdk.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControlRegistryTest {

    @Test
    void unknownAgentReturnsDefaultPayload() {
        ControlRegistry registry = new ControlRegistry();
        AgentControlPayload p = registry.snapshot("unknown");
        assertFalse(p.isHalted());
        assertEquals(AgentControlPayload.SOURCE_NONE, p.getHaltSource());
        assertEquals(0L, p.getRevision());
        assertEquals("OFF", p.getRequireGuardrails());
    }

    @Test
    void agentLevelHaltIsApplied() {
        ControlRegistry registry = new ControlRegistry();
        registry.update(new AgentControlPayload("agent-1", true, "policy", "agent", 5L, "OFF", 300L));
        AgentControlPayload p = registry.snapshot("agent-1");
        assertTrue(p.isHalted());
        assertEquals("policy", p.getHaltReason());
        assertEquals(AgentControlPayload.SOURCE_AGENT, p.getHaltSource());
        assertEquals(5L, p.getRevision());
    }

    @Test
    void teamHaltOverridesAgentStateForAllAgents() {
        ControlRegistry registry = new ControlRegistry();
        registry.update(new AgentControlPayload("known-agent", false, null, "none", 1L, "OFF", 300L));
        registry.update(new AgentControlPayload("known-agent", true, "incident", "team", 1_000_000L, "OFF", 300L));

        for (String name : new String[]{"known-agent", "never-seen-agent"}) {
            AgentControlPayload p = registry.snapshot(name);
            assertTrue(p.isHalted(), name);
            assertEquals(AgentControlPayload.SOURCE_TEAM, p.getHaltSource(), name);
            assertEquals("incident", p.getHaltReason(), name);
        }
    }

    @Test
    void staleRevisionIsIgnored() {
        ControlRegistry registry = new ControlRegistry();
        registry.update(new AgentControlPayload("agent-1", true, "current", "agent", 10L, "OFF", 300L));
        registry.update(new AgentControlPayload("agent-1", false, null, "none", 3L, "OFF", 300L));
        assertTrue(registry.snapshot("agent-1").isHalted());
    }

    @Test
    void guardrailSessionRoundTripsWithinTtl() {
        ControlRegistry registry = new ControlRegistry();
        long now = 1_000L;
        registry.recordGuardrailSession("agent-1", "sess-abc", 300L, now);
        assertEquals("sess-abc", registry.takeGuardrailSession("agent-1", now + 1_000L));
    }

    @Test
    void expiredGuardrailSessionReturnsNull() {
        ControlRegistry registry = new ControlRegistry();
        long now = 1_000L;
        registry.recordGuardrailSession("agent-1", "sess-abc", 5L, now);
        assertNull(registry.takeGuardrailSession("agent-1", now + 6_000L));
    }

    @Test
    void blankInputsDoNotMintGuardrailSession() {
        ControlRegistry registry = new ControlRegistry();
        registry.recordGuardrailSession("", "sess", 60L);
        registry.recordGuardrailSession("agent-1", "", 60L);
        assertNull(registry.takeGuardrailSession("agent-1"));
    }

    @Test
    void raiseIfHaltedThrowsForHaltedAgent() {
        ControlRegistry registry = new ControlRegistry();
        registry.update(new AgentControlPayload("agent-1", true, "policy", "agent", 1L, "OFF", 300L));
        AgentHaltedException ex = assertThrows(AgentHaltedException.class,
                () -> ControlRegistry.raiseIfHalted(registry, "agent-1"));
        assertEquals("agent-1", ex.getAgentName());
        assertEquals(AgentControlPayload.SOURCE_AGENT, ex.getSource());
        assertEquals("policy", ex.getReason());
    }

    @Test
    void raiseIfHaltedDoesNothingWithoutRegistry() {
        // Should be a no-op when registry is null.
        assertDoesNotThrow(() -> ControlRegistry.raiseIfHalted(null, "agent-1"));
    }
}
