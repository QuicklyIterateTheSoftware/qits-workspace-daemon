package eu.wohlben.qits.workspacedaemon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

/**
 * The base clone and the agent worktrees made from it (qits-1152, D1, D6, D22).
 *
 * <p>A workspace keeps one regular clone of its wrapper at {@code /workspace/base}, with every
 * submodule materialized ({@link Provisioner}). An agent never works there. It gets its own agent
 * worktree at {@code /workspace/agents/<agentId>/<wrapperName>}, made in two steps, because {@code
 * git worktree add} on the wrapper leaves the submodule paths empty:
 *
 * <ol>
 *   <li>the wrapper: a worktree of the base clone on the local branch {@code <wrapperBranch>},
 *       started from {@code origin/<wrapperBranch>} when the git host has it, else from the
 *       wrapper's {@code origin/main};
 *   <li>each submodule {@code <p>}: a <b>detached</b> worktree of {@code /workspace/base/<p>} at
 *       its {@code origin/main}, placed at the wrapper worktree's {@code <p>}.
 * </ol>
 *
 * <p>Objects are shared and nothing is fetched again. A submodule commit shows as a moved gitlink
 * in the agent's wrapper worktree, and the base clone stays clean. A branch can be checked out in
 * one worktree only, so two agents can never share a branch; one agent per work item matches that.
 *
 * <p>Framework-free, and every git command names its directory, so the unit suite drives it over a
 * temporary base and agents root.
 */
final class AgentWorktrees {

  private static final Logger LOG = Logger.getLogger(AgentWorktrees.class);

  /**
   * An agent id reaches a path, so it is held to a safe shape: no separator, no {@code ..}, no
   * leading dash. The service hands out UUIDs, which fit.
   */
  private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  /**
   * One repository of the base clone: the wrapper ({@code path} empty) or a submodule. {@code
   * originUrl} is the origin the daemon set, read before any agent existed; {@link GitConfigGuard}
   * refuses to talk to the git host once it has changed.
   */
  record Repo(String path, String name, String defaultBranch, String originUrl) {
    boolean wrapper() {
      return path.isEmpty();
    }
  }

  /** One repository of an agent worktree as it stands now. */
  record RepoState(
      String repository,
      String path,
      String branch,
      String head,
      boolean dirty,
      int unpushedCommits,
      boolean pushed) {}

  /** What {@link #cleanupCheck} found. */
  record Leftover(String repository, String branch, int commits) {}

  record CleanupCheck(List<String> dirty, List<Leftover> unpushed) {
    boolean clean() {
      return dirty.isEmpty() && unpushed.isEmpty();
    }
  }

  private final Path base;
  private final Path agentsRoot;
  private final String wrapperName;

  /** The base clone's repositories, wrapper first; read on demand and kept until a refresh. */
  private volatile List<Repo> repositories;

  /**
   * @param base the base clone ({@code /workspace/base})
   * @param agentsRoot where agent worktrees live ({@code /workspace/agents})
   * @param wrapperName the wrapper's repository name; the wrapper worktree's directory name and the
   *     {@code repository} reported for it
   */
  AgentWorktrees(Path base, Path agentsRoot, String wrapperName) {
    this.base = base;
    this.agentsRoot = agentsRoot;
    this.wrapperName = wrapperName;
  }

  Path base() {
    return base;
  }

  Path agentsRoot() {
    return agentsRoot;
  }

  String wrapperName() {
    return wrapperName;
  }

  /** Whether {@code agentId} is safe to use as a directory name. */
  static boolean validAgentId(String agentId) {
    return agentId != null && AGENT_ID.matcher(agentId).matches();
  }

  Path agentDir(String agentId) {
    requireAgentId(agentId);
    return agentsRoot.resolve(agentId);
  }

  /** The agent's wrapper worktree: the harness's working directory, and the Files root. */
  Path wrapperDir(String agentId) {
    return agentDir(agentId).resolve(wrapperName);
  }

