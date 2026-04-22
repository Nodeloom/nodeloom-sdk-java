package io.nodeloom.sdk.control;

import io.nodeloom.sdk.event.JsonReader;

import java.util.Map;

/**
 * Snapshot of the remote-control payload returned by the NodeLoom backend.
 *
 * <p>Returned by {@code GET /api/sdk/v1/agents/&#123;name&#125;/control} and piggy-backed
 * on every telemetry batch response. SDK clients use this to detect halt
 * state and required-guardrail mode without an extra round-trip.</p>
 */
public final class AgentControlPayload {

    public static final String SOURCE_NONE = "none";
    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_TEAM = "team";

    private final String agentName;
    private final boolean halted;
    private final String haltReason;
    private final String haltSource;
    private final long revision;
    private final String requireGuardrails;
    private final long guardrailSessionTtlSeconds;

    public AgentControlPayload(String agentName, boolean halted, String haltReason,
                               String haltSource, long revision, String requireGuardrails,
                               long guardrailSessionTtlSeconds) {
        this.agentName = agentName;
        this.halted = halted;
        this.haltReason = haltReason;
        this.haltSource = haltSource == null ? SOURCE_NONE : haltSource;
        this.revision = revision;
        this.requireGuardrails = requireGuardrails == null ? "OFF" : requireGuardrails.toUpperCase();
        this.guardrailSessionTtlSeconds = guardrailSessionTtlSeconds;
    }

    public String getAgentName() { return agentName; }
    public boolean isHalted() { return halted; }
    public String getHaltReason() { return haltReason; }
    public String getHaltSource() { return haltSource; }
    public long getRevision() { return revision; }
    public String getRequireGuardrails() { return requireGuardrails; }
    public long getGuardrailSessionTtlSeconds() { return guardrailSessionTtlSeconds; }

    /**
     * Parse an {@link AgentControlPayload} from a JSON string. Returns null
     * when the input is null or does not parse to an object.
     */
    public static AgentControlPayload fromJson(String json) {
        Map<String, Object> map = JsonReader.parseObject(json);
        if (map == null || map.isEmpty()) return null;
        return fromMap(map);
    }

    /** Build a payload from an already-parsed JSON map. */
    public static AgentControlPayload fromMap(Map<String, Object> map) {
        if (map == null) return null;
        String agentName = stringValue(map.get("agent_name"));
        boolean halted = Boolean.TRUE.equals(map.get("halted"));
        String haltReason = stringValue(map.get("halt_reason"));
        String haltSource = stringValue(map.get("halt_source"));
        long revision = longValue(map.get("revision"), 0L);
        String requireGuardrails = stringValue(map.get("require_guardrails"));
        long ttl = clampTtl(longValue(map.get("guardrail_session_ttl_seconds"), 300L));
        return new AgentControlPayload(agentName, halted, haltReason, haltSource,
                revision, requireGuardrails, ttl);
    }

    /** Clamp TTL to [1, 86400] seconds; guards against a buggy server payload. */
    private static long clampTtl(long raw) {
        if (raw < 1L || raw > 86_400L) {
            return 300L;
        }
        return raw;
    }

    private static String stringValue(Object v) {
        return v == null ? null : v.toString();
    }

    private static long longValue(Object v, long fallback) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
