package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.commands.WorkspaceContext;
import java.util.function.Supplier;

/**
 * Answers {@link WorkspaceContext} from what the daemon already knows.
 *
 * <p>Every value here was a lookup on the host. {@code repoId} and {@code workspaceId} came off the
 * command's {@code Workspace} relation; the branch came from a database read in its own transaction
 * (a launch could arrive on a non-request thread, so Panache needed one); the commit came from a
 * {@code docker exec git rev-parse HEAD} spawned per launch. In the container the first three are
 * the identity the host injected at container creation, and the fourth is already being watched —
 * {@link GitStatusMonitor} reports HEAD on every working-tree change, so a launch reads the value
 * the host was told rather than forking git again.
 *
 * <p>The branch and commit are suppliers, not fields: a workspace can change branch mid-session (an
 * agent runs {@code git switch}), and a command must record what was actually checked out when it
 * launched. The monitor's value can be null before its first report — a workspace whose boot-time
 * git read has not landed — and that is passed through rather than defaulted, exactly as the host
 * recorded null when its {@code rev-parse} failed.
 */
final class DaemonWorkspaceContext implements WorkspaceContext {

  private final String repoId;
  private final String workspaceId;
  private final Supplier<String> branch;
  private final Supplier<String> commitHash;

  DaemonWorkspaceContext(
      String repoId, String workspaceId, Supplier<String> branch, Supplier<String> commitHash) {
    this.repoId = repoId;
    this.workspaceId = workspaceId;
    this.branch = branch;
    this.commitHash = commitHash;
  }

  @Override
  public String repoId() {
    return repoId;
  }

  @Override
  public String workspaceId() {
    return workspaceId;
  }

  @Override
  public String branch() {
    return branch.get();
  }

  @Override
  public String commitHash() {
    return commitHash.get();
  }
}
