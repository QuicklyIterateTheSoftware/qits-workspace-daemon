package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.commands.CheckoutContext;

/**
 * Who this workspace is, for the commands that run in it — this daemon's own half of {@link
 * CheckoutContext}.
 *
 * <p><b>It used to be the whole interface, and it lived in the shared module.</b> Two copies of that
 * module existed, and each declared its own: this one answered four questions about a workspace
 * (repository id, workspace id, branch, commit), the projects daemon's {@code ProjectContext}
 * answered four about a project (project id, wrapper repository, branch, commit). Only two of the
 * eight were the same question, and only those two were ever read by the shared code — a command
 * records the branch and commit it ran at. The other two were read here, by this module's own
 * response bodies and its own MCP narrowing.
 *
 * <p>So the split is exactly that: {@link CheckoutContext} keeps what the library asks, and the two
 * ids a workspace has beyond it stay here, where the only readers are {@link CommandJson}, {@link
 * AgentJson} and {@link WorkspaceMcpServers}. That is what lets the library stop being able to tell
 * a workspace container from a project one, which is the point of unifying it.
 *
 * <p>On the host every launch carried {@code repoId} and {@code workspaceId} as arguments and then
 * spent real work turning them back into something usable: a slug pattern check against path
 * traversal (the ids reached a container name and a git invocation), a {@code
 * workspaceRepository.findActiveByRepositoryAndWorkspaceId} in its own transaction to read the
 * branch, a {@code workspaceService.ensureContainer} to make sure the target existed, a {@code
 * containers.containerName} to address it, and a {@code docker exec git rev-parse HEAD} to learn the
 * commit. All five were consequences of launching into a workspace from outside it.
 *
 * <p>Inside the container none of them are questions. The daemon was told which workspace it is at
 * provisioning, the checkout is its own working directory, and it already watches HEAD — {@code
 * GitStatusMonitor} reports {@code head} on every change, which is where {@link #commitHash} comes
 * from rather than a fresh git process per launch. The traversal check goes with them: there is no
 * id being interpolated into a path any more.
 *
 * <p>Implemented by {@link DaemonWorkspaceContext}; every method is read at launch time, so a
 * workspace that changes branch mid-session is reflected on the next command rather than being
 * snapshotted here.
 */
interface WorkspaceContext extends CheckoutContext {

  /** The repository this workspace belongs to. Recorded on each command for the host's benefit. */
  String repoId();

  /** This workspace's id. */
  String workspaceId();
}
