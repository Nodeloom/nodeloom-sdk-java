package io.nodeloom.sdk.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe per-agent control registry shared between the transport, the
 * optional poller, and the {@code Trace} factory.
 *
 * <p>The backend exposes both per-agent and team-wide halt flags. Team-wide
 * halt always overrides individual agent state, so unknown agents inherit it
 * automatically.</p>
 */
public final class ControlRegistry {

    /** Internal mutable state for a single agent. Guarded by {@code lock}. */
    static final class Entry {
        boolean halted;
        String haltReason;
        String haltSource = AgentControlPayload.SOURCE_NONE;
        long revision;
        String requireGuardrails = "OFF";
        long guardrailSessionTtlSeconds = 300L;
        String guardrailSessionId;
        long guardrailSessionExpiresAtMs;
    }

    private final Object lock = new Object();
    private final Map<String, Entry> agents = new HashMap<>();
    private boolean teamHalted;
    private String teamHaltReason;
    private long teamRevision;

    /** Returns a snapshot of an agent's control state. */
    public AgentControlPayload snapshot(String agentName) {
        synchronized (lock) {
            Entry e = agents.get(agentName);
            boolean halted;
            String reason;
            String source;
            long revision;
            String requireGuardrails;
            long ttl;
            if (e == null) {
                halted = false;
                reason = null;
                source = AgentControlPayload.SOURCE_NONE;
                revision = 0;
                requireGuardrails = "OFF";
                ttl = 300L;
            } else {
                halted = e.halted;
                reason = e.haltReason;
                source = e.haltSource;
                revision = e.revision;
                requireGuardrails = e.requireGuardrails;
                ttl = e.guardrailSessionTtlSeconds;
            }
            if (teamHalted) {
                halted = true;
                source = AgentControlPayload.SOURCE_TEAM;
                reason = teamHaltReason;
            }
            return new AgentControlPayload(agentName, halted, reason, source, revision,
                    requireGuardrails, ttl);
        }
    }

    public boolean isHalted(String agentName) {
        synchronized (lock) {
            if (teamHalted) return true;
            Entry e = agents.get(agentName);
            return e != null && e.halted;
        }
    }

    public List<String> knownAgents() {
        synchronized (lock) {
            return new ArrayList<>(agents.keySet());
        }
    }

    /**
     * Merge a backend control payload into the registry. Stale revisions are
     * ignored to keep updates idempotent and order-tolerant.
     */
    public void update(AgentControlPayload payload) {
        if (payload == null) return;
        synchronized (lock) {
            String source = payload.getHaltSource();
            long revision = payload.getRevision();
            boolean halted = payload.isHalted();

            if (AgentControlPayload.SOURCE_TEAM.equals(source)) {
                if (revision >= teamRevision) {
                    teamHalted = halted;
                    teamHaltReason = payload.getHaltReason();
                    teamRevision = revision;
                }
            } else if (!halted && revision >= teamRevision) {
                teamHalted = false;
                teamHaltReason = null;
            }

            String agentName = payload.getAgentName();
            if (agentName == null || agentName.isEmpty()) return;
            Entry e = agents.computeIfAbsent(agentName, k -> new Entry());
            if (revision < e.revision) return;
            e.halted = halted && !AgentControlPayload.SOURCE_TEAM.equals(source);
            e.haltReason = AgentControlPayload.SOURCE_TEAM.equals(source) ? null : payload.getHaltReason();
            e.haltSource = source;
            e.revision = revision;
            e.requireGuardrails = payload.getRequireGuardrails();
            e.guardrailSessionTtlSeconds = payload.getGuardrailSessionTtlSeconds();
        }
    }

    /** Cache a guardrail session id returned by checkGuardrails. */
    public void recordGuardrailSession(String agentName, String sessionId, long ttlSeconds) {
        recordGuardrailSession(agentName, sessionId, ttlSeconds, System.currentTimeMillis());
    }

    void recordGuardrailSession(String agentName, String sessionId, long ttlSeconds, long nowMs) {
        if (agentName == null || agentName.isEmpty() || sessionId == null || sessionId.isEmpty()) return;
        synchronized (lock) {
            Entry e = agents.computeIfAbsent(agentName, k -> new Entry());
            e.guardrailSessionId = sessionId;
            long ttl = Math.max(1L, ttlSeconds);
            e.guardrailSessionExpiresAtMs = nowMs + ttl * 1000L;
        }
    }

    /** Return the cached guardrail session id while it is still within TTL. */
    public String takeGuardrailSession(String agentName) {
        return takeGuardrailSession(agentName, System.currentTimeMillis());
    }

    String takeGuardrailSession(String agentName, long nowMs) {
        synchronized (lock) {
            Entry e = agents.get(agentName);
            if (e == null || e.guardrailSessionId == null) return null;
            if (nowMs >= e.guardrailSessionExpiresAtMs) {
                e.guardrailSessionId = null;
                e.guardrailSessionExpiresAtMs = 0L;
                return null;
            }
            return e.guardrailSessionId;
        }
    }

    /** Convenience for tests: wipe all state. */
    public void clear() {
        synchronized (lock) {
            agents.clear();
            teamHalted = false;
            teamHaltReason = null;
            teamRevision = 0;
        }
    }

    public static void raiseIfHalted(ControlRegistry registry, String agentName) {
        if (registry == null) return;
        AgentControlPayload payload = registry.snapshot(agentName);
        if (payload.isHalted()) {
            throw new AgentHaltedException(agentName, payload.getHaltReason(),
                    payload.getHaltSource(), payload.getRevision());
        }
    }

    /** Convenience for the "no team-wide halt" payload (used in tests). */
    public Map<String, Object> testStateSnapshot() {
        synchronized (lock) {
            Map<String, Object> out = new HashMap<>();
            out.put("teamHalted", teamHalted);
            out.put("teamReason", teamHaltReason);
            out.put("teamRevision", teamRevision);
            out.put("agents", new HashMap<>(agents));
            return out;
        }
    }
}
