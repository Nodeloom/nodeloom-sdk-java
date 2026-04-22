package io.nodeloom.sdk.control;

/**
 * Thrown by SDK operations when the targeted agent has been halted by the
 * NodeLoom backend (per-agent or team-wide). Callers should treat this as a
 * deliberate runtime kill switch and avoid retrying without operator approval.
 */
public final class AgentHaltedException extends RuntimeException {

    private final String agentName;
    private final String reason;
    private final String source;
    private final long revision;

    public AgentHaltedException(String agentName, String reason, String source, long revision) {
        super(buildMessage(agentName, reason, source, revision));
        this.agentName = agentName;
        this.reason = reason;
        this.source = source;
        this.revision = revision;
    }

    public String getAgentName() { return agentName; }
    public String getReason() { return reason; }
    public String getSource() { return source; }
    public long getRevision() { return revision; }

    private static String buildMessage(String agentName, String reason, String source, long revision) {
        StringBuilder sb = new StringBuilder("Agent '").append(agentName)
                .append("' is halted (source=").append(source)
                .append(", revision=").append(revision).append(")");
        if (reason != null && !reason.isEmpty()) {
            sb.append(": ").append(reason);
        }
        return sb.toString();
    }
}
