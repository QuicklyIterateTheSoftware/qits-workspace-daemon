package eu.wohlben.qits.workspacedaemon.agents;

import java.time.Instant;
import java.util.List;

/**
 * One node of the agent-session tree: a session, its fork children, and its subagent sidechains.
 *
 * <p>The field names are a wire contract — see {@link AgentSubagentDto}.
 *
 * @param messageCount null until a sweep has aggregated the session's transcript
 * @param newestCommandId the most recent command that drove this session — the UI's navigation
 *     target
 */
public record AgentSessionNodeDto(
    String sessionId,
    Instant firstRecordedAt,
    String forkedFromSessionId,
    Integer messageCount,
    String newestCommandId,
    List<AgentSubagentDto> subagents,
    List<AgentSessionNodeDto> children) {}
