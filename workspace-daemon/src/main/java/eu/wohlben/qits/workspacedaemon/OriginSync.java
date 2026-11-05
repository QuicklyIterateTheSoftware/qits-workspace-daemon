package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.AgentBranchPushed;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * Keeps the workspace in sync with the git host in both directions (qits-1152, D8, D22):
 *
 * <ul>
 *   <li><b>Fetch (origin → base clone).</b> {@code git fetch --prune origin} in the base clone and
 *       in every submodule, branches and tags (the tags under {@code refs/origin-tags}, qits-1688), on a timer and whenever the host hints that a ref moved ({@code
 *       PullBranch}). Only remote-tracking refs move; the base clone's working tree is never
 *       touched, so a dirty tree can never block it. Agent worktrees share these refs, so a fresh
 *       {@code origin/main} is there for every agent at once.
 *   <li><b>Auto-push (agent branches → origin).</b> Every agent branch with commits no remote
 *       branch or tag has is pushed, the wrapper branch and any branch an agent made in a submodule alike,
 *       each with <em>that agent's</em> credential. Each push is reported as an {@link
 *       AgentBranchPushed}. The default branch is never pushed, and a commit on a detached HEAD is
 *       not pushed at all; the cleanup check reports it.
 *   <li><b>Agent-local repositories (qits-1886).</b> A repository that becomes a submodule during
 *       the session — {@code git submodule add} or {@code git submodule update --init} in an
 *       agent's wrapper worktree — is in neither the base clone's HEAD nor its worktree lists: git
 *       makes it a standalone clone under the wrapper worktree's git dir ({@code
 *       <base>/.git/worktrees/<wt>/modules/<name>}). Each agent's wrapper {@code .gitmodules} is
 *       therefore read every cycle, and a declared path the base clone does not have that holds a
 *       checkout is fetched and its branch pushed like any other, as that agent only. Only
 *       submodules of the wrapper and, below them, of an accepted agent-local repository are
 *       walked; one an agent adds inside a base-clone submodule is not.
 * </ul>
 *
 * <p>Both run on one thread, so a fetch and a push never race for the same ref lock. A rejected
 * push is classified: a lock or connection failure is retried with capped backoff, a
 * non-fast-forward is left alone (never a force; the agent rewrote a pushed branch, and only it can
 * say what it meant).
 *
 * <p>The push side polls rather than watches: one {@code git worktree list} per repository finds
 * every agent's branch and head at once, so the cost of a cycle does not grow with the number of
 * agents; only the agent-local walk is per agent, one {@code .gitmodules} read each. A harness hook
 * nudges a cycle at once, so a commit made in a turn is pushed when the turn ends rather than at
 * the next poll.
 */
final class OriginSync {

  private static final Logger LOG = Logger.getLogger(OriginSync.class);

  /** The outcome of one push. Package-private so a test can assert on it. */
  enum PushOutcome {
    PUSHED,
    DIVERGED,
    FAILED,
    RETRY_LATER
  }

  private enum Rejection {
    NON_FAST_FORWARD,
    TRANSIENT,
    FATAL
  }

  /**
   * The one name an agent's credential is read from in its start's {@code env}: the agent's own
   * token. Nothing else from that map reaches a git process of the daemon's.
   */
  static final String AGENT_CREDENTIAL = "QITS_TOKEN";

  /** One worktree of a repository, as {@code git worktree list --porcelain} reports it. */
  record Worktree(Path path, String head, String branch) {}

  /**
   * One repository a push or a fetch runs in: a base-clone repository, or an agent-local one
   * (qits-1886). {@code path} is its path in the wrapper, the key {@link #handled} and the log use;
   * {@code originUrl} the origin {@link GitConfigGuard} holds its config to; {@code where} names it
   * in a log line.
   */
  private record Target(Path dir, String path, String name, String originUrl, String where) {}

