package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import org.junit.jupiter.api.Test;

/**
 * Locks in the {@code git status --porcelain=v2 --branch} parsing that lets a single git fork
 * report both HEAD and the dirty flag — the container-free half of {@link
 * WorkspaceDescriber#describe}.
 */
class WorkspaceDescriberTest {

  private static WorkspaceInfo parse(String statusV2) {
    return WorkspaceDescriber.parse("ws-1", "repo-1", "feature", "main", statusV2);
  }

  @Test
  void cleanTreeReadsHeadAndIsNotDirty() {
    String out =
        """
        # branch.oid 1a2b3c4d5e6f
        # branch.head feature
        # branch.upstream origin/feature
        """;
    WorkspaceInfo info = parse(out);
    assertEquals("1a2b3c4d5e6f", info.head());
    assertFalse(info.dirty());
    assertEquals("ws-1", info.workspaceId());
    assertEquals("feature", info.branch());
  }

  @Test
  void anEntryLineMarksTheTreeDirty() {
    String out =
        """
        # branch.oid 1a2b3c4d5e6f
        # branch.head feature
        1 .M N... 100644 100644 100644 aaa bbb readme.md
        ? untracked.txt
        """;
    WorkspaceInfo info = parse(out);
    assertEquals("1a2b3c4d5e6f", info.head());
    assertTrue(info.dirty());
  }

  @Test
  void unbornBranchHasBlankHead() {
    WorkspaceInfo info = parse("# branch.oid (initial)\n# branch.head (detached)\n");
    assertEquals("", info.head());
    assertFalse(info.dirty());
  }

  @Test
  void blankOutputYieldsBlankHeadAndNotDirty() {
    WorkspaceInfo info = parse("");
    assertEquals("", info.head());
    assertFalse(info.dirty());
  }
}