  /** Whether the agent's wrapper worktree exists. */
  boolean exists(String agentId) {
    return validAgentId(agentId)
        && contained(agentId)
        && Files.exists(wrapperDir(agentId).resolve(".git"), LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * Whether the agent's directory and wrapper worktree are where they should be: real directories,
   * not symbolic links, under the agents root. Agents share the OS user and could replace either
   * with a link to somewhere else; every route that reads or removes an agent's files asks this.
   */
  boolean contained(String agentId) {
    Path agentDir = agentDir(agentId);
    Path wrapper = wrapperDir(agentId);
    if (Files.isSymbolicLink(agentDir) || Files.isSymbolicLink(wrapper)) {
      return false;
    }
    if (!Files.isDirectory(wrapper, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try {
      Path root = agentsRoot.toRealPath();
      return wrapper.toRealPath().equals(root.resolve(agentId).resolve(wrapperName));
    } catch (IOException e) {
      return false;
    }
  }

  /** The agents that have a directory under the agents root, whatever this process remembers. */
  List<String> agentIds() {
    if (!Files.isDirectory(agentsRoot)) {
      return List.of();
    }
    try (Stream<Path> children = Files.list(agentsRoot)) {
      return children
          .filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .filter(AgentWorktrees::validAgentId)
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The base clone's repositories: the wrapper, then every materialized submodule, a parent before
   * its children. Read once and cached; {@link #refresh} re-reads it.
   */
  List<Repo> repositories() {
    List<Repo> current = repositories;
    if (current == null) {
      current = readRepositories();
      repositories = current;
    }
    return current;
  }

  /** Forget the cached repository list, so the next read sees the base clone as it is now. */
  void refresh() {
    repositories = null;
  }

  private List<Repo> readRepositories() {
    List<Repo> out = new ArrayList<>();
    out.add(new Repo("", wrapperName, defaultBranch(base), originUrl(base)));
    GitExec.Out status = GitExec.git(base, "submodule", "status", "--recursive");
    List<String> paths = new ArrayList<>();
    for (String raw : status.stdout().split("\n")) {
      // " <sha> <path> (<describe>)"; a leading '-' marks a submodule that is not initialized.
      if (raw.isBlank() || raw.charAt(0) == '-') {
        continue;
      }
      String[] parts = raw.substring(1).strip().split(" ");
      if (parts.length >= 2 && confined(parts[1])) {
        paths.add(parts[1]);
      }
    }
    paths.sort(
        Comparator.comparingInt((String p) -> p.split("/").length)
            .thenComparing(Comparator.naturalOrder()));
    for (String path : paths) {
      Path dir = base.resolve(path);
      String url = originUrl(dir);
      String name = url == null ? path : Provisioner.basename(url);
      out.add(new Repo(path, name, defaultBranch(dir), url));
    }
    return List.copyOf(out);
  }

  /**
   * The configured origin, as written — not {@code remote get-url}, which applies {@code insteadOf}
   * rewrites a planted config could add.
   */
  private static String originUrl(Path repository) {
    GitExec.Out url = GitExec.git(repository, "config", "--get", "remote.origin.url");
    return url.ok() && !url.line().isEmpty() ? url.line() : null;
  }

  /**
   * Whether a submodule path stays inside the worktree it is resolved against: relative, no {@code
   * ..} segment. It comes from the wrapper's {@code .gitmodules}.
   */
  static boolean confined(String path) {
    if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("-")) {
      return false;
    }
    for (String segment : path.split("/")) {
      if (segment.isEmpty() || segment.equals("..") || segment.equals(".")) {
        return false;
      }
    }
    return true;
  }

  /** {@code origin/HEAD}'s branch, else {@code main}. */
  static String defaultBranch(Path repository) {
    GitExec.Out head =
        GitExec.git(repository, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD");
    String line = head.line();
    if (head.ok() && line.startsWith("origin/") && line.length() > "origin/".length()) {
      return line.substring("origin/".length());
    }
    return "main";
  }

  /** Install the commit guard (D23) in the base clone and every submodule. */
  void installGuards() {
    for (Repo repo : repositories()) {
      CommitGuard.install(dirOf(repo), repo.defaultBranch(), agentsRoot, wrapperName);
    }
  }

  /** The base clone's checkout of {@code repo}, where its git dir and worktrees are managed. */
  Path dirOf(Repo repo) {
    return repo.wrapper() ? base : base.resolve(repo.path());
  }

  /**
   * Create the agent's worktrees where they are missing, and answer the wrapper worktree.
   * Idempotent: an existing worktree is left as it stands, because it may hold work.
   *
   * @throws AgentWorktreeException when the wrapper worktree cannot be made; a submodule that
   *     cannot is skipped with a warning
   */
  synchronized Path ensure(String agentId, String wrapperBranch) {
    requireAgentId(agentId);
    requireBranch(wrapperBranch);
    Path dir = wrapperDir(agentId);
    List<Repo> repos = repositories();
    Repo wrapper = repos.get(0);
    if (wrapperBranch.equals(wrapper.defaultBranch())) {
      throw new AgentWorktreeException(
          "wrapperBranch must not be the default branch '" + wrapper.defaultBranch() + "'");
    }
    try {
      Files.createDirectories(agentsRoot);
      if (Files.isSymbolicLink(dir.getParent()) || Files.isSymbolicLink(dir)) {
        throw new AgentWorktreeException(409, "the agent's directory is a symbolic link");
      }
      Files.createDirectories(dir.getParent());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
      addWrapper(dir, wrapperBranch, wrapper);
    }
    for (Repo repo : repos.subList(1, repos.size())) {
      addSubmodule(dir, repo);
    }
    return dir;
  }

  private void addWrapper(Path dir, String wrapperBranch, Repo wrapper) {
    GitExec.git(base, "worktree", "prune");
    // Best effort: the periodic fetch may not have seen a branch pushed a moment ago. Absent on the
    // remote is the ordinary case and fails this quietly. The workspace's own credential, and only
    // from a config nobody tampered with.
    List<String> tampered = GitConfigGuard.violations(base, wrapper.originUrl());
    if (tampered.isEmpty()) {
      GitExec.network(
          base,
          GitExec.workspaceCredential(),
          "fetch",
          "--quiet",
          "origin",
          "+refs/heads/" + wrapperBranch + ":refs/remotes/origin/" + wrapperBranch);
    } else {
      LOG.warnf("Not fetching %s: the base clone's config carries %s", wrapperBranch, tampered);
    }
    GitExec.Out added;
    if (hasRef(base, "refs/heads/" + wrapperBranch)) {
      added = GitExec.git(base, "worktree", "add", dir.toString(), wrapperBranch);
    } else if (hasRef(base, "refs/remotes/origin/" + wrapperBranch)) {
      added =
          GitExec.git(
              base,
              "worktree",
              "add",
              "--track",
              "-b",
              wrapperBranch,
              dir.toString(),
              "origin/" + wrapperBranch);
    } else {
      added =
          GitExec.git(
              base,
              "worktree",
              "add",
              "--no-track",
              "-b",
              wrapperBranch,
              dir.toString(),
              startPoint(base, wrapper));
    }
    if (!added.ok()) {
      throw new AgentWorktreeException(
          409,
          "could not create the wrapper worktree on " + wrapperBranch + ": " + added.message());
    }
  }

  /**
   * A submodule worktree, detached at the submodule's {@code origin/main} (D6). Only into an empty
   * directory the wrapper worktree already has: a missing one means the wrapper's branch does not
   * carry that submodule, and a non-empty one is somebody's files.
   */
  private void addSubmodule(Path wrapperDir, Repo repo) {
    Path target = wrapperDir.resolve(repo.path());
    if (Files.exists(target.resolve(".git"))) {
      return;
    }
    if (!Files.isDirectory(target) || !isEmpty(target)) {
      LOG.debugf(
          "No worktree for submodule %s at %s: not an empty gitlink directory", repo, target);
      return;
    }
    Path repository = dirOf(repo);
    GitExec.git(repository, "worktree", "prune");
    GitExec.Out added =
        GitExec.git(
            repository,
            "worktree",
            "add",
            "--detach",
            target.toString(),
            startPoint(repository, repo));
    if (!added.ok()) {
      LOG.warnf("No worktree for submodule %s at %s: %s", repo.path(), target, added.message());
    }
  }

  /** {@code origin/<default>}, or the checkout's own HEAD when the remote ref is missing. */
  private static String startPoint(Path repository, Repo repo) {
    String remote = "refs/remotes/origin/" + repo.defaultBranch();
    return hasRef(repository, remote) ? remote : "HEAD";
  }

  /** Every repository of the agent's worktree as it stands now. */
  List<RepoState> status(String agentId) {
    Path dir = wrapperDir(agentId);
    List<RepoState> out = new ArrayList<>();
    for (Repo repo : repositories()) {
      Path worktree = repo.wrapper() ? dir : dir.resolve(repo.path());
      if (!Files.exists(worktree.resolve(".git"))) {
        continue;
      }
      out.add(state(repo, worktree));
    }
    return out;
  }

  private static RepoState state(Repo repo, Path worktree) {
    // Submodules are ignored here: each one is a worktree of its own and gets its own entry, and in
    // the wrapper a moved gitlink is the expected trace of submodule work, not leftover work.
    GitExec.Out status =
        GitExec.git(
            worktree,
            "--no-optional-locks",
            "status",
            "--porcelain=v2",
            "--branch",
            "--ignore-submodules=all");
    String branch = null;
    String head = "";
    boolean dirty = false;
    for (String line : status.stdout().split("\n")) {
      if (line.startsWith("# branch.oid ")) {
        head = line.substring("# branch.oid ".length()).strip();
      } else if (line.startsWith("# branch.head ")) {
        String name = line.substring("# branch.head ".length()).strip();
        branch = "(detached)".equals(name) ? null : name;
      } else if (!line.isBlank() && !line.startsWith("#")) {
        dirty = true;
      }
    }
    if ("(initial)".equals(head)) {
      head = "";
    }
    int unpushed =
        count(GitExec.git(worktree, "rev-list", "--count", "HEAD", "--not", "--remotes=origin"));
    boolean pushed = false;
    if (branch != null) {
      GitExec.Out remote =
          GitExec.git(
              worktree, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/" + branch);
      pushed = remote.ok() && remote.line().equals(head);
    }
    return new RepoState(repo.name(), repo.path(), branch, head, dirty, unpushed, pushed);
  }

  private static int count(GitExec.Out out) {
    if (!out.ok()) {
      return 0;
    }
    try {
      return Integer.parseInt(out.line());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Uncommitted changes and commits no remote branch has (D5). A commit left on a detached HEAD is
   * unpushed work too, reported with no branch (D22).
   */
  CleanupCheck cleanupCheck(String agentId) {
    List<String> dirty = new ArrayList<>();
    List<Leftover> unpushed = new ArrayList<>();
    for (RepoState state : status(agentId)) {
      if (state.dirty()) {
        dirty.add(state.repository());
      }
      if (state.unpushedCommits() > 0) {
        unpushed.add(new Leftover(state.repository(), state.branch(), state.unpushedCommits()));
      }
    }
    return new CleanupCheck(List.copyOf(dirty), List.copyOf(unpushed));
  }

  /**
   * Remove the agent's worktrees, submodules first, delete the local branches they were on, and
   * delete the agent's directory. The caller decides whether the work in them may go.
   */
  synchronized void remove(String agentId) {
    Path dir = wrapperDir(agentId);
    List<Repo> repos = new ArrayList<>(repositories());
    // Deepest first: a worktree is removed before the worktree that contains it.
    repos.sort(
        Comparator.comparingInt((Repo r) -> r.path().isEmpty() ? 0 : r.path().split("/").length)
            .reversed());
    for (Repo repo : repos) {
      Path worktree = repo.wrapper() ? dir : dir.resolve(repo.path());
      Path repository = dirOf(repo);
      if (Files.exists(worktree.resolve(".git"))) {
        GitExec.Out branch = GitExec.git(worktree, "symbolic-ref", "--quiet", "--short", "HEAD");
        GitExec.Out removed =
            GitExec.git(repository, "worktree", "remove", "--force", worktree.toString());
        if (!removed.ok()) {
          // git refuses to remove a worktree that still holds submodules; the directory goes by
          // hand and the prune below drops git's record of it.
          deleteTree(worktree);
        }
        if (branch.ok()
            && !branch.line().isEmpty()
            && !branch.line().equals(repo.defaultBranch())) {
          GitExec.git(repository, "branch", "-D", branch.line());
        }
      }
      GitExec.git(repository, "worktree", "prune");
    }
    deleteTree(agentDir(agentId));
  }

  /**
   * A cheap fingerprint of the wrapper worktree's state, for the detection caches: it moves when a
   * file, the index or HEAD does.
   */
  String marker(String agentId) {
    Path dir = wrapperDir(agentId);
    GitExec.Out status =
        GitExec.git(dir, "--no-optional-locks", "status", "--porcelain=v2", "--branch", "-uall");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(status.stdout().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean hasRef(Path repository, String ref) {
    return GitExec.git(repository, "rev-parse", "--verify", "--quiet", ref).ok();
  }

  private void requireAgentId(String agentId) {
    if (!validAgentId(agentId)) {
      throw new AgentWorktreeException("Invalid agentId: " + agentId);
    }
  }

  /** A branch name reaches git as an argument: it must be a valid branch and not an option. */
  private void requireBranch(String branch) {
    if (branch == null
        || branch.isBlank()
        || branch.startsWith("-")
        || !GitExec.git(base, "check-ref-format", "--branch", branch).ok()) {
      throw new AgentWorktreeException("Invalid wrapperBranch: " + branch);
    }
  }

  private static boolean isEmpty(Path directory) {
    try (Stream<Path> children = Files.list(directory)) {
      return children.findAny().isEmpty();
    } catch (IOException e) {
      return false;
    }
  }

  /** Delete a directory tree without following symbolic links out of it. */
  static void deleteTree(Path root) {
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * A refusal the API answers as a 4xx: 400 for a bad id or branch, 409 for a worktree git would
   * not make (its branch is checked out in another worktree, say).
   */
  static final class AgentWorktreeException extends RuntimeException {
    private final int status;

    AgentWorktreeException(String message) {
      this(400, message);
    }

    AgentWorktreeException(int status, String message) {
      super(message);
      this.status = status;
    }

    int status() {
      return status;
    }
  }
}
