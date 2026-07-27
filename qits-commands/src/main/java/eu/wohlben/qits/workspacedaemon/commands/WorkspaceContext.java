package eu.wohlben.qits.workspacedaemon.commands;

/**
 * Who this workspace is, for the commands that run in it.
 *
 * <p>On the host every launch carried {@code repoId} and {@code workspaceId} as arguments and then
 * spent real work turning them back into something usable: a slug pattern check against path
 * traversal (the ids reached a container name and a git invocation), a
 * {@code workspaceRepository.findActiveByRepositoryAndWorkspaceId} in its own transaction to read
 * the branch, a {@code workspaceService.ensureContainer} to make sure the target existed, a
 * {@code containers.containerName} to address it, and a {@code docker exec git rev-parse HEAD} to
 * learn the commit. All five were consequences of launching into a workspace from outside it.
 *
 * <p>Inside the container none of them are questions. The daemon was told which workspace it is at
 * provisioning, the checkout is its own working directory, and it already watches HEAD — {@code
 * GitStatusMonitor} reports {@code head} on every change, which is where {@link #commitHash} comes
 * from rather than a fresh git process per launch. The traversal check goes with them: there is no
 * id being interpolated into a path any more.
 *
 * <p>Implemented by the daemon module; every method is read at launch time, so a workspace that
 * changes branch mid-session is reflected on the next command rather than being snapshotted here.
 */
public interface WorkspaceContext {

  /** The repository this workspace belongs to. Recorded on each command for the host's benefit. */
  String repoId();

  /** This workspace's id. */
  String workspaceId();

  /** The branch currently checked out. */
  String branch();

  /**
   * The commit currently checked out, or null if it is not known yet (a workspace whose first git
   * read has not landed). Null is recorded as-is — the host did the same when its {@code rev-parse}
   * failed.
   */
  String commitHash();
}
