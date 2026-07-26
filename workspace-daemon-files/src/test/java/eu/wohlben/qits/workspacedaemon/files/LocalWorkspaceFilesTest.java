package eu.wohlben.qits.workspacedaemon.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.files.WorkspaceFiles.Entry;
import eu.wohlben.qits.workspacedaemon.files.WorkspaceFiles.EntryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the {@code java.nio} seam against a real temp tree — symlinks, a directory outside the
 * root, and raw bytes. These are the primitives every path guard is built on, so they are asserted
 * directly rather than only through {@link WorkspaceFileBrowser}: a {@code stat} that followed
 * links, or a {@code resolvesInsideRoot} that string-compared prefixes, would silently defeat the
 * browser's checks while all of its own tests still passed.
 */
class LocalWorkspaceFilesTest {

  @TempDir Path root;

  /** A directory deliberately outside the workspace root — the target every escape aims at. */
  @TempDir Path outside;

  private LocalWorkspaceFiles files() {
    return new LocalWorkspaceFiles(root);
  }

  private static Optional<Entry> find(List<Entry> entries, String path) {
    return entries.stream().filter(e -> e.path().equals(path)).findFirst();
  }

  @Test
  void statTypesEntriesWithoutFollowingSymlinks() throws Exception {
    Files.writeString(root.resolve("a.txt"), "hello");
    Files.createDirectory(root.resolve("sub"));
    Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("a.txt"));

    LocalWorkspaceFiles files = files();
    Entry file = files.stat("a.txt");
    assertEquals(EntryType.FILE, file.type());
    assertEquals(5, file.size());

