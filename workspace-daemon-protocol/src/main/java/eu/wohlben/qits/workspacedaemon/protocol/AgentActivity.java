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
 * @param awaitingInput (qits-895) whether the agent is blocked on the user rather than merely
 *     between turns — {@code true} for a {@code Stop} whose {@code background_tasks} and {@code
 *     session_crons} are both present and empty, for a {@code Notification} whose {@code
 *     notification_type} is {@code permission_prompt} or {@code elicitation_dialog}, and for every
 *     {@code ENDED} frame (nothing is coming next, hook-driven or killed); {@code false} for a
 *     {@code Stop} with either array non-empty (a background task or a cron is still going to want
 *     the agent's attention) and for {@code UserPromptSubmit}; {@code null} when the payload does
 *     not say — {@code SessionStart}, an {@code other}-typed {@code Notification}, or a {@code
 *     Stop} whose arrays are missing or not JSON arrays at all (an older harness, say). Null on
 *     every frame built before this field existed. Optional on the wire (written only when
 *     present), the {@code exitCode}/{@code message} rule again: an older host reading a newer
 *     frame simply does not see the key, and a newer host reading an older frame decodes {@code
 *     null} — "unknown", not "no"
 * @param agentId (capability 10, qits-1152) the agent worktree whose harness the command runs, or
 *     null for a command no agent owns (the sign-in terminal). A workspace hosts several agents,
 *     so the host keys the session id and the activity by agent, not by workspace. Optional on the
 *     wire, written only when present
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
    String message,
    Boolean awaitingInput,
    String agentId)
    implements DaemonMessage {

  /** Every field but {@code agentId}, which is then null: the shape before capability 10. */
  public AgentActivity(
      String commandId,
      String sessionId,
      String state,
      String hookEvent,
      String source,
      String transcriptPath,
      long at,
      Integer exitCode,
      String message,
      Boolean awaitingInput) {
    this(
        commandId,
        sessionId,
        state,
        hookEvent,
        source,
        transcriptPath,
        at,
        exitCode,
        message,
        awaitingInput,
        null);
  }

  /** This frame, naming the agent whose harness sent it. */
  public AgentActivity withAgentId(String agent) {
    return new AgentActivity(
        commandId,
        sessionId,
        state,
        hookEvent,
        source,
        transcriptPath,
        at,
        exitCode,
        message,
        awaitingInput,
        agent);
  }

  /**
   * A hook-driven frame with no verdict on {@code awaitingInput}: no exit code and no message
   * either. Kept for source compatibility with every caller written before qits-895.
   */
  public AgentActivity(
      String commandId,
      String sessionId,
      String state,
      String hookEvent,
      String source,
      String transcriptPath,
      long at) {
    this(commandId, sessionId, state, hookEvent, source, transcriptPath, at, null, null, null);
  }

  /**
   * A hook-driven frame carrying its {@code awaitingInput} verdict: still no exit code or message.
   */
  public AgentActivity(
      String commandId,
      String sessionId,
      String state,
      String hookEvent,
      String source,
      String transcriptPath,
      long at,
      Boolean awaitingInput) {
    this(
        commandId,
        sessionId,
        state,
        hookEvent,
        source,
        transcriptPath,
        at,
        null,
        null,
        awaitingInput);
  }

  /**
   * A killed-agent frame with no {@code awaitingInput} opinion of its own. Kept for source
   * compatibility with every caller written before qits-895; {@code workspace-daemon}'s own killed
   * frame now calls the canonical constructor directly so it carries {@code true}.
   */
  public AgentActivity(
      String commandId,
      String sessionId,
      String state,
      String hookEvent,
      String source,
      String transcriptPath,
      long at,
      Integer exitCode,
      String message) {
    this(
        commandId,
        sessionId,
        state,
        hookEvent,
        source,
        transcriptPath,
        at,
        exitCode,
        message,
        null);
  }
}
