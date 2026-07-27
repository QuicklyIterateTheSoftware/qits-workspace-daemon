package eu.wohlben.qits.workspacedaemon.commands;

import java.time.Instant;

/**
 * One coding-agent session a command drove, in the order the sessions were entered. The command's
 * current session is the last entry; most commands have exactly one. Lineage falls out of the
 * entries: every command whose list contains a session ID belongs to that session's conversation
 * thread, and {@link #forkedFromSessionId} edges form the fork tree.
 *
 * <p>A record rather than the host's {@code @Embeddable}. On the host this was the
 * {@code command_agent_session} collection table — the one that needed V32 to add the missing
 * {@code on delete cascade}. Here the list is a field of an in-memory {@link Command} and there is
 * no second table to keep referentially intact.
 *
 * @param sessionId the agent session id — daemon-generated and pinned at launch, or hook-reported
 * @param source how the session entered the list
 * @param forkedFromSessionId set on {@link AgentSessionSource#FORKED} entries: the session this one
 *     branched from
 * @param transcriptPath the transcript JSONL path as reported by the harness's SessionStart hook,
 *     authoritative over the computed convention; null until the hook's first report
 * @param recordedAt when the entry was pinned at launch or reported by the hook
 */
public record AgentSessionRef(
    String sessionId,
    AgentSessionSource source,
    String forkedFromSessionId,
    String transcriptPath,
    Instant recordedAt) {}
