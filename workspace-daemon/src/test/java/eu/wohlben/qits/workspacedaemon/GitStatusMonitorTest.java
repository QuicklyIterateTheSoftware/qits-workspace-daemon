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
}
