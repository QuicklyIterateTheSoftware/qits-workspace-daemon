package eu.wohlben.qits.workspacedaemon;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Keeps the container's checkout and its origin ref in sync in <em>both</em> directions
 * (docs/epics/qits-workspace-daemon/features/2026-07-25_daemon-bidirectional-auto-sync.md):
 *
 * <ul>
 *   <li><b>Auto-push (container → origin).</b> The {@link GitStatusMonitor} already sees every
 *       commit (a commit moves the working-tree marker even though it touches only {@code .git}),
 *       so {@link #onWorkingTreeSettled()} is called on each report. When the local branch is ahead
 *       of {@code origin/<branch>} it pushes right away — so a commit the coding agent (or a
 *       merge-into-this-workspace) makes is durable on origin without waiting for the next host op.
 *   <li><b>Incoming pull (origin → container).</b> {@link #pull(String)} fast-forwards the checkout
 *       to origin after the host reports a merge/integration advanced this branch out-of-band.
 * </ul>
 *
 * <p><b>Push conflicts.</b> The host still pushes the same branch to the same bare origin from a
 * few paths ({@code mergeWorkspace}'s pre-integration push, {@code fastForwardWorkspace}, {@code
 * updateWorkspaceFromParent}, the stop-time {@code pushBranch}), so two pushes can race on origin's
 * ref lock. A rejected push is classified: a transient lock/connection failure is retried with
 * capped exponential backoff (the "delay this automatic push" the design calls for), and a
 * non-fast-forward rejection (origin moved ahead under us) is reconciled with a {@code --ff-only}
 * pull before one more push — never a force. A tree that can't fast-forward is left exactly as-is;
 * the next host git op reconciles it.
 *
 * <p><b>Serialization.</b> Pushes and pulls both run on one single-thread scheduler, so an
 * auto-push and an incoming pull in the same container never interleave against git.
 */
final class OriginSync {

  private static final Logger LOG = Logger.getLogger(OriginSync.class);

  /** The terminal outcome of an auto-push cycle. Package-private so tests can assert on it. */
  enum PushOutcome {
    DISABLED,
    NOTHING_TO_PUSH,
    PUSHED,
    DIVERGED,
    FAILED
  }

  /** The terminal outcome of an incoming pull. */
  enum PullOutcome {
    SKIPPED,
    PULLED,
    REFUSED
  }

  /** How a rejected {@code git push} is classified from its combined output. */
  private enum Rejection {
    NON_FAST_FORWARD,
    TRANSIENT,
    FATAL
  }

  private final String workspaceId;
  private final String branch;
  private final GitRunner git;
  private final boolean enabled;
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

  /** At-most-one pending push window, so a burst of reports coalesces into one push cycle. */
  private final AtomicBoolean windowOpen = new AtomicBoolean();

  private volatile boolean closed;

  OriginSync(
      String workspaceId,
      String branch,
      GitRunner git,
      boolean enabled,
      long coalesceMs,
      int maxAttempts,
      long backoffInitialMs,
      long backoffMaxMs) {
    this.workspaceId = workspaceId;
    this.branch = branch;
    this.git = git;
    this.enabled = enabled;
    this.coalesceMs = coalesceMs;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.backoffInitialMs = Math.max(0, backoffInitialMs);
    this.backoffMaxMs = Math.max(this.backoffInitialMs, backoffMaxMs);
  }

  /**
   * The {@link GitStatusMonitor} reported (its marker moved — a commit, a checkout, or a content
   * edit): open a coalescing window and, when it closes, push if the branch has unpushed commits. A
   * content-edit-only report finds nothing ahead and is a cheap no-op.
   */
  void onWorkingTreeSettled() {
    if (!enabled || closed || branch == null || branch.isBlank()) {
      return;
    }
    if (windowOpen.compareAndSet(false, true)) {
      scheduler.schedule(this::pushCycle, coalesceMs, TimeUnit.MILLISECONDS);
    }
  }

  private void pushCycle() {
    windowOpen.set(false);
    if (closed) {
      return;
    }
    try {
      pushIfAhead();
    } catch (RuntimeException e) {
      LOG.debugf(e, "auto-push cycle failed for %s", workspaceId);
    }
  }

  /**
   * Push the branch iff it is ahead of {@code origin/<branch>}. Package-private so a test drives it
   * directly (off the scheduler) with a canned {@link GitRunner}.
   */
  PushOutcome pushIfAhead() {
    if (!enabled) {
      return PushOutcome.DISABLED;
    }
    if (!isAhead()) {
      return PushOutcome.NOTHING_TO_PUSH;
    }
    return pushWithRetry();
  }

  /**
   * Whether the local {@code HEAD} has commits {@code origin/<branch>} lacks. A failed count (no
   * remote-tracking ref yet, e.g. a never-pushed branch) is treated as "ahead" so the first push is
   * attempted — git itself is the source of truth for what actually needs sending.
   */
  private boolean isAhead() {
    GitRunner.Result r = git.run("git", "rev-list", "--count", "origin/" + branch + "..HEAD");
    if (!r.ok()) {
      return true;
    }
    try {
      return Integer.parseInt(r.output().trim()) > 0;
    } catch (NumberFormatException e) {
      return true;
    }
  }

  /** Package-private for tests: push with backoff-retry and non-fast-forward reconciliation. */
  PushOutcome pushWithRetry() {
    long backoff = backoffInitialMs;
    for (int attempt = 1; attempt <= maxAttempts && !closed; attempt++) {
      GitRunner.Result r = git.run("git", "push", "origin", branch);
      if (r.ok()) {
        return PushOutcome.PUSHED; // includes "Everything up-to-date"
      }
      switch (classify(r.output())) {
        case NON_FAST_FORWARD -> {
          // Origin advanced under us: reconcile with a fast-forward pull, then retry the push once
          // more. If it won't fast-forward (diverged/dirty), leave it — never force.
          if (!reconcile().ok()) {
            LOG.debugf(
                "auto-push for %s hit a non-fast-forward it could not reconcile; leaving it",
                workspaceId);
            return PushOutcome.DIVERGED;
          }
        }
        case TRANSIENT -> {
          if (attempt < maxAttempts) {
            sleep(backoff);
            backoff = Math.min(backoffMaxMs, backoff * 2);
          }
        }
        case FATAL -> {
          LOG.debugf("auto-push for %s failed fatally: %s", workspaceId, oneLine(r.output()));
          return PushOutcome.FAILED;
        }
      }
    }
    return PushOutcome.FAILED;
  }

  /** {@code git fetch} + {@code git merge --ff-only origin/<branch>} — the reconcile primitive. */
  private GitRunner.Result reconcile() {
    git.run("git", "fetch", "origin", branch);
    return git.run("git", "merge", "--ff-only", "origin/" + branch);
  }

  /**
   * Apply an incoming merge the host pushed to origin: fetch and fast-forward the checkout. Runs on
   * the sync thread (serialized with pushes). Refuses anything but a fast-forward — a tree that
   * turned dirty since the host's clean-gate is left intact rather than clobbered (the accepted
   * race).
   */
  void pull(String incomingBranch) {
    if (closed) {
      return;
    }
    scheduler.execute(
        () -> {
          try {
            applyIncomingPull(incomingBranch);
          } catch (RuntimeException e) {
            LOG.debugf(e, "incoming pull failed for %s", workspaceId);
          }
        });
  }

  /** Package-private for tests: the fetch + ff-only body of {@link #pull}. */
  PullOutcome applyIncomingPull(String incomingBranch) {
    // The host only asks us to pull our own checkout branch; reject a blank/flag-shaped name rather
    // than hand it to git as an argument.
    if (incomingBranch == null || incomingBranch.isBlank() || incomingBranch.startsWith("-")) {
      return PullOutcome.SKIPPED;
    }
    if (!git.run("git", "fetch", "origin", incomingBranch).ok()) {
      return PullOutcome.REFUSED;
    }
    GitRunner.Result ff = git.run("git", "merge", "--ff-only", "origin/" + incomingBranch);
    if (ff.ok()) {
      return PullOutcome.PULLED;
    }
    // Not fast-forwardable now (a race dirtied the tree, or an unexpected divergence): leave it —
    // the next host git op (fast-forward / merge-parent-in) reconciles via its own --ff-only step.
    LOG.debugf(
        "incoming pull for %s could not fast-forward %s; left intact", workspaceId, incomingBranch);
    return PullOutcome.REFUSED;
  }

  private static Rejection classify(String output) {
    String o = output == null ? "" : output.toLowerCase(Locale.ROOT);
    if (o.contains("fetch first")
        || o.contains("non-fast-forward")
        || o.contains("[rejected]")
        || o.contains("remote rejected")) {
      return Rejection.NON_FAST_FORWARD;
    }
    if (o.contains("cannot lock ref")
        || o.contains("failed to lock")
        || o.contains("unable to create")
        || o.contains("index.lock")
        || o.contains("could not read from remote")
        || o.contains("hung up")
        || o.contains("shutdown")
        || o.contains("connection")
        || o.contains("timed out")
        || o.contains("interrupted")) {
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

  private static String oneLine(String output) {
    return output == null ? "" : output.strip().replace('\n', ' ');
  }

  void close() {
    closed = true;
    scheduler.shutdownNow();
  }
}
