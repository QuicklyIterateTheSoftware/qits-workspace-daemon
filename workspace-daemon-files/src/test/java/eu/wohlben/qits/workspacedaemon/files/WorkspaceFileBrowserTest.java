package eu.wohlben.qits.workspacedaemon.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The behavioural contract of the file browser, ported from the host's {@code
 * WorkspaceControllerTest} file-browser cases and run here against a real git repository in a temp
 * directory instead of a container. The status assertions are the point of the port: the host
 * expressed the policy as HTTP codes, and {@link WorkspaceFilesException.Kind} has to keep
 * producing the same ones or the UI's "invalid path" and "no such file" states swap.
 *
 * <p>Weighted deliberately towards the escape cases. A file browser over an <em>untrusted</em>
 * checkout is a path-traversal target by construction: git happily checks out a symlink pointing at
 * {@code /etc}, and the lexical {@code ..} guard cannot see it.
 */
class WorkspaceFileBrowserTest {

  @TempDir Path root;

  /**
   * Stands in for anything outside the workspace — the host filesystem the browser must not reach.
   */
  @TempDir Path outside;

  private LocalWorkspaceFiles files;
  private WorkspaceFileBrowser browser;

  @BeforeEach
  void initRepo() {
    files = new LocalWorkspaceFiles(root);
    files.git("init", "--quiet");
    browser = new WorkspaceFileBrowser(files);
  }

  /** The HTTP status a rejected call produced, so the escape cases read as a table. */
  private static int statusOf(Executable call) {
    return assertThrows(WorkspaceFilesException.class, call).status();
  }

  // --- listing -------------------------------------------------------------------------------

  @Test
  void listFilesIncludesTrackedAndNewUntrackedFiles() throws Exception {
    Files.writeString(root.resolve("browse-me.txt"), "hello\n");

    // a brand-new untracked file shows up (ls-files --others), sorted alongside tracked ones
    assertTrue(browser.listFiles(null).paths().contains("browse-me.txt"));
    assertEquals(browser.listFiles(null).paths(), browser.listFiles("  ").paths());
  }

  @Test
  void listFilesReturnsGitignoredDirectoryAsLazyStub() throws Exception {
    ignoredNodeModules();

    FileListing listing = browser.listFiles(null);

    // the ignored dir is a collapsed stub, not walked into: no node_modules contents in paths
    assertFalse(listing.paths().stream().anyMatch(p -> p.startsWith("node_modules")));
    LazyDir stub = onlyLazyDir(listing, "node_modules");
    // the cheap immediate-child count: pkg/ and top.js, not the nested index.js
    assertEquals(2, stub.childCount());
  }

  @Test
  void listLazyDirectoryContentsOneLevelDeep() throws Exception {
    ignoredNodeModules();

    FileListing listing = browser.listFiles("node_modules");

    // immediate regular files are eager; the nested subdir stays lazy
    assertEquals(List.of("node_modules/top.js"), listing.paths());
    assertEquals(1, onlyLazyDir(listing, "node_modules/pkg").childCount());
  }

  @Test
  void listedLevelSkipsSymlinksRatherThanFollowingThem() throws Exception {
    Files.createDirectory(root.resolve("dir"));
    Files.writeString(root.resolve("dir/real.txt"), "x");
    Files.writeString(outside.resolve("secret.txt"), "top secret");
    Files.createSymbolicLink(root.resolve("dir/link.txt"), outside.resolve("secret.txt"));
    Files.createSymbolicLink(root.resolve("dir/linkdir"), outside);

    FileListing listing = browser.listFiles("dir");

    // a link is neither followed nor surfaced — listing it would advertise the escape
    assertEquals(List.of("dir/real.txt"), listing.paths());
    assertEquals(List.of(), listing.lazyDirs());
  }

