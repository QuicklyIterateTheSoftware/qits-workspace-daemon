package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.GitStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Locks in {@link GitStatusMonitor}'s decide-and-dedup logic — the container-free half that turns
 * raw {@code git status}/{@code git diff} output into at-most-one {@link GitStatus} report. The
 * marker (status + diff) is the dedup key, so a dirty→dirty content edit still reports (the "files
 * changed" signal) while an unchanged tree stays silent.
 */
class GitStatusMonitorTest {

  private final List<DaemonMessage> sent = new ArrayList<>();
  private final GitStatusMonitor monitor =
      new GitStatusMonitor("ws-1", "repo-1", "feature", "main", sent::add, 20_000, 120_000);

  private static final String CLEAN = "# branch.oid 1a2b3c4d\n# branch.head feature\n";
  private static final String DIRTY =
      "# branch.oid 1a2b3c4d\n# branch.head feature\n1 .M N... 100644 100644 100644 aaa bbb r.md\n";

  private GitStatus lastSent() {
    return (GitStatus) sent.get(sent.size() - 1);
  }

  @Test
  void firstSettleEmitsTheBootReport() {
    monitor.settle(CLEAN, "");
    assertEquals(1, sent.size());
    assertTrue(lastSent().clean());
    assertEquals("1a2b3c4d", lastSent().head());
    assertEquals("ws-1", lastSent().workspaceId());
  }

  @Test
  void cleanToDirtyFlipEmits() {
    monitor.settle(CLEAN, "");
    monitor.settle(DIRTY, "diff --git a/r.md b/r.md\n");
    assertEquals(2, sent.size());
    assertFalse(lastSent().clean());
  }

  @Test
  void dirtyToCleanFlipEmits() {
    monitor.settle(DIRTY, "diff --git a/r.md b/r.md\n");
    monitor.settle(CLEAN, "");
    assertEquals(2, sent.size());
    assertTrue(lastSent().clean());
  }

  @Test
  void identicalMarkerDoesNotReEmit() {
    monitor.settle(CLEAN, "");
    monitor.settle(CLEAN, "");
    assertEquals(1, sent.size());
  }

  @Test
  void contentEditWhileAlreadyDirtyStillEmits() {
    // Both settles report dirty, but the diff moved (a second file edited) — the marker moves, so
    // the files-changed signal must still fire. A bare boolean would swallow this.
    monitor.settle(DIRTY, "diff one\n");
    monitor.settle(DIRTY, "diff one\ndiff two\n");
    assertEquals(2, sent.size());
    assertFalse(lastSent().clean());
  }

  @Test
  void reportCurrentReEmitsLastAndIsNoOpBeforeFirstReport() {
    monitor.reportCurrent();
    assertEquals(0, sent.size()); // nothing reported yet
    monitor.settle(CLEAN, "");
    monitor.reportCurrent();
    assertEquals(2, sent.size());
    assertTrue(lastSent().clean());
  }

  // --- trailing-debounce timing (the index.lock-contention fix)
  // -----------------------------------
  //
  // These tests drive the REAL ScheduledExecutorService, so they trade a scaled-down window
  // (tens of ms, not the shipped 1.5s/120s) for a wall-clock wait. The wait after quiescence
  // (~200ms) is a deliberate ~5x margin over the window so the scheduled settle has fired by the
  // assertion; on a badly CPU-starved runner that margin could in principle be missed (an
  // intermittent false failure, not a code defect). Kept because it mirrors the existing pattern
  // and the margin is generous; if it ever flakes in CI, widen the sleeps rather than tighten them.

  /**
   * A burst of raw inotify events — like a {@code git commit} scribbling under {@code
   * .git/index}/{@code refs} — must collapse to a <b>single</b> recompute, fired only after the
   * tree falls quiet for the debounce period. If each event triggered its own {@code git status},
   * the recompute would race the committer for {@code index.lock}.
   */
  @Test
  @Timeout(5)
  void rapidBurstCollapsesToOneSettleAfterQuiescence() throws InterruptedException {
    AtomicInteger settles = new AtomicInteger();
    // 40ms quiet period, generous 2s cap: a fast burst settles by quiescence, not by the cap.
    GitStatusMonitor debounced =
        new GitStatusMonitor(
            "ws-1", "repo-1", "feature", "main", sent::add, 40, 2_000, settles::incrementAndGet);

    for (int i = 0; i < 50; i++) {
      debounced.onRawChange(); // faster than the 40ms window ⇒ each event re-arms the timer
    }
    assertEquals(0, settles.get(), "no settle should fire while events are still streaming");

    Thread.sleep(200); // let the tree fall quiet past the 40ms window
    assertEquals(1, settles.get(), "the whole burst must collapse to exactly one recompute");
    debounced.close();
  }

  /**
   * Under <b>sustained</b> churn (events never pause longer than the quiet period), the resetting
   * timer would be starved forever — so the {@code maxWaitMs} cap forces a settle anyway, keeping
   * the badge alive without ever letting the quiet period lapse mid-commit.
   */
  @Test
  @Timeout(5)
  void sustainedChurnStillSettlesViaTheMaxWaitCap() throws InterruptedException {
    AtomicInteger settles = new AtomicInteger();
    // 60ms quiet period that never elapses, but a 120ms hard cap that must.
    GitStatusMonitor debounced =
        new GitStatusMonitor(
            "ws-1", "repo-1", "feature", "main", sent::add, 60, 120, settles::incrementAndGet);

    long deadline = System.nanoTime() + Duration.ofMillis(300).toNanos();
    while (System.nanoTime() < deadline) {
      debounced.onRawChange(); // re-arm every ~20ms — the 60ms quiet period never lands
      Thread.sleep(20);
    }
    // Across 300ms of unbroken churn the 60ms debounce never fires on its own; the 120ms cap does,
    // at least twice.
    assertTrue(
        settles.get() >= 2, "max-wait cap must force periodic settles, got " + settles.get());
    debounced.close();
  }

  /**
   * The lag regression (issue candidate 2): a commit's write burst that then goes quiet must flip
   * the badge within a short bound — nowhere near the old 20s window. With status/diff now
   * lock-free (--no-optional-locks) the debounce is a short coalescing gate, so the single settle
   * lands just after quiescence, not tens of seconds later.
   */
  @Test
  @Timeout(5)
  void commitBurstFlipsWithinAShortBound() throws InterruptedException {
    AtomicInteger settles = new AtomicInteger();
    // 40ms quiet window standing in for the shipped 1.5s default; the point is one prompt settle.
    GitStatusMonitor debounced =
        new GitStatusMonitor(
            "ws-1", "repo-1", "feature", "main", sent::add, 40, 120_000, settles::incrementAndGet);

    // A commit scribbles a short burst under .git/index/refs/heads, then the tree goes quiet.
    for (int i = 0; i < 10; i++) {
      debounced.onRawChange();
    }
    assertEquals(0, settles.get(), "no settle while the commit burst is still streaming");

    Thread.sleep(200); // well under the old 20s — a short multiple of the quiet window
    assertEquals(
        1, settles.get(), "the commit must flip the badge once, promptly after quiescence");
    debounced.close();
  }

  /**
   * A push after the commit-settle must not defer the badge: even if a watched write re-arms the
   * (now short) window, the follow-up settle still lands within the short bound — the ~20s
   * push-deferral is gone. (Remote-tracking refs are also excluded from the watch, so a real push's
   * .git/refs/remotes write does not even re-arm; this bounds the worst case where something does.)
   */
  @Test
  @Timeout(5)
  void aBurstAfterTheFirstSettleStillFlipsWithinTheShortWindow() throws InterruptedException {
    AtomicInteger settles = new AtomicInteger();
    GitStatusMonitor debounced =
        new GitStatusMonitor(
            "ws-1", "repo-1", "feature", "main", sent::add, 40, 120_000, settles::incrementAndGet);

    debounced.onRawChange(); // the commit
    Thread.sleep(200);
    assertEquals(1, settles.get(), "commit settles once");

    debounced.onRawChange(); // a later write (e.g. a push landing in the window)
    Thread.sleep(200);
    assertEquals(2, settles.get(), "the second burst settles within the short window, not +20s");
    debounced.close();
  }

  /**
   * The watch must exclude remote-tracking and fetch bookkeeping — a push/fetch/auto-push writes
   * these but never changes working-tree cleanliness, so watching them would needlessly re-arm the
   * badge debounce (issue candidate 1). Local refs/heads, index and HEAD stay watched.
   */
  @Test
  void watchArgvExcludesRemoteAndFetchBookkeeping() {
    Pattern exclude = excludePattern();
    // Excluded: the top-level repo's remote-tracking + fetch bookkeeping (a push/fetch writes these
    // but they never move the marker).
    assertMatches(exclude, ".git/refs/remotes/origin/feature");
    assertMatches(exclude, ".git/FETCH_HEAD");
    assertMatches(exclude, ".git/ORIG_HEAD");
    // Watched: local branch refs, index, HEAD and work-tree files — so a commit is still observed.
    assertNoMatch(exclude, ".git/refs/heads/feature");
    assertNoMatch(exclude, ".git/index");
    assertNoMatch(exclude, "src/main/App.java");
  }

  /**
   * A submodule keeps its own gitdir under {@code .git/modules/<name>/}; a submodule fetch/push
   * writes its remote-tracking + fetch bookkeeping there. The exclusion must reach into that nested
   * gitdir too (via the {@code (modules/[^/]+/)*} hop), or a workspace with submodules would still
   * re-arm the badge debounce on every submodule sync — the very churn the exclusion removes.
   */
  @Test
  void watchArgvExcludesSubmoduleGitdirBookkeeping() {
    Pattern exclude = excludePattern();
    assertMatches(exclude, ".git/modules/lib-a/refs/remotes/origin/main");
    assertMatches(exclude, ".git/modules/lib-a/FETCH_HEAD");
    assertMatches(exclude, ".git/modules/lib-a/modules/nested/refs/remotes/origin/main"); // nested
    // A submodule's local branch ref still matters (its HEAD moving advances the parent gitlink).
    assertNoMatch(exclude, ".git/modules/lib-a/refs/heads/main");
  }

  /**
   * A blank {@code git status} read means the git fork failed (non-zero exit ⇒ {@code capture}
   * returned ""), which {@code WorkspaceDescriber.parse} reads as not-dirty — so an unguarded
   * settle would flip the badge falsely clean. The shorter debounce can land a read mid-commit,
   * making this reachable, so {@code settle} must emit nothing and leave the last (dirty) report
   * standing.
   */
  @Test
  void blankStatusFromAFailedReadDoesNotFlipTheBadgeClean() {
    monitor.settle(DIRTY, "diff one\n");
    assertEquals(1, sent.size());
    assertFalse(lastSent().clean());

    monitor.settle("", ""); // a failed git read
    assertEquals(1, sent.size(), "a blank/failed status read must emit nothing");
    assertFalse(lastSent().clean(), "the badge must stay dirty, not flip falsely clean");
  }

  /** Compile the watch's {@code --exclude} POSIX-ish alternation as a Java regex for matching. */
  private Pattern excludePattern() {
    List<String> argv = monitor.watchArgv();
    int i = argv.indexOf("--exclude");
    assertTrue(i >= 0 && i + 1 < argv.size(), "watch must carry an --exclude pattern");
    return Pattern.compile(argv.get(i + 1));
  }

  private static void assertMatches(Pattern p, String path) {
    assertTrue(p.matcher(path).find(), path + " should be excluded from the watch");
  }

  private static void assertNoMatch(Pattern p, String path) {
    assertFalse(p.matcher(path).find(), path + " should stay watched");
  }
}
