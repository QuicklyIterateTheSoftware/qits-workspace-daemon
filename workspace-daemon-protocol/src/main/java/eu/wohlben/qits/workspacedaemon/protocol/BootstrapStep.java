package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: a bootstrap chain step is starting. Sent as the daemon runs the
 * chain (install / migrate / seed) from its in-container {@code .qits-config.yml}, autonomously,
 * between the self-clone and daemon start (docs/epics/qits-workspace-daemon/ Part 3). {@code phase}
 * is one of {@code CHECK} (running the optional skip-guard), {@code EXECUTE} (running the command),
 * or {@code SKIP} (the check returned non-zero, the command is skipped). The host opens/settles a
 * {@code bootstrap:<name>} process segment off these; the step's live output arrives as {@link
 * CommandChunk}s tagged with the {@link DaemonProtocol#bootstrapCorrelationId(String)} for the same
 * {@code name}.
 */
public record BootstrapStep(String workspaceId, String name, String phase)
    implements DaemonMessage {

  /** The {@link #phase()} values. */
  public static final class Phase {
    public static final String CHECK = "CHECK";
    public static final String EXECUTE = "EXECUTE";
    public static final String SKIP = "SKIP";

    private Phase() {}
  }
}
