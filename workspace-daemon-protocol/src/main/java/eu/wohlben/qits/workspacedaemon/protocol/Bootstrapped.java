package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: the bootstrap chain has finished (mirrors {@link Provisioned} as
 * the terminal of the bootstrap phase). {@code ok} is true when every step succeeded or was
 * skipped, false when a step failed (the chain aborted). The host completes its pending-bootstrap
 * await on this: {@code ok:true} lets the workspace proceed to daemon auto-start ({@code
 * WorkspaceReadyForServices}), {@code ok:false} gates services off — a dev server on an
 * unbootstrapped checkout would only crash-loop. An empty chain (no {@code bootstrap:} entries, or
 * the autorun kill-switch off) reports {@code ok:true} immediately.
 * docs/epics/qits-workspace-daemon/ Part 3.
 */
public record Bootstrapped(String workspaceId, boolean ok) implements DaemonMessage {}
