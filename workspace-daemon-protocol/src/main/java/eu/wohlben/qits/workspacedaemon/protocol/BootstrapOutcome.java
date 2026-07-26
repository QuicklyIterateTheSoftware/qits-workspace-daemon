package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: the terminal result of one bootstrap chain step. {@code outcome}
 * uses the exact vocabulary of the host's {@code bootstrap.entity.BootstrapOutcome} enum — {@code
 * SKIPPED} (the {@code check} guard returned non-zero, the command never ran), {@code SUCCEEDED}
 * (execute exited 0), or {@code FAILED} (execute exited non-zero or was terminated on timeout).
 * {@code exitCode} is the execute's exit (or the check's, for a {@code SKIPPED} step). The host
 * upserts a {@code BootstrapRun} row from this; a {@code FAILED} step aborts the rest of the chain
 * (see {@link Bootstrapped}). docs/epics/qits-workspace-daemon/ Part 3.
 */
public record BootstrapOutcome(String workspaceId, String name, String outcome, int exitCode)
    implements DaemonMessage {

  /** The {@link #outcome()} values (mirroring the host {@code BootstrapOutcome} enum). */
  public static final class Result {
    public static final String SKIPPED = "SKIPPED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    private Result() {}
  }
}