  @Test
  void everyListingStampsTheSameWholeTreeGeneration() throws Exception {
    ignoredNodeModules();

    String rootGeneration = browser.listFiles(null).generation();
    // a lazy level holds only its own slice, yet must carry the *tree's* token: the client compares
    // it against detection's, which never knows which level triggered the fetch
    assertEquals(rootGeneration, browser.listFiles("node_modules").generation());

    // structural only — editing a file's contents must not move the token
    Files.writeString(root.resolve("keep.ts"), "changed\n");
    assertEquals(rootGeneration, browser.listFiles(null).generation());

    Files.writeString(root.resolve("added.ts"), "x\n");
    assertNotEquals(rootGeneration, browser.listFiles(null).generation());
  }

  // --- file content --------------------------------------------------------------------------

  @Test
  void fileContentReturnsText() throws Exception {
    Files.writeString(root.resolve("readme.md"), "# Title\n\nbody\n");

    FileContent content = browser.readFile("readme.md");

    assertEquals("readme.md", content.path());
    assertFalse(content.binary());
    assertEquals("# Title\n\nbody\n", content.content());
  }

  @Test
  void fileContentDetectsBinary() throws Exception {
    // A NUL byte marks the file as binary; the viewer gets no content.
    Files.write(root.resolve("blob.bin"), new byte[] {1, 2, 0, 3, 4});

    FileContent content = browser.readFile("blob.bin");

    assertTrue(content.binary());
    assertNull(content.content());
  }

  @Test
  void oversizedFileIsReportedBinaryWithoutEverBeingRead() throws Exception {
    Files.writeString(root.resolve("huge.log"), "small on disk, huge according to stat\n");
    // The cap is checked against the stat, not the bytes — reading first would pull the file into
    // the heap before deciding it was too big to send. A read here fails the test outright.
    WorkspaceFileBrowser strict = new WorkspaceFileBrowser(new InflatedSize(files, 2_000_001L));

    FileContent content = strict.readFile("huge.log");

    assertTrue(content.binary());
    assertNull(content.content());
  }

  @Test
  void missingFileIsNotFound() {
    assertEquals(404, statusOf(() -> browser.readFile("does-not-exist.txt")));
  }

  @Test
  void directoryRequestedAsAFileIsNotFound() throws Exception {
    Files.createDirectory(root.resolve("sub"));

    // "not a file" and "no file" are indistinguishable to the browser UI, as on the host
    assertEquals(404, statusOf(() -> browser.readFile("sub")));
  }

  // --- path safety ---------------------------------------------------------------------------

  @Test
  void lexicallyUnsafePathsAreRejectedBeforeTouchingTheFilesystem() {
    for (String bad :
        List.of("../outside/config", "a/../../escape", "..", "/etc/passwd", "\\etc\\passwd")) {
      assertEquals(400, statusOf(() -> browser.readFile(bad)), bad);
      assertEquals(400, statusOf(() -> browser.listFiles(bad)), bad);
    }
    // a NUL byte truncates the name in any native call it reaches
    assertEquals(400, statusOf(() -> browser.readFile("ok.txt\0.png")));
    assertEquals(400, statusOf(() -> browser.readFile(null)));
  }

  @Test
  void symlinkedFileIsRejectedEvenWhenItsTargetIsInsideTheWorkspace() throws Exception {
    Files.writeString(root.resolve("real.txt"), "safe\n");
    Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("real.txt"));

