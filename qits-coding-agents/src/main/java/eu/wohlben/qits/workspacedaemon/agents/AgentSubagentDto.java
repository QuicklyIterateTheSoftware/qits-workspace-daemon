package eu.wohlben.qits.workspacedaemon.agents;

import java.time.Instant;

/**
 * One subagent sidechain of a session, as the session-history UI renders it.
 *
 * <p>The field names are a wire contract: {@code AgentJson} serializes them into the body the host's
 * {@code AgentSubagentDto} record deserializes, and the SPA consumes that unchanged. A rename here is
 * a broken Agents view that nothing in this reactor would notice.
 */
public record AgentSubagentDto(
    String agentId,
    String agentType,
    String description,
    int messageCount,
    Instant firstTimestamp) {}
