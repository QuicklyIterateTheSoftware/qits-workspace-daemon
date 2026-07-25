package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks in {@link OriginSync}'s decide/retry/reconcile logic — the container-free half driven off
 * the scheduler through the package-private {@code pushIfAhead}/{@code applyIncomingPull} seams
 * with a scripted {@link GitRunner}, so no real repository or network is needed. Backoff is set to
 * 0 so the transient-retry cases don't sleep.
 */
class OriginSyncTest {

  /**
   * A {@link GitRunner} scripting per-subcommand results (keyed by argv[1]) and recording calls.
   */
  private static final class ScriptedGit implements GitRunner {
    private final Map<String, Deque<Result>> scripts = new HashMap<>();
    final List<List<String>> calls = new ArrayList<>();

    ScriptedGit on(String subcommand, Result... results) {
      Deque<Result> queue = scripts.computeIfAbsent(subcommand, k -> new ArrayDeque<>());
      for (Result r : results) {
        queue.add(r);
      }
      return this;
    }

    @Override
    public Result run(String... argv) {
      calls.add(List.of(argv));
      Deque<Result> queue = scripts.get(argv[1]);
      Result next = queue == null ? null : queue.poll();
      return next != null ? next : new Result(0, ""); // default: success
    }

    long count(String subcommand) {
      return calls.stream().filter(c -> c.get(1).equals(subcommand)).count();
    }
  }

  private static final GitRunner.Result OK = new GitRunner.Result(0, "");
  private static final GitRunner.Result NON_FF =
      new GitRunner.Result(1, "! [rejected] feature -> feature (fetch first)");
  private static final GitRunner.Result LOCKED =
      new GitRunner.Result(1, "error: cannot lock ref 'refs/heads/feature'");
  private static final GitRunner.Result FATAL =
      new GitRunner.Result(128, "fatal: something went badly wrong");

  private OriginSync sync(GitRunner git, boolean enabled) {
    return new OriginSync("ws-1", "feature", git, enabled, 250, 4, 0, 0);
  }

  @Test
  void nothingToPushWhenNotAhead() {
    ScriptedGit git = new ScriptedGit().on("rev-list", new GitRunner.Result(0, "0\n"));
    assertEquals(OriginSync.PushOutcome.NOTHING_TO_PUSH, sync(git, true).pushIfAhead());
    assertEquals(0, git.count("push"));
  }

  @Test
  void pushesWhenAhead() {
    ScriptedGit git =
        new ScriptedGit().on("rev-list", new GitRunner.Result(0, "2\n")).on("push", OK);
    assertEquals(OriginSync.PushOutcome.PUSHED, sync(git, true).pushIfAhead());
    assertEquals(1, git.count("push"));
  }

  @Test
  void pushesWhenNoRemoteTrackingRefYet() {
    // rev-list fails (origin/feature doesn't exist): treat as ahead and let git decide.
    ScriptedGit git =
        new ScriptedGit().on("rev-list", new GitRunner.Result(128, "unknown revision"));
    assertEquals(OriginSync.PushOutcome.PUSHED, sync(git, true).pushIfAhead());
    assertEquals(1, git.count("push"));
  }

  @Test
  void disabledNeverPushes() {
    ScriptedGit git = new ScriptedGit();
    assertEquals(OriginSync.PushOutcome.DISABLED, sync(git, false).pushIfAhead());
    assertTrue(git.calls.isEmpty());
  }

  @Test
  void nonFastForwardReconcilesThenPushes() {
    ScriptedGit git =
        new ScriptedGit()
            .on("rev-list", new GitRunner.Result(0, "1\n"))
            .on("push", NON_FF, OK) // first rejected, retry succeeds after reconcile
            .on("fetch", OK)
            .on("merge", OK); // ff-only reconcile succeeds
    assertEquals(OriginSync.PushOutcome.PUSHED, sync(git, true).pushWithRetry());
    assertEquals(2, git.count("push"));
    assertEquals(1, git.count("fetch"));
    assertEquals(1, git.count("merge"));
  }

