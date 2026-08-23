package eu.wohlben.qits.workspacedaemon.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks in the default gitignore-based lazy boundary against a real git repository in a temp
 * directory. The point of these assertions is the {@code --directory --no-empty-directory} pair: it
 * is what makes the strategy cheap by contract (git collapses an ignored tree without recursing),
 * and a well-meaning flag change would keep the tests green on the first case while quietly turning
 * {@code node_modules} into thousands of entries.
 */
class GitignoreLazyDirectoryStrategyTest {

  private final GitignoreLazyDirectoryStrategy strategy = new GitignoreLazyDirectoryStrategy();

  @TempDir Path root;

  private LocalWorkspaceFiles files;

  @BeforeEach
  void initRepo() {
    files = new LocalWorkspaceFiles(root);
    files.git("init", "--quiet");
  }

  @Test
  void returnsIgnoredDirectoriesAsTheLazyBoundarySorted() throws Exception {
    Files.writeString(root.resolve(".gitignore"), "node_modules/\ndist/\n");
    Files.createDirectories(root.resolve("node_modules/pkg"));
    Files.writeString(root.resolve("node_modules/pkg/a.js"), "x");
    Files.createDirectories(root.resolve("dist"));
    Files.writeString(root.resolve("dist/b.js"), "y");
    Files.writeString(root.resolve("keep.ts"), "z");

    assertEquals(
        List.of("dist", "node_modules"), strategy.lazyDirectories(files, WorkspaceTreeScan.of(files)));
  }

  @Test
  void collapsesAWhollyIgnoredDirectoryToASingleEntryWithoutRecursing() throws Exception {
    Files.writeString(root.resolve(".gitignore"), "node_modules/\n");
    Files.createDirectories(root.resolve("node_modules/a/b/c"));
    Files.writeString(root.resolve("node_modules/a/b/c/deep.js"), "x");

    List<String> dirs = strategy.lazyDirectories(files, WorkspaceTreeScan.of(files));

    // --directory collapses the whole ignored tree to one stub — nested dirs are NOT enumerated
    assertEquals(List.of("node_modules"), dirs);
    assertFalse(dirs.stream().anyMatch(d -> d.startsWith("node_modules/")));
  }

  @Test
  void ignoresIndividuallyIgnoredFilesAndEmptyDirectories() throws Exception {
    Files.writeString(root.resolve(".gitignore"), "*.log\nbuild/\n");
    Files.writeString(root.resolve("stray.log"), "l");
    Files.createDirectories(root.resolve("build")); // empty → --no-empty-directory drops it

    List<String> dirs = strategy.lazyDirectories(files, WorkspaceTreeScan.of(files));

    // an ignored *file* is not a lazy dir; an empty ignored dir is dropped
    assertTrue(dirs.isEmpty(), () -> "expected no lazy dirs but got " + dirs);
  }

  @Test
  void reportsItsConfigId() {
    assertEquals("gitignore", strategy.id());
  }
}
