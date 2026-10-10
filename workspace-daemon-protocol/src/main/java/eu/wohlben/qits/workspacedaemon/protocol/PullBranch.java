package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * qits → {@code workspace-daemon}: a ref moved on the git host, for example a release merged into
 * {@code main} or a branch was deleted.
 *
 * <p>Since capability 10 (qits-1152) this is only a hint. A workspace no longer has one checkout
 * branch to pull: it keeps a base clone that agents make their worktrees from, and the base clone's
 * working tree is never moved. So the daemon answers any {@code PullBranch} with a {@code git fetch
 * --prune} of the base clone and its submodules, ahead of its periodic fetch. {@code branch} is
 * read for logging only. Before 10 the daemon fetched and fast-forwarded its checkout onto {@code
 * branch}.
 *
 * <p>Not a request/reply round-trip: {@code correlationId} is carried for tracing, and no {@link
 * Ack} is expected.
 */
public record PullBranch(String correlationId, String branch) implements DaemonMessage {}
