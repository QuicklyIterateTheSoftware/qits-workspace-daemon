package eu.wohlben.qits.workspacedaemon.protocol;

import java.time.Instant;

/**
 * {@code workspace-daemon} → qits: an agent's harness command settled (capability 11, qits-1236).
 *
 * <p>Sent once for every agent command the daemon sees finish — the same exits it logs since
 * qits-1233 — so the host can say on its bus that a harness is gone, and why. Before this only an
 * exit 137 travelled home, as an {@link AgentActivity} {@code ENDED}; a harness that ended any
 * other way left the host believing it still ran.
 *
 * <p>A frame of its own and not another {@link AgentActivity}: that frame's {@code hookEvent} is
 * decoded by name into an enum on the host, and its {@code ENDED} drives the agent's state. An exit
 * is a fact about a process, not a hook, and the killed agent's {@code ENDED} still comes as it
 * always did, beside this one.
 *
 * <p>Unsolicited and not correlated. A backend older than 11 does not know the tag and drops the
 * frame ({@code DaemonControlSocket} catches an undecodable frame and logs it); nothing else about
 * the socket changes.
 *
 * @param agentId the agent whose harness it was, or null for an agent command no agent of this
 *     daemon owns (the sign-in terminal)
 * @param commandId the harness command that settled
 * @param agentType the harness kind ({@code claude}, {@code kimi}, …), as the command records it
 * @param status the command's final status by name ({@code EXITED}, {@code TERMINATED}, …)
 * @param exitCode the process's exit code, or null when the registry recorded none
 * @param stoppedBy what ended it: one of {@link #DAEMON_REMOVE}, {@link #DAEMON_YIELD}, {@link
 *     #KILLED}, {@link #SELF}
 * @param at when the daemon saw the exit; epoch milliseconds on the wire, as {@link
 *     AgentActivity#at()} is
 */
public record AgentHarnessExit(
    String agentId,
    String commandId,
    String agentType,
    String status,
    Integer exitCode,
    String stoppedBy,
    Instant at)
    implements DaemonMessage {

  /** The daemon terminated the harness because the agent's worktree was removed. */
  public static final String DAEMON_REMOVE = "DAEMON_REMOVE";

  /** The daemon terminated the harness because the agent yielded. */
  public static final String DAEMON_YIELD = "DAEMON_YIELD";

  /**
   * The harness died by SIGKILL (exit 137, the OOM killer included) and the daemon did not ask for
   * it. The matching {@link AgentActivity} {@code ENDED} says which of the two it was.
   */
  public static final String KILLED = "KILLED";

  /**
   * Anything else: the harness ended on its own terms — or was terminated by somebody other than
   * the daemon's remove or yield ({@code DELETE /commands/{id}}, the daemon's own shutdown), which
   * {@code status} {@code TERMINATED} then tells apart.
   */
  public static final String SELF = "SELF";
}
