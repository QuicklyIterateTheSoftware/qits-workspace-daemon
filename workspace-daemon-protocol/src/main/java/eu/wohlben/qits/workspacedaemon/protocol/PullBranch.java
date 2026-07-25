package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * qits → {@code workspace-daemon}: an incoming merge/integration advanced this workspace's branch
 * on origin out-of-band (a host-side {@code mergeIntoTarget} into it), so pull it into the
 * container's checkout — {@code git fetch origin <branch>} + {@code git merge --ff-only
 * origin/<branch>}
 * (docs/epics/qits-workspace-daemon/features/2026-07-25_daemon-bidirectional-auto-sync.md). The
 * host only sends this to the workspace that <em>owns</em> {@code branch}, so it is always the
 * daemon's own checkout branch; the daemon validates it and refuses anything but a fast-forward
 * (never a force), so a working tree that turned dirty in the tiny window since the host's
 * clean-gate is left untouched rather than clobbered — the accepted-risk path, reconciled by the
 * next host git op.
 *
 * <p>Not a request/reply round-trip: the daemon's own {@link GitStatus} watch re-reports the new
 * {@code HEAD} once the fast-forward moves the tree, so {@code correlationId} is carried for
 * symmetry/tracing but no {@link Ack} is expected.
 */
public record PullBranch(String correlationId, String branch) implements DaemonMessage {}
