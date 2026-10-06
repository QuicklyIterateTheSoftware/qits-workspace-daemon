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
 *
 * <p><b>One frame is not a hook's.</b> An agent that is SIGKILLed — the cgroup OOM killer is the
 * one that does it in practice — fires no hook at all, so nothing would ever say its session is
 * over and the host would keep the last state it heard, typically {@code IDLE}: a turn that died
 * mid-work read exactly like one that finished. The daemon sees the process exit instead and sends
 * {@code ENDED} itself, with a {@link DaemonProtocol.AgentEvent} as the {@code hookEvent} and the
 * two optional fields below filled in.
 *
 * @param exitCode the agent process's exit code, on the daemon-synthesised frame for an agent that
 *     was killed; null on every hook-driven frame. Optional on the wire (written only when present),
 *     so every hook frame is byte-identical to what it was before the field existed
 * @param message one sentence saying what killed the agent — the OOM killer or a plain SIGKILL, and
 *     the container's memory cap where the daemon could read it; null on every hook-driven frame.
 *     Optional on the wire for {@code exitCode}'s reason
 */
public record AgentActivity(
    String commandId,
    String sessionId,
    String state,
    String hookEvent,
    String source,
    String transcriptPath,
    long at,
    Integer exitCode,
    String message)
    implements DaemonMessage {

  /** A hook-driven frame: no exit code and no message, which is every frame but a kill's. */
  public AgentActivity(
      String commandId,
      String sessionId,
      String state,
      String hookEvent,
      String source,
      String transcriptPath,
      long at) {
    this(commandId, sessionId, state, hookEvent, source, transcriptPath, at, null, null);
  }
}