    // rejected on being a link at all, not on where it points: the browser never dereferences a
    // path a cloned (untrusted) repository controls, so there is no target to re-check later
    assertEquals(400, statusOf(() -> browser.readFile("link.txt")));
  }

  @Test
  void fileContentRejectsSymlinkEscape() throws Exception {
    // A cloned repo is untrusted: a symlink committed inside the workspace that points outside it
    // must not be followed when reading (path traversal via symlink).
    Files.writeString(outside.resolve("secret.txt"), "top secret");
    Files.createSymbolicLink(root.resolve("escape-link"), outside.resolve("secret.txt"));

    assertEquals(400, statusOf(() -> browser.readFile("escape-link")));
  }

  @Test
  void fileContentRejectsIntermediateSymlinkEscape() throws Exception {
    // A symlinked *directory* is transparently followed during path resolution, so a request whose
    // intermediate segment is that link escapes the workspace even though the final segment is an
    // ordinary file — and lstat on the final segment sees nothing wrong.
    Files.writeString(outside.resolve("secret.txt"), "top secret");
    Files.createSymbolicLink(root.resolve("escape-dir"), outside);

    assertEquals(400, statusOf(() -> browser.readFile("escape-dir/secret.txt")));
  }

  @Test
  void listFilesRejectsSymlinkDirectoryEscape() throws Exception {
    Files.createSymbolicLink(root.resolve("escape-dir"), outside);

    assertEquals(400, statusOf(() -> browser.listFiles("escape-dir")));
  }

  @Test
  void listFilesRejectsIntermediateSymlinkEscape() throws Exception {
    // Same escape via an intermediate symlinked directory, but for a listing: the final segment
    // resolves to a real directory outside the workspace, which must not be walked.
    Files.createDirectories(outside.resolve("nested"));
    Files.createSymbolicLink(root.resolve("escape-dir"), outside);

    assertEquals(400, statusOf(() -> browser.listFiles("escape-dir/nested")));
  }

  @Test
  void listFilesRejectsNonDirectoryAndMissingDirectory() throws Exception {
    Files.writeString(root.resolve("a-file.txt"), "hi\n");

    assertEquals(400, statusOf(() -> browser.listFiles("a-file.txt")));
    assertEquals(404, statusOf(() -> browser.listFiles("no-such-dir")));
  }

  @Test
  void listFilesRejectsGitDirectory() {
    // The object store is not browsable content, and it holds credentials in .git/config
    assertEquals(400, statusOf(() -> browser.listFiles(".git")));
    assertEquals(400, statusOf(() -> browser.listFiles(".git/config")));
  }

  // --- wiring --------------------------------------------------------------------------------

  @Test
  void unknownLazinessStrategyFailsAsAnInternalError() {
    WorkspaceFileBrowser misconfigured =
        new WorkspaceFileBrowser(files, List.of(new GitignoreLazyDirectoryStrategy()), "nonesuch");

    // a config typo is the operator's fault, not the caller's — never a 400
    assertEquals(500, statusOf(() -> misconfigured.listFiles(null)));
  }

  // --- fixtures ------------------------------------------------------------------------------

  /** The canonical lazy-boundary fixture: an ignored {@code node_modules} with a nested package. */
  private void ignoredNodeModules() throws Exception {
    Files.writeString(root.resolve(".gitignore"), "node_modules/\n");
    Files.writeString(root.resolve("keep.ts"), "x\n");
    Files.createDirectories(root.resolve("node_modules/pkg"));
    Files.writeString(root.resolve("node_modules/top.js"), "x\n");
    Files.writeString(root.resolve("node_modules/pkg/index.js"), "y\n");
  }

  private static LazyDir onlyLazyDir(FileListing listing, String path) {
    return listing.lazyDirs().stream()
        .filter(d -> d.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no lazy dir " + path + " in " + listing.lazyDirs()));
  }

  /**
   * Delegates everything to the real seam but reports an inflated size and refuses to be read —
   * the only way to prove the size cap short-circuits <em>before</em> the read rather than after.
   */
  private record InflatedSize(WorkspaceFiles delegate, long size) implements WorkspaceFiles {

    @Override
    public String git(String... args) {
      return delegate.git(args);
    }

    @Override
    public Entry stat(String path) {
      Entry entry = delegate.stat(path);
      return new Entry(entry.path(), entry.type(), size, entry.childCount());
    }

    @Override
    public List<Entry> list(String dir) {
      return delegate.list(dir);
    }

    @Override
    public int childCount(String dir) {
      return delegate.childCount(dir);
    }

    @Override
    public boolean resolvesInsideRoot(String path) {
      return delegate.resolvesInsideRoot(path);
    }

    @Override
    public byte[] read(String path) {
      throw new AssertionError("the oversized file must never be read: " + path);
    }
  }
}