  @Test
  void nonFastForwardThatCannotReconcileIsLeft() {
    ScriptedGit git =
        new ScriptedGit()
            .on("push", NON_FF)
            .on("fetch", OK)
            .on("merge", FATAL); // ff-only refuses (diverged/dirty)
    assertEquals(OriginSync.PushOutcome.DIVERGED, sync(git, true).pushWithRetry());
    assertEquals(1, git.count("push")); // no force, no second push
  }

  @Test
  void transientFailureRetriesThenSucceeds() {
    ScriptedGit git = new ScriptedGit().on("push", LOCKED, OK);
    assertEquals(OriginSync.PushOutcome.PUSHED, sync(git, true).pushWithRetry());
    assertEquals(2, git.count("push"));
  }

  @Test
  void transientFailureExhaustsAttempts() {
    ScriptedGit git = new ScriptedGit().on("push", LOCKED, LOCKED, LOCKED, LOCKED);
    assertEquals(OriginSync.PushOutcome.FAILED, sync(git, true).pushWithRetry());
    assertEquals(4, git.count("push")); // maxAttempts
  }

  @Test
  void fatalFailureStopsImmediately() {
    ScriptedGit git = new ScriptedGit().on("push", FATAL);
    assertEquals(OriginSync.PushOutcome.FAILED, sync(git, true).pushWithRetry());
    assertEquals(1, git.count("push"));
  }

  @Test
  void incomingPullFastForwards() {
    ScriptedGit git = new ScriptedGit().on("fetch", OK).on("merge", OK);
    assertEquals(OriginSync.PullOutcome.PULLED, sync(git, true).applyIncomingPull("feature"));
    assertTrue(git.calls.get(git.calls.size() - 1).contains("--ff-only"));
  }

  @Test
  void incomingPullRefusedWhenNotFastForwardable() {
    // The accepted race: the tree turned dirty since the host's clean-gate, so ff-only refuses and
    // the checkout is left intact rather than clobbered.
    ScriptedGit git = new ScriptedGit().on("fetch", OK).on("merge", FATAL);
    assertEquals(OriginSync.PullOutcome.REFUSED, sync(git, true).applyIncomingPull("feature"));
  }

  @Test
  void incomingPullRefusedWhenFetchFails() {
    ScriptedGit git = new ScriptedGit().on("fetch", FATAL);
    assertEquals(OriginSync.PullOutcome.REFUSED, sync(git, true).applyIncomingPull("feature"));
    assertEquals(0, git.count("merge")); // never merges if the fetch failed
  }

  @Test
  void incomingPullSkipsBlankOrFlagBranch() {
    ScriptedGit git = new ScriptedGit();
    assertEquals(OriginSync.PullOutcome.SKIPPED, sync(git, true).applyIncomingPull(""));
    assertEquals(OriginSync.PullOutcome.SKIPPED, sync(git, true).applyIncomingPull("-D"));
    assertTrue(git.calls.isEmpty());
  }

  @Test
  void incomingPullIndependentOfTheAutoPushKillSwitch() {
    // A disabled auto-push must not disable host-triggered incoming pulls.
    ScriptedGit git = new ScriptedGit().on("fetch", OK).on("merge", OK);
    assertEquals(OriginSync.PullOutcome.PULLED, sync(git, false).applyIncomingPull("feature"));
  }

  @Test
  void nonFastForwardRejectionIsNotMistakenForTransient() {
    // Guards the classify() ordering: a "(fetch first)" reject must reconcile, not blind-retry.
    ScriptedGit git = new ScriptedGit().on("push", NON_FF, OK).on("fetch", OK).on("merge", OK);
    sync(git, true).pushWithRetry();
    assertEquals(1, git.count("fetch"), "a non-ff reject reconciles via fetch, not a bare retry");
    assertFalse(git.calls.isEmpty());
  }
}
