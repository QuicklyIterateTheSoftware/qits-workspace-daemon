package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.GitStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks in {@link GitStatusMonitor}'s decide-and-dedup logic — the container-free half that turns
 * raw {@code git status}/{@code git diff} output into at-most-one {@link GitStatus} report. The
 * marker (status + diff) is the dedup key, so a dirty→dirty content edit still reports (the "files
 * changed" signal) while an unchanged tree stays silent.
 */
class GitStatusMonitorTest {

  private final List<DaemonMessage> sent = new ArrayList<>();
  private final GitStatusMonitor monitor =
      new GitStatusMonitor("ws-1", "repo-1", "feature", "main", sent::add, 250);

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
}
