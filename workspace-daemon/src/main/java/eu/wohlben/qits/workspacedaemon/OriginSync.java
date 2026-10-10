package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.AgentBranchPushed;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 *       in every submodule, on a timer and whenever the host hints that a ref moved ({@code
 *       PullBranch}). Only remote-tracking refs move; the base clone's working tree is never
 *       touched, so a dirty tree can never block it. Agent worktrees share these refs, so a fresh
 *       {@code origin/main} is there for every agent at once.
 *   <li><b>Auto-push (agent branches → origin).</b> Every agent branch with commits no remote
 *       branch has is pushed, the wrapper branch and any branch an agent made in a submodule alike,
 *       each with <em>that agent's</em> credential. Each push is reported as an {@link
 *       AgentBranchPushed}. The default branch is never pushed, and a commit on a detached HEAD is
 *       not pushed at all; the cleanup check reports it.
 * </ul>
 *
 * <p>Both run on one thread, so a fetch and a push never race for the same ref lock. A rejected
 * push is classified: a lock or connection failure is retried with capped backoff, a
 * non-fast-forward is left alone (never a force; the agent rewrote a pushed branch, and only it can
 * say what it meant).
 *
 * <p>The push side polls rather than watches: one {@code git worktree list} per repository finds
 * every agent's branch and head at once, so the cost of a cycle does not grow with the number of
 * agents. A harness hook nudges a cycle at once, so a commit made in a turn is pushed when the turn
 * ends rather than at the next poll.
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
    int failures = 0;
    for (AgentWorktrees.Repo repo : worktrees.repositories()) {
      Path dir = worktrees.dirOf(repo);
      List<String> tampered = GitConfigGuard.violations(dir, repo.originUrl());
      if (!tampered.isEmpty()) {
        failures++;
        warnOnce(
            "fetch:" + repo.path(),
            "Not fetching " + where(repo) + ": its config carries " + tampered);
        continue;
      }
      GitExec.Out fetched =
          GitExec.network(
              dir, GitExec.workspaceCredential(), "fetch", "--prune", "--quiet", "origin");
      if (!fetched.ok()) {
        failures++;
        LOG.debugf(
            "fetch failed in %s: %s",
            repo.path().isEmpty() ? "the wrapper" : repo.path(), fetched.message());
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
    for (AgentWorktrees.Repo repo : worktrees.repositories()) {
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
        AgentBranchPushed done = pushIfNew(repository, repo, agentId, worktree);
        if (done != null) {
          pushed.add(done);
          emit.accept(done);
        }
      }
    }
    return pushed;
  }

  private AgentBranchPushed pushIfNew(
      Path repository, AgentWorktrees.Repo repo, String agentId, Worktree worktree) {
    String key = agentId + '\u0000' + repo.path() + '\u0000' + worktree.branch();
    if (worktree.head().equals(handled.get(key))) {
      return null;
    }
    GitExec.Out ahead =
        GitExec.git(
            repository, "rev-list", "--count", worktree.head(), "--not", "--remotes=origin");
    if (ahead.ok() && "0".equals(ahead.line())) {
      handled.put(key, worktree.head());
      return null;
    }
    String token =
        agentEnvironment.apply(agentId).map(env -> env.get(AGENT_CREDENTIAL)).orElse(null);
    if (token == null || token.isBlank()) {
      // Not handled: the branch is pushed once the host starts the agent again with its credential.
      // Never with the workspace's, and never with another agent's.
      LOG.debugf(
          "not pushing %s for agent %s: its credential is not known", worktree.branch(), agentId);
      return null;
    }
    List<String> tampered = GitConfigGuard.violations(repository, repo.originUrl());
    if (!tampered.isEmpty()) {
      warnOnce(
          "push:" + repo.path(),
          "Not pushing in " + where(repo) + ": its config carries " + tampered);
      return null;
    }
    PushOutcome outcome = push(repository, Map.of(AGENT_CREDENTIAL, token), worktree.branch());
    if (outcome != PushOutcome.RETRY_LATER) {
      handled.put(key, worktree.head());
    }
    return outcome == PushOutcome.PUSHED
        ? new AgentBranchPushed(agentId, repo.name(), worktree.branch(), worktree.head())
        : null;
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
