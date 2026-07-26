package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: a workspace service's lifecycle transition, the driver the host
 * projects into its display {@code ServiceStatus} state machine (SSE, the {@code service:<name>}
 * process segment, the web-view proxy origin). {@code id} is the service name; {@code state} is one
 * of {@link State}. Because the in-container supervisor (PID 1) owns the process lifecycle —
 * spawn/restart/backoff/policy/group-kill — it reports the *decision outcome* ({@code RESTARTING} /
 * {@code CRASHED} / {@code STOPPED}), never a raw exit the host would have to policy-evaluate;
 * there is deliberately no {@code EXITED} state. {@code exitCode} is nullable, present only on
 * {@code CRASHED}/{@code STOPPED} after an exit. {@code DEGRADED} is host-derived from streamed
 * output (log observers), so it is not a wire state. On socket reconnect the daemon re-reports the
 * current state of every running service (replacing the old tmux adoption probe).
 * docs/epics/qits-workspace-daemon/ Part 4.
 */
public record ServiceTransition(String workspaceId, String id, String state, Integer exitCode)
    implements DaemonMessage {

  /** The {@link #state()} values (a subset of the host {@code ServiceStatus}, minus DEGRADED). */
  public static final class State {
    public static final String STARTING = "STARTING";
    public static final String READY = "READY";
    public static final String RESTARTING = "RESTARTING";
    public static final String CRASHED = "CRASHED";
    public static final String STOPPED = "STOPPED";

    private State() {}
  }
}