    assertEquals(EntryType.DIRECTORY, files.stat("sub").type());
    // the link resolves to a perfectly ordinary file; only lstat semantics keep it a SYMLINK
    assertEquals(EntryType.SYMLINK, files.stat("link.txt").type());
    assertEquals(EntryType.MISSING, files.stat("nope.txt").type());
    assertEquals(0, files.stat("nope.txt").size());
  }

  @Test
  void listReturnsRootRelativeImmediateChildrenWithSubdirectoryCounts() throws Exception {
    Files.createDirectories(root.resolve("node_modules/pkg"));
    Files.writeString(root.resolve("node_modules/top.js"), "x\n");
    Files.writeString(root.resolve("node_modules/pkg/index.js"), "y\n");

    List<Entry> entries = files().list("node_modules");

    assertEquals(2, entries.size());
    Entry file = find(entries, "node_modules/top.js").orElseThrow();
    assertEquals(EntryType.FILE, file.type());
    assertEquals(2, file.size());
    assertEquals(0, file.childCount());

    Entry dir = find(entries, "node_modules/pkg").orElseThrow();
    assertEquals(EntryType.DIRECTORY, dir.type());
    assertEquals(1, dir.childCount());
  }

  @Test
  void listReportsSymlinkedDirectoriesAsLinksAndNeverCountsThroughThem() throws Exception {
    Files.createDirectory(root.resolve("dir"));
    Files.writeString(outside.resolve("one.txt"), "1");
    Files.writeString(outside.resolve("two.txt"), "2");
    Files.createSymbolicLink(root.resolve("dir/escape"), outside);

    Entry link = find(files().list("dir"), "dir/escape").orElseThrow();

    assertEquals(EntryType.SYMLINK, link.type());
    // counting through the link would both cost a walk and leak the size of what's outside
    assertEquals(0, link.childCount());
  }

  @Test
  void childCountCountsOneLevelOnlyAndZeroForUnreadableTargets() throws Exception {
    Files.createDirectories(root.resolve("a/b/c"));
    Files.writeString(root.resolve("a/one.txt"), "1");
    Files.writeString(root.resolve("a/b/deep.txt"), "2");

    LocalWorkspaceFiles files = files();
    // "b" and "one.txt" — the grandchildren under a/b are not counted
    assertEquals(2, files.childCount("a"));
    assertEquals(0, files.childCount("missing"));
  }

  @Test
  void resolvesInsideRootAcceptsOrdinaryPathsAndLinksThatStayInside() throws Exception {
    Files.createDirectories(root.resolve("src/main"));
    Files.writeString(root.resolve("src/main/App.java"), "x");
    Files.createSymbolicLink(root.resolve("inside-link"), root.resolve("src/main/App.java"));

    LocalWorkspaceFiles files = files();
    assertTrue(files.resolvesInsideRoot("src/main/App.java"));
    assertTrue(files.resolvesInsideRoot("src"));
    // a link is not the problem — leaving the root is
    assertTrue(files.resolvesInsideRoot("inside-link"));
    assertTrue(files.resolvesInsideRoot(""));
  }

  @Test
  void resolvesInsideRootRejectsFinalAndIntermediateSymlinkEscapes() throws Exception {
    Files.writeString(outside.resolve("secret.txt"), "top secret");
    Files.createSymbolicLink(root.resolve("escape-file"), outside.resolve("secret.txt"));
    Files.createSymbolicLink(root.resolve("escape-dir"), outside);

    LocalWorkspaceFiles files = files();
    assertFalse(files.resolvesInsideRoot("escape-file"));
    assertFalse(files.resolvesInsideRoot("escape-dir"));
    // the final segment is an ordinary file; only the intermediate link leaves the root
    assertFalse(files.resolvesInsideRoot("escape-dir/secret.txt"));
  }

  @Test
  void resolvesInsideRootRejectsMissingBrokenAndLoopingPaths() throws Exception {
    Files.createSymbolicLink(root.resolve("broken"), root.resolve("gone.txt"));
    Files.createSymbolicLink(root.resolve("loop-a"), root.resolve("loop-b"));
    Files.createSymbolicLink(root.resolve("loop-b"), root.resolve("loop-a"));

    LocalWorkspaceFiles files = files();
    assertFalse(files.resolvesInsideRoot("does-not-exist"));
    // a broken link has no real path, so containment is unprovable — which means "no"
    assertFalse(files.resolvesInsideRoot("broken"));
    assertFalse(files.resolvesInsideRoot("loop-a"));
  }

  @Test
  void resolvesInsideRootIsNotFooledByASiblingSharingTheRootsNamePrefix() throws Exception {
    Path sibling = root.resolveSibling(root.getFileName().toString() + "-evil");
    Files.createDirectories(sibling);
    try {
      Files.writeString(sibling.resolve("secret.txt"), "top secret");
      Files.createSymbolicLink(root.resolve("evil"), sibling);

      // a raw string prefix compare would read "<root>-evil/secret.txt" as being under "<root>"
      assertFalse(files().resolvesInsideRoot("evil/secret.txt"));
    } finally {
      Files.deleteIfExists(sibling.resolve("secret.txt"));
      Files.deleteIfExists(sibling);
    }
  }

  @Test
  void readReturnsRawBytesUnmangled() throws Exception {
    byte[] blob = {1, 2, 0, 3, (byte) 0xFF};
    Files.write(root.resolve("blob.bin"), blob);
    // no trailing newline, CRLF in the middle: a line-joining reader would corrupt both
    Files.write(root.resolve("text.txt"), "a\r\nb".getBytes(StandardCharsets.UTF_8));

    LocalWorkspaceFiles files = files();
    assertArrayEquals(blob, files.read("blob.bin"));
    assertArrayEquals("a\r\nb".getBytes(StandardCharsets.UTF_8), files.read("text.txt"));
  }

  @Test
  void readFailsLoudlyForAMissingFile() {
    WorkspaceFilesException e =
        assertThrows(WorkspaceFilesException.class, () -> files().read("nope.txt"));
    assertEquals(500, e.status());
  }

  @Test
  void gitReturnsCombinedOutputAndThrowsOnNonZeroExit() throws Exception {
    LocalWorkspaceFiles files = files();
    files.git("init", "--quiet");
    Files.writeString(root.resolve("tracked.txt"), "x\n");

    assertTrue(files.git("ls-files", "--others", "--exclude-standard").contains("tracked.txt"));

    // git writes this diagnostic to stderr, so an implementation that only captured stdout would
    // report an empty failure message
    WorkspaceFilesException e =
        assertThrows(WorkspaceFilesException.class, () -> files.git("no-such-subcommand"));
    assertEquals(500, e.status());
    assertTrue(e.getMessage().contains("no-such-subcommand"), e.getMessage());
  }
}
