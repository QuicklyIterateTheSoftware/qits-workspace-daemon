package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * The workspace's in-container working-tree cleanliness, pushed <em>unsolicited</em> from {@code
 * workspace-daemon} to qits: once on boot, once on every socket (re)connect, and again whenever the
 * daemon's file watcher observes the working-tree marker move. Unlike {@link WorkspaceInfo} (the
 * FIFO reply to a {@link Describe}), this frame is not correlated to any request — the backend
 * caches {@code clean} per workspace and drives the dirty badge + the {@code files} refresh from
 * it.
 *
 * <p>{@code clean} is {@code true} when {@code git status --porcelain} is empty; {@code head} is
 * the current {@code HEAD} oid (blank on an unborn branch / unreadable tree).
 *
 * <p>Not sent since capability 10 (qits-1152): a workspace has no single checkout to watch. Each
 * agent worktree's state is answered by the daemon's {@code GET agent-worktrees} instead. Kept so
 * a host can still decode a frame from an older daemon.
 */
public record GitStatus(String workspaceId, boolean clean, String head) implements DaemonMessage {}
