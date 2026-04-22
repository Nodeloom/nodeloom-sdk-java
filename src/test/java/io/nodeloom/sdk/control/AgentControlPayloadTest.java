package io.nodeloom.sdk.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentControlPayloadTest {

    @Test
    void parsesFullJsonPayload() {
        String json = "{" +
                "\"agent_name\":\"agent-1\"," +
                "\"halted\":true," +
                "\"halt_reason\":\"policy violation\"," +
                "\"halt_source\":\"agent\"," +
                "\"revision\":42," +
                "\"require_guardrails\":\"HARD\"," +
                "\"guardrail_session_ttl_seconds\":120" +
                "}";
        AgentControlPayload p = AgentControlPayload.fromJson(json);
        assertNotNull(p);
        assertEquals("agent-1", p.getAgentName());
        assertTrue(p.isHalted());
        assertEquals("policy violation", p.getHaltReason());
        assertEquals(AgentControlPayload.SOURCE_AGENT, p.getHaltSource());
        assertEquals(42L, p.getRevision());
        assertEquals("HARD", p.getRequireGuardrails());
        assertEquals(120L, p.getGuardrailSessionTtlSeconds());
    }

    @Test
    void appliesDefaultsForMissingFields() {
        AgentControlPayload p = AgentControlPayload.fromJson("{\"agent_name\":\"a\"}");
        assertNotNull(p);
        assertFalse(p.isHalted());
        assertEquals(AgentControlPayload.SOURCE_NONE, p.getHaltSource());
        assertEquals("OFF", p.getRequireGuardrails());
        assertEquals(300L, p.getGuardrailSessionTtlSeconds());
    }

    @Test
    void returnsNullForBlankInput() {
        assertNull(AgentControlPayload.fromJson(null));
        assertNull(AgentControlPayload.fromJson(""));
        assertNull(AgentControlPayload.fromJson("   "));
    }
}
