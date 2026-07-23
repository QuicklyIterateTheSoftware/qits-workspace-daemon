package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: {@code /workspace} has been checked out (and its submodules
 * materialized) from the daemon's own boot-time env — the autonomous self-clone succeeded. Carries
 * the resulting {@code HEAD} sha (parity with the host's old post-clone {@code git rev-parse}). The
 * host awaits this to settle the {@code clone} process segment and mark the workspace {@code
 * RUNNING}. See docs/epics/qits-workspace-daemon/features/ (autonomous self-clone on boot).
 */
public record Provisioned(String workspaceId, String head) implements DaemonMessage {}
