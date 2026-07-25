package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * A coding-agent's lifecycle state, pushed <em>unsolicited</em> from {@code workspace-daemon} to
 * qits whenever the agent process fires a lifecycle hook (SessionStart / UserPromptSubmit / Stop /
 * Notification / SessionEnd), and re-sent for every tracked command on each socket (re)connect.
 * Unlike {@link WorkspaceInfo} (the FIFO reply to a {@link Describe}), this frame is not correlated
 * to any request — the backend caches the {@code state} per {@code commandId} and drives the live
 * "cooking / idle / waiting" chip + the {@code SessionStart} lineage write from it.
 *
 * <p>The hook process POSTs its stdin JSON to the daemon's loopback webhook, which maps {@code
 * hookEvent} to one of {@link DaemonProtocol.AgentState}'s values ({@code state}) and forwards the
 * identity fields verbatim: {@code sessionId}, {@code source}, {@code transcriptPath} (all
 * nullable, exactly what the hook payload carried). {@code commandId} is the qits command the hook
 * was launched under (rendered into the hook URL); {@code at} is the daemon's epoch-millis send
 * time.
 */
public record AgentActivity(
    String commandId,
    String sessionId,
    String state,
    String hookEvent,
    String source,
    String transcriptPath,
    long at)
    implements DaemonMessage {}
