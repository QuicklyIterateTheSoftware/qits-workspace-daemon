package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.AgentBranchPushed;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Each agent's own checkout of the platform repository {@code qits-agent-config} — its memories,
 * skills and enabled plugins — on a branch named exactly like its wrapper branch (qits-1382).
 *
 * <p><b>The work tree never contains a {@code .git}.</b> The harness is handed the tree as its
 * config directory and reads and writes it freely; a {@code .git} there would be a repository the
 * agent could turn against the credential the daemon pushes with, and one Claude would mistake for
 * a project. So the git dir sits beside it: {@code <root>/<agentId>/git}, a bare clone switched to
 * {@code core.bare false}, and {@code <root>/<agentId>/tree}, reached only with {@code --git-dir}
 * and {@code --work-tree}.
 *
 * <p><b>Only three paths are ever committed</b>: {@code memory}, {@code skills} and {@code
 * settings.json}. Whatever else the harness leaves in its config directory — caches, session
 * state — stays local and dies with the checkout.
 *
 * <p>Every git call goes through {@link GitExec}; the clone, fetch and push carry the agent's own
 * token and nothing else, exactly as {@link OriginSync}'s pushes do, and a push is reported home as
 * the same {@link AgentBranchPushed}. What the agent's config is and which token it pushes with
 * live only in memory, like the start's {@code env}: after a restart, the next start says them
 * again, and until then a sync is a no-op.
 *
 * <p>A checkout that cannot be made is not a failed start: {@link #ensure} warns, removes what it
 * left behind, and the harness launches as it did before this class existed.
 */
final class AgentConfigCheckout implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(AgentConfigCheckout.class);

  /** The repository's name, for a reason in the cleanup check. */
  static final String REPOSITORY = "qits-agent-config";

  /** The only paths of the tree that are committed. */
  static final List<String> SYNCED = List.of("memory", "skills", "settings.json");

  /**
   * A {@code <projectId>/<repoName>} path. It reaches a url, so two plain segments and nothing
   * else: no scheme, no {@code ..}, no third level.
   */
  private static final Pattern REPOSITORY_PATH =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}/[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  /** A qualified work id, {@code <project>-<n>}: the commit's scope. */
  private static final Pattern QUALIFIED_ID = Pattern.compile("[A-Za-z][A-Za-z0-9]*-[0-9]+");

  /** What a start names: the repository row and its {@code <projectId>/<repoName>}. */
  record AgentConfig(String repositoryId, String repositoryPath) {

    /** The repository's name on the git host: the last segment of its path. */
    String repositoryName() {
      return repositoryPath.substring(repositoryPath.lastIndexOf('/') + 1);
    }
  }

  /** Whether {@code path} is a {@code <projectId>/<repoName>} the url may be built from. */
  static boolean validRepositoryPath(String path) {
    return path != null && REPOSITORY_PATH.matcher(path).matches();
  }

  /** What the last start told this process about an agent's checkout. */
  private record Remembered(
      AgentConfig config, String branch, Map<String, String> credential, String message) {}

  private final Path root;
  private final String gitBase;
  private final Consumer<DaemonMessage> emit;
  private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();

  /** The hook-triggered syncs, off the hook thread; one thread, so they never race each other. */
  private final ExecutorService background =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-agent-config");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * @param root where every agent's checkout lives ({@code /workspace/agent-config})
   * @param gitBase the git host's base url ({@code qits.workspace-daemon.git-base-url}); blank
   *     makes every {@link #ensure} a warning and an empty answer
   * @param emit where a push is reported ({@link AgentBranchPushed})
   */
  AgentConfigCheckout(Path root, String gitBase, Consumer<DaemonMessage> emit) {
    this.root = root;
    this.gitBase =
        gitBase == null || gitBase.isBlank() ? "" : Provisioner.trimTrailingSlash(gitBase.trim());
    this.emit = emit;
  }

  private Path agentDir(String agentId) {
    if (!AgentWorktrees.validAgentId(agentId)) {
      throw new IllegalArgumentException("Invalid agentId: " + agentId);
    }
    return root.resolve(agentId);
  }

  private Path gitDir(String agentId) {
    return agentDir(agentId).resolve("git");
  }

  private Path treeDir(String agentId) {
    return agentDir(agentId).resolve("tree");
  }

  /** The agent's work tree, when it has a checkout. */
  Optional<Path> tree(String agentId) {
    if (!AgentWorktrees.validAgentId(agentId)
        || Files.isSymbolicLink(agentDir(agentId))
        || !Files.isDirectory(gitDir(agentId), LinkOption.NOFOLLOW_LINKS)
        || !Files.isDirectory(treeDir(agentId), LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    return Optional.of(treeDir(agentId));
  }

  /**
   * Make the agent's checkout if it has none, and remember what a sync needs. An existing checkout
   * is answered as it stands, never touched: it may hold memories not committed yet.
   *
   * @param workRef the qualified work id ({@code qits-123}) the commits a sync makes are scoped
   *     by, or anything else for none
   * @return the work tree, or empty when it could not be made — the launch then goes without it
   */
  Optional<Path> ensure(
      String agentId,
      String wrapperBranch,
      AgentConfig config,
      Map<String, String> env,
      String workRef) {
    if (!AgentWorktrees.validAgentId(agentId)
        || config == null
        || !validRepositoryPath(config.repositoryPath())) {
      LOG.warnf("agent %s: no agent config checkout for %s", agentId, config);
      return Optional.empty();
    }
    Map<String, String> credential =
        AgentRuntime.credential(env)
            .map(token -> Map.of(OriginSync.AGENT_CREDENTIAL, token))
            .orElse(Map.of());
    remembered.put(
        agentId, new Remembered(config, wrapperBranch, credential, message(workRef, agentId)));
    synchronized (lock(agentId)) {
      if (Files.exists(gitDir(agentId), LinkOption.NOFOLLOW_LINKS)) {
        return tree(agentId);
      }
      if (gitBase.isEmpty()) {
        LOG.warnf(
            "agent %s: no qits.workspace-daemon.git-base-url, so no %s checkout",
            agentId, REPOSITORY);
        return Optional.empty();
      }
      String failure = checkout(agentId, wrapperBranch, url(config), credential);
      if (failure != null) {
        LOG.warnf(
            "agent %s: could not check out %s on %s, launching without it: %s",
            agentId, REPOSITORY, wrapperBranch, failure);
        AgentWorktrees.deleteTree(agentDir(agentId));
        return Optional.empty();
      }
      return tree(agentId);
    }
  }

  /** The clone, as the class javadoc lays it out. Answers why it failed, or null. */
  private String checkout(
      String agentId, String branch, String url, Map<String, String> credential) {
    Path agentDir = agentDir(agentId);
    Path git = gitDir(agentId);
    Path tree = treeDir(agentId);
    try {
      Files.createDirectories(tree);
    } catch (IOException e) {
      return e.getMessage();
    }
    GitExec.Out cloned =
        GitExec.network(agentDir, credential, "clone", "--quiet", "--bare", url, git.toString());
    if (!cloned.ok()) {
      return cloned.message();
    }
    for (String[] setting :
        List.of(
            new String[] {"core.bare", "false"},
            new String[] {"remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*"})) {
      GitExec.Out set = GitExec.git(agentDir, "--git-dir=" + git, "config", setting[0], setting[1]);
      if (!set.ok()) {
        return set.message();
      }
    }
    GitExec.Out fetched =
        GitExec.network(agentDir, credential, "--git-dir=" + git, "fetch", "--quiet", "origin");
    if (!fetched.ok()) {
      return fetched.message();
    }
    GitExec.Out checkedOut;
    if (hasRef(git, "refs/remotes/origin/" + branch)) {
      checkedOut = inTree(agentId, "checkout", "--quiet", "-B", branch, "origin/" + branch);
    } else if (hasRef(git, "refs/remotes/origin/main")) {
      checkedOut =
          inTree(agentId, "checkout", "--quiet", "--no-track", "-B", branch, "origin/main");
    } else {
      // An empty repository: the branch is born with the first sync's commit.
      checkedOut = inTree(agentId, "symbolic-ref", "HEAD", "refs/heads/" + branch);
    }
    return checkedOut.ok() ? null : checkedOut.message();
  }

  /** {@code <gitBase>/<projectId>/<repoName>}, as {@link Provisioner#rootUrl} builds the wrapper. */
  private String url(AgentConfig config) {
    return gitBase + "/" + config.repositoryPath();
  }

  /**
   * Commit the three synced paths and push the branch when it has commits the git host lacks.
   * Never throws: a sync that fails leaves the work where it is, and the cleanup check reports it.
   */
  void sync(String agentId) {
    Remembered agent = AgentWorktrees.validAgentId(agentId) ? remembered.get(agentId) : null;
    if (agent == null) {
      LOG.debugf("agent %s: no agent config remembered, nothing to sync", agentId);
      return;
    }
    synchronized (lock(agentId)) {
      if (tree(agentId).isEmpty()) {
        return;
      }
      try {
        commit(agentId, agent);
        push(agentId, agent);
      } catch (RuntimeException e) {
        LOG.warnf(e, "agent %s: syncing %s failed", agentId, REPOSITORY);
      }
    }
  }

  /** {@link #sync}, on this class's own thread: for a hook, which must not wait on the network. */
  void syncLater(String agentId) {
    try {
      background.execute(() -> sync(agentId));
    } catch (RejectedExecutionException shuttingDown) {
      LOG.debug("agent config sync is shut down; request dropped");
    }
  }

  private void commit(String agentId, Remembered agent) {
    List<String> paths = present(agentId);
    if (paths.isEmpty()) {
      return;
    }
    List<String> add = new ArrayList<>(List.of("add", "-A", "--"));
    add.addAll(paths);
    GitExec.Out added = inTree(agentId, add.toArray(String[]::new));
    if (!added.ok()) {
      LOG.warnf("agent %s: staging %s failed: %s", agentId, REPOSITORY, added.message());
      return;
    }
    if (inTree(agentId, "diff", "--cached", "--quiet").exit() != 1) {
      return;
    }
    String name = identity("GIT_AUTHOR_NAME", "qits workspace daemon");
    String email = identity("GIT_AUTHOR_EMAIL", "workspace-daemon@qits.invalid");
    GitExec.Out committed =
        inTree(
            agentId,
            "-c",
            "user.name=" + name,
            "-c",
            "user.email=" + email,
            "-c",
            "commit.gpgsign=false",
            "commit",
            "--quiet",
            "-m",
            agent.message());
    if (!committed.ok()) {
      LOG.warnf("agent %s: committing %s failed: %s", agentId, REPOSITORY, committed.message());
    }
  }

  private void push(String agentId, Remembered agent) {
    if (ahead(agentId, agent.branch()) == 0) {
      return;
    }
    Path git = gitDir(agentId);
    List<String> tampered = GitConfigGuard.violations(git, url(agent.config()));
    if (!tampered.isEmpty()) {
      LOG.warnf("agent %s: not pushing %s, its config carries %s", agentId, REPOSITORY, tampered);
      return;
    }
    String ref = "refs/heads/" + agent.branch();
    GitExec.Out pushed =
        GitExec.network(
            agentDir(agentId),
            agent.credential(),
            "--git-dir=" + git,
            "push",
            "--quiet",
            "origin",
            ref + ":" + ref);
    if (!pushed.ok()) {
      LOG.infof("agent %s: pushing %s failed: %s", agentId, REPOSITORY, pushed.message());
      return;
    }
    GitExec.Out sha = GitExec.git(agentDir(agentId), "--git-dir=" + git, "rev-parse", ref);
    // By name, as every other push is: the host resolves it within the agent's project.
    emit.accept(
        new AgentBranchPushed(
            agentId, agent.config().repositoryName(), agent.branch(), sha.line()));
  }

  /**
   * Whether removing the checkout would lose anything: a change under the three synced paths, or a
   * commit the git host lacks. False when there is no checkout.
   */
  boolean dirty(String agentId) {
    if (tree(agentId).isEmpty()) {
      return false;
    }
    synchronized (lock(agentId)) {
      List<String> status =
          new ArrayList<>(List.of("--no-optional-locks", "status", "--porcelain", "-uall", "--"));
      status.addAll(SYNCED);
      GitExec.Out out = inTree(agentId, status.toArray(String[]::new));
      if (out.ok() && !out.stdout().isBlank()) {
        return true;
      }
      GitExec.Out head = inTree(agentId, "symbolic-ref", "--quiet", "--short", "HEAD");
      return head.ok() && ahead(agentId, head.line()) > 0;
    }
  }

  /** Delete the agent's checkout, work tree and git dir alike, and forget it. */
  void remove(String agentId) {
    if (!AgentWorktrees.validAgentId(agentId)) {
      return;
    }
    synchronized (lock(agentId)) {
      AgentWorktrees.deleteTree(agentDir(agentId));
      remembered.remove(agentId);
    }
  }

  @Override
  public void close() {
    background.shutdownNow();
  }

  /** Commits on {@code branch} that no {@code origin/*} ref has; 0 for an unborn branch. */
  private int ahead(String agentId, String branch) {
    GitExec.Out count =
        inTree(agentId, "rev-list", "--count", "refs/heads/" + branch, "--not", "--remotes=origin");
    try {
      return count.ok() ? Integer.parseInt(count.line()) : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * The synced paths there is something to stage for: on disk, or tracked and deleted. {@code git
   * add} refuses a pathspec that matches nothing, and a new checkout has none of them yet.
   */
  private List<String> present(String agentId) {
    Path tree = treeDir(agentId);
    List<String> out = new ArrayList<>();
    for (String path : SYNCED) {
      if (Files.exists(tree.resolve(path), LinkOption.NOFOLLOW_LINKS)
          || !inTree(agentId, "ls-files", "--", path).line().isEmpty()) {
        out.add(path);
      }
    }
    return out;
  }

  /** A local git call on the checkout: the git dir beside, the tree as the work tree and cwd. */
  private GitExec.Out inTree(String agentId, String... args) {
    List<String> argv = new ArrayList<>(args.length + 2);
    argv.add("--git-dir=" + gitDir(agentId));
    argv.add("--work-tree=" + treeDir(agentId));
    argv.addAll(List.of(args));
    return GitExec.git(treeDir(agentId), argv.toArray(String[]::new));
  }

  private static boolean hasRef(Path git, String ref) {
    return GitExec.git(git, "--git-dir=" + git, "rev-parse", "--verify", "--quiet", ref).ok();
  }

  private Object lock(String agentId) {
    return locks.computeIfAbsent(agentId, id -> new Object());
  }

  /** Whether {@code workRef} is a qualified work id, {@code <project>-<n>}. */
  static boolean qualified(String workRef) {
    return workRef != null && QUALIFIED_ID.matcher(workRef).matches();
  }

  /**
   * The subject of a sync's commit: scoped by the qualified work id when the start carried one,
   * else naming the agent.
   */
  static String message(String workRef, String agentId) {
    if (qualified(workRef)) {
      return "chore(" + workRef + "): agent memories, skills and plugins";
    }
    return "chore(agent-config): " + agentId;
  }

  private static String identity(String variable, String fallback) {
    String value = System.getenv(variable);
    return value == null || value.isBlank() ? fallback : value;
  }
}