  private final AgentWorktrees worktrees;
  private final Function<String, Optional<Map<String, String>>> agentEnvironment;
  private final Consumer<DaemonMessage> emit;
  private final boolean autoPush;
  private final long fetchIntervalMs;
  private final long pushPollMs;
  private final long coalesceMs;
  private final int maxAttempts;
  private final long backoffInitialMs;
  private final long backoffMaxMs;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-origin-sync");
            thread.setDaemon(true);
            return thread;
          });

  private final AtomicBoolean fetchPending = new AtomicBoolean();
  private final AtomicBoolean pushPending = new AtomicBoolean();

  /**
   * The head last handled per (agent, repository path, branch): pushed, found to have nothing new,
   * or refused for good. A cycle skips a branch whose head has not moved since.
   */
  private final Map<String, String> handled = new ConcurrentHashMap<>();

  private volatile boolean closed;

  /**
   * @param agentEnvironment the agent's harness environment — its credential — by agent id, or
   *     empty while the daemon has not been told it (after a restart, until the host starts the
   *     agent again). A branch of such an agent waits rather than being pushed as the workspace
   * @param fetchIntervalMs the periodic fetch; {@code <= 0} fetches only on a hint
   * @param pushPollMs the periodic push cycle; {@code <= 0} pushes only when nudged
   */
  OriginSync(
      AgentWorktrees worktrees,
      Function<String, Optional<Map<String, String>>> agentEnvironment,
      Consumer<DaemonMessage> emit,
      boolean autoPush,
      long fetchIntervalMs,
      long pushPollMs,
      long coalesceMs,
      int maxAttempts,
      long backoffInitialMs,
      long backoffMaxMs) {
    this.worktrees = worktrees;
    this.agentEnvironment = agentEnvironment;
    this.emit = emit;
    this.autoPush = autoPush;
    this.fetchIntervalMs = fetchIntervalMs;
    this.pushPollMs = pushPollMs;
    this.coalesceMs = Math.max(0, coalesceMs);
    this.maxAttempts = Math.max(1, maxAttempts);
    this.backoffInitialMs = Math.max(0, backoffInitialMs);
    this.backoffMaxMs = Math.max(this.backoffInitialMs, backoffMaxMs);
  }

  /** Start the two timers. */
  void start() {
    if (fetchIntervalMs > 0) {
      scheduler.scheduleWithFixedDelay(
          () -> guarded("fetch", this::fetchAll),
          fetchIntervalMs,
          fetchIntervalMs,
          TimeUnit.MILLISECONDS);
    }
    if (autoPush && pushPollMs > 0) {
      scheduler.scheduleWithFixedDelay(
          () -> guarded("push", this::pushCycle), pushPollMs, pushPollMs, TimeUnit.MILLISECONDS);
    }
  }

  /** The host says a ref moved: fetch now, ahead of the timer. Coalesced. */
  void requestFetch() {
    if (!closed && fetchPending.compareAndSet(false, true)) {
      submit(
          () -> {
            fetchPending.set(false);
            guarded("fetch", this::fetchAll);
          },
          0);
    }
  }

  /** Something may have been committed (a harness hook fired): push soon. Coalesced. */
  void nudge() {
    if (autoPush && !closed && pushPending.compareAndSet(false, true)) {
      submit(
          () -> {
            pushPending.set(false);
            guarded("push", this::pushCycle);
          },
          coalesceMs);
    }
  }

  private void submit(Runnable work, long delayMs) {
    try {
      scheduler.schedule(work, delayMs, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
      LOG.debug("origin sync is shut down; request dropped");
    }
  }

  private void guarded(String what, Runnable work) {
    if (closed) {
      return;
    }
    try {
      work.run();
    } catch (RuntimeException e) {
      LOG.debugf(e, "origin %s cycle failed", what);
    }
  }

  /**
   * {@code git fetch --prune origin} in the base clone and every submodule. Package-private so a
   * test drives it off the scheduler. Answers how many repositories failed to fetch.
   */
  int fetchAll() {
    List<Target> targets = new ArrayList<>();
    for (AgentWorktrees.Repo repo : worktrees.repositories()) {
      targets.add(target(repo));
    }
    for (String agentId : worktrees.agentIds()) {
      targets.addAll(agentLocal(agentId));
    }
    int failures = 0;
    for (Target target : targets) {
      List<String> tampered = GitConfigGuard.violations(target.dir(), target.originUrl());
      if (!tampered.isEmpty()) {
        failures++;
        warnOnce(
            "fetch:" + target.where(),
            "Not fetching " + target.where() + ": its config carries " + tampered);
        continue;
      }
      GitExec.Out fetched =
          GitExec.network(
              target.dir(),
              GitExec.workspaceCredential(),
              "fetch",
              "--prune",
              "--quiet",
              "origin",
              "+refs/heads/*:refs/remotes/origin/*",
              AgentWorktrees.ORIGIN_TAGS_REFSPEC);
      if (!fetched.ok()) {
        failures++;
        LOG.debugf("fetch failed in %s: %s", target.where(), fetched.message());
      }
    }
    return failures;
  }

  /**
   * One push cycle over every repository and every agent. Package-private so a test drives it off
   * the scheduler. Answers what it pushed, in order.
   */
  List<AgentBranchPushed> pushCycle() {
    List<AgentBranchPushed> pushed = new ArrayList<>();
    if (!autoPush) {
      return pushed;
    }
    Path agentsRoot = worktrees.agentsRoot().toAbsolutePath().normalize();
    List<AgentWorktrees.Repo> repos = worktrees.repositories();
    for (AgentWorktrees.Repo repo : repos) {
      Path repository = worktrees.dirOf(repo);
      for (Worktree worktree : list(repository)) {
        Path path = worktree.path().toAbsolutePath().normalize();
        if (worktree.branch() == null
            || !path.startsWith(agentsRoot)
            || path.equals(agentsRoot)
            || worktree.branch().equals(repo.defaultBranch())) {
          continue;
        }
        String agentId = agentsRoot.relativize(path).getName(0).toString();
        // Only the worktree the daemon made for this agent counts. Agents share the OS user, so one
        // could add a worktree of its own under another agent's directory; its branch must never be
        // pushed with that other agent's credential.
        if (!AgentWorktrees.validAgentId(agentId)
            || !worktrees.contained(agentId)
            || !path.equals(expectedWorktree(agentId, repo))) {
          continue;
        }
        report(pushIfNew(target(repo), agentId, worktree.branch(), worktree.head()), pushed);
      }
    }
    String wrapperDefault = repos.get(0).defaultBranch();
    for (String agentId : worktrees.agentIds()) {
      for (Target local : agentLocal(agentId)) {
        // Read in the checkout itself: it is no worktree of anything in the base clone.
        GitExec.Out branch = GitExec.git(local.dir(), "symbolic-ref", "--quiet", "--short", "HEAD");
        GitExec.Out head = GitExec.git(local.dir(), "rev-parse", "--verify", "--quiet", "HEAD");
        if (!branch.ok()
            || !head.ok()
            || branch.line().isEmpty()
            || branch.line().equals(wrapperDefault)
            || branch.line().equals(AgentWorktrees.defaultBranch(local.dir()))) {
          continue;
        }
        report(pushIfNew(local, agentId, branch.line(), head.line()), pushed);
      }
    }
    return pushed;
  }

  private void report(AgentBranchPushed done, List<AgentBranchPushed> pushed) {
    if (done != null) {
      pushed.add(done);
      emit.accept(done);
    }
  }

  private AgentBranchPushed pushIfNew(Target target, String agentId, String branch, String head) {
    String key = agentId + '\u0000' + target.path() + '\u0000' + branch;
    if (head.equals(handled.get(key))) {
      return null;
    }
    // A commit a release tag holds is delivered, even after the release deleted its branch: pushing
    // it again would bring the released branch back (qits-1688).
    GitExec.Out ahead = GitExec.git(target.dir(), AgentWorktrees.notOnOrigin(head));
    if (ahead.ok() && "0".equals(ahead.line())) {
      handled.put(key, head);
      return null;
    }
    String token =
        agentEnvironment.apply(agentId).map(env -> env.get(AGENT_CREDENTIAL)).orElse(null);
    if (token == null || token.isBlank()) {
      // Not handled: the branch is pushed once the host starts the agent again with its credential.
      // Never with the workspace's, and never with another agent's.
      LOG.debugf("not pushing %s for agent %s: its credential is not known", branch, agentId);
      return null;
    }
    List<String> tampered = GitConfigGuard.violations(target.dir(), target.originUrl());
    if (!tampered.isEmpty()) {
      warnOnce(
          "push:" + target.where(),
          "Not pushing in " + target.where() + ": its config carries " + tampered);
      return null;
    }
    PushOutcome outcome = push(target.dir(), Map.of(AGENT_CREDENTIAL, token), branch);
    if (outcome != PushOutcome.RETRY_LATER) {
      handled.put(key, head);
    }
    return outcome == PushOutcome.PUSHED
        ? new AgentBranchPushed(agentId, target.name(), branch, head)
        : null;
  }

  private Target target(AgentWorktrees.Repo repo) {
    return new Target(
        worktrees.dirOf(repo), repo.path(), repo.name(), repo.originUrl(), where(repo));
  }

  /**
   * The agent's agent-local repositories (qits-1886): every submodule its wrapper worktree's {@code
   * .gitmodules} declares that the base clone does not have and that holds a checkout, then the
   * same one level down inside each one accepted, a parent before its children. Read from {@code
   * .gitmodules} rather than {@code submodule status --recursive}, which would walk every base
   * submodule of every agent each cycle to find the rare one that is new.
   *
   * <p>Agents share the OS user, so each checkout is held to what {@link #expectedWorktree} and
   * {@link AgentWorktrees#contained} give a base repository: its path, real-path resolved, is
   * exactly its place in this agent's wrapper worktree (no link out), and its git dir lies under
   * this agent's wrapper worktree git dir's {@code modules/} — where git put it, and where no other
   * agent's submodule command would. A clone planted at a declared path, or a {@code .git} file
   * pointing into another agent's modules, is not this agent's and is skipped. The origin it is
   * held to ({@link GitConfigGuard}) is its {@code .gitmodules} url resolved against the parent's
   * — the wrapper's recorded origin, or the parent agent-local repository's expected one — never
   * its own {@code remote.origin.url}.
   */
  private List<Target> agentLocal(String agentId) {
    List<Target> found = new ArrayList<>();
    if (!AgentWorktrees.validAgentId(agentId) || !worktrees.exists(agentId)) {
      return found;
    }
    List<AgentWorktrees.Repo> repos = worktrees.repositories();
    String wrapperUrl = repos.get(0).originUrl();
    Path wrapper;
    Path modules;
    try {
      wrapper = worktrees.wrapperDir(agentId).toRealPath();
      Path gitDir = realGitDir(wrapper);
      if (wrapperUrl == null
          || gitDir == null
          || !gitDir.getParent().equals(worktrees.base().resolve(".git/worktrees").toRealPath())) {
        return found;
      }
      modules = gitDir.resolve("modules");
    } catch (IOException e) {
      return found;
    }
    Set<String> basePaths = new HashSet<>();
    repos.forEach(repo -> basePaths.add(repo.path()));
    walk(agentId, wrapper, modules, basePaths, "", wrapperUrl, 0, found);
    return found;
  }

  private void walk(
      String agentId,
      Path wrapper,
      Path modules,
      Set<String> basePaths,
      String parent,
      String parentUrl,
      int depth,
      List<Target> found) {
    if (depth >= Provisioner.MAX_SUBMODULE_DEPTH) {
      return;
    }
    Path parentDir = parent.isEmpty() ? wrapper : wrapper.resolve(parent);
    String gitmodules = parentDir.resolve(".gitmodules").toString();
    GitExec.Out listed =
        GitExec.git(
            wrapper, "config", "--file", gitmodules, "--get-regexp", "^submodule\\..*\\.path$");
    if (!listed.ok()) {
      return;
    }
    List<Target> accepted = new ArrayList<>();
    for (Provisioner.Submodule sub : Provisioner.parseSubmodules(listed.stdout())) {
      // The path and the url come from a file the agent writes: confined, and resolved here.
      if (!AgentWorktrees.confined(sub.path())) {
        continue;
      }
      String path = parent.isEmpty() ? sub.path() : parent + "/" + sub.path();
      Path dir = wrapper.resolve(path);
      if (basePaths.contains(path)
          || !Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      String where = path + " (agent " + agentId + ")";
      GitExec.Out url =
          GitExec.git(wrapper, "config", "--file", gitmodules, "submodule." + sub.name() + ".url");
      String expected = url.ok() ? Provisioner.resolveSubmoduleUrl(url.line(), parentUrl) : null;
      if (expected == null) {
        warnOnce(
            "local:" + where, "Not syncing " + where + ": its .gitmodules url is unresolvable");
        continue;
      }
      if (!ownCheckout(dir, wrapper.resolve(path), modules)) {
        warnOnce(
            "local:" + where,
            "Not syncing " + where + ": its checkout is not where this agent's git put it");
        continue;
      }
      accepted.add(new Target(dir, path, Provisioner.basename(expected), expected, where));
    }
    for (Target target : accepted) {
      found.add(target);
      walk(
          agentId,
          wrapper,
          modules,
          basePaths,
          target.path(),
          target.originUrl(),
          depth + 1,
          found);
    }
  }

  /**
   * Whether {@code dir} is a checkout this agent's git made: its real path is {@code expected}, and
   * its real git dir lies under {@code modules}.
   */
  private static boolean ownCheckout(Path dir, Path expected, Path modules) {
    try {
      if (!dir.toRealPath().equals(expected)) {
        return false;
      }
      Path gitDir = realGitDir(dir);
      return gitDir != null && gitDir.startsWith(modules) && !gitDir.equals(modules);
    } catch (IOException e) {
      return false;
    }
  }

  /** {@code rev-parse --absolute-git-dir} in {@code dir}, real-path resolved; null if git fails. */
  private static Path realGitDir(Path dir) throws IOException {
    GitExec.Out gitDir = GitExec.git(dir, "rev-parse", "--absolute-git-dir");
    return gitDir.ok() && !gitDir.line().isEmpty() ? Path.of(gitDir.line()).toRealPath() : null;
  }

  /** Push one branch with retry. Package-private so a test can drive the classification. */
  PushOutcome push(Path repository, Map<String, String> credential, String branch) {
    long backoff = backoffInitialMs;
    String refspec = "refs/heads/" + branch + ":refs/heads/" + branch;
    for (int attempt = 1; attempt <= maxAttempts && !closed; attempt++) {
      GitExec.Out result = GitExec.network(repository, credential, "push", "origin", refspec);
      if (result.ok()) {
        return PushOutcome.PUSHED;
      }
      switch (classify(result.message())) {
        case NON_FAST_FORWARD -> {
          LOG.infof(
              "not pushing %s: the git host has commits it lacks (%s)", branch, result.message());
          return PushOutcome.DIVERGED;
        }
        case FATAL -> {
          LOG.infof("pushing %s failed: %s", branch, result.message());
          return PushOutcome.FAILED;
        }
        case TRANSIENT -> {
          if (attempt < maxAttempts) {
            sleep(backoff);
            backoff = Math.min(backoffMaxMs, backoff * 2);
          }
        }
      }
    }
    return PushOutcome.RETRY_LATER;
  }

  /** Every worktree of {@code repository}. */
  static List<Worktree> list(Path repository) {
    GitExec.Out out = GitExec.git(repository, "worktree", "list", "--porcelain");
    List<Worktree> worktrees = new ArrayList<>();
    if (!out.ok()) {
      return worktrees;
    }
    String path = null;
    String head = "";
    String branch = null;
    for (String line : (out.stdout() + "\n").split("\n", -1)) {
      if (line.isEmpty()) {
        if (path != null) {
          worktrees.add(new Worktree(Path.of(path), head, branch));
        }
        path = null;
        head = "";
        branch = null;
      } else if (line.startsWith("worktree ")) {
        path = line.substring("worktree ".length());
      } else if (line.startsWith("HEAD ")) {
        head = line.substring("HEAD ".length());
      } else if (line.startsWith("branch refs/heads/")) {
        branch = line.substring("branch refs/heads/".length());
      }
    }
    return worktrees;
  }

  private static Rejection classify(String output) {
    String o = output == null ? "" : output.toLowerCase(Locale.ROOT);
    if (o.contains("fetch first") || o.contains("non-fast-forward") || o.contains("[rejected]")) {
      return Rejection.NON_FAST_FORWARD;
    }
    if (o.contains("cannot lock ref")
        || o.contains("failed to lock")
        || o.contains("unable to create")
        || o.contains("index.lock")
        || o.contains("could not read from remote")
        || o.contains("hung up")
        || o.contains("connection")
        || o.contains("timed out")) {
      return Rejection.TRANSIENT;
    }
    return Rejection.FATAL;
  }

  private void sleep(long ms) {
    if (ms <= 0) {
      return;
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Path expectedWorktree(String agentId, AgentWorktrees.Repo repo) {
    Path wrapper = worktrees.wrapperDir(agentId).toAbsolutePath().normalize();
    return repo.wrapper() ? wrapper : wrapper.resolve(repo.path()).normalize();
  }

  private static String where(AgentWorktrees.Repo repo) {
    return repo.path().isEmpty() ? "the wrapper" : repo.path();
  }

  /** Warn once per subject, so a tampered repository does not flood the log every cycle. */
  private void warnOnce(String subject, String message) {
    if (warned.add(subject)) {
      LOG.warn(message);
    }
  }

  private final java.util.Set<String> warned = ConcurrentHashMap.newKeySet();

  void close() {
    closed = true;
    scheduler.shutdownNow();
  }
}
