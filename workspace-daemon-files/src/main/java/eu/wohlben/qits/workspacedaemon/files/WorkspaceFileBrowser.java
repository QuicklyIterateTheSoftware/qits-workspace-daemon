package eu.wohlben.qits.workspacedaemon.files;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only browsing of the workspace's files for the file-browser UI: the file list (git-aware, so
 * build artifacts and ignored files stay out) and the text content of a single file. This is the
 * port of the host's {@code WorkspaceFilesService}, and it owns only orchestration, sorting and
 * path-safety policy — the filesystem mechanics live behind {@link WorkspaceFiles}, and the records
 * it returns carry the host DTOs' field names so the JSON the UI sees is unchanged.
 *
 * <p>Two things the host version did are gone, both because the daemon <em>is</em> one workspace:
 * the {@code repoId}/{@code workspaceId} parameters, and the {@code validate()} step that looked up
 * the repository and workspace rows and re-provisioned a missing container before every read. There
 * is nothing to resolve here and no container to ensure — the checkout is the process's own
 * filesystem — so those 404s move to whichever host endpoint routes to this daemon.
 *
 * <p>Everything else is deliberately identical, in particular the order of the guards in {@link
 * #readFile}: lexical rejection, then the {@code lstat} type check, then {@link
 * WorkspaceFiles#resolvesInsideRoot}, then the size cap, then binary sniffing. The order is the
 * policy — each guard assumes the previous one held, and moving the containment check after the
 * read would mean the read already happened.
 */
public final class WorkspaceFileBrowser {

  /** Files larger than this are reported as {@code binary} rather than streamed into the viewer. */
  private static final long MAX_CONTENT_BYTES = 2_000_000L;

  private final WorkspaceFiles files;
  private final List<LazyDirectoryStrategy> lazyStrategies;
  private final String lazinessStrategyId;

  /**
   * @param files the workspace's filesystem seam
   * @param lazyStrategies the available laziness strategies — explicit rather than discovered,
   *     since there is no CDI here and a {@code ServiceLoader} would be one more thing to register
   *     with the native-image builder
   * @param lazinessStrategyId which of them to use; the host's {@code
   *     qits.repositories.file-tree.laziness} property, now passed down by the daemon's config
   */
  public WorkspaceFileBrowser(
      WorkspaceFiles files, List<LazyDirectoryStrategy> lazyStrategies, String lazinessStrategyId) {
    this.files = files;
    this.lazyStrategies = List.copyOf(lazyStrategies);
    this.lazinessStrategyId = lazinessStrategyId;
  }

  /** The default wiring: gitignored directories as the lazy boundary, nothing else registered. */
  public WorkspaceFileBrowser(WorkspaceFiles files) {
    this(files, List.of(new GitignoreLazyDirectoryStrategy()), GitignoreLazyDirectoryStrategy.ID);
  }

  /**
   * Lists a level of the workspace. With no {@code path} this is the root: eager files from {@code
   * git ls-files --cached --others --exclude-standard} (tracked + new untracked, gitignore
   * honoured) plus the directories the active laziness strategy marked as lazy stubs. With a {@code
   * path} it is that one directory's immediate listing (a lazy directory git refuses to walk), so
   * arbitrarily deep lazy nesting resolves through the same call.
   */
  public FileListing listFiles(String path) {
    if (path == null || path.isBlank()) {
      return listRoot();
    }
    return listDirectory(path);
  }

  /** The eager (non-lazy) tree from the workspace root, with the strategy's lazy dirs alongside. */
  private FileListing listRoot() {
    List<String> paths =
        WorkspaceTreeFingerprint.normalize(
            files.git("ls-files", "--cached", "--others", "--exclude-standard"));

    List<LazyDir> lazyDirs =
        strategy().lazyDirectories(files).stream()
            .map(dir -> new LazyDir(dir, files.childCount(dir)))
            .toList();
    // The root already holds the whole-tree ls-files, so fingerprint it directly (no second call).
    return new FileListing(paths, lazyDirs, WorkspaceTreeFingerprint.of(paths));
  }

  /**
   * Lists one directory a single level deep. Immediate regular files become {@code paths};
   * immediate subdirectories become lazy stubs again (the same laziness applies recursively).
   * Symlinks are skipped rather than followed — a symlink committed inside an untrusted workspace
   * must not be walked through. {@code path} is user-supplied, so it is guarded exactly like a file
   * read.
   */
  private FileListing listDirectory(String path) {
    if (path.equals(".git") || path.startsWith(".git/")) {
      throw WorkspaceFilesException.invalidPath("Cannot list the .git directory");
    }
    requireSafeRelativePath(path);
    WorkspaceFiles.Entry stat = files.stat(path);
    switch (stat.type()) {
      case MISSING -> throw WorkspaceFilesException.notFound("Directory not found: " + path);
      // an untrusted in-repo symlink must not redirect the listing outside the workspace
      case SYMLINK -> throw WorkspaceFilesException.invalidPath("Invalid directory path: " + path);
      case FILE, OTHER -> throw WorkspaceFilesException.invalidPath("Not a directory: " + path);
      case DIRECTORY -> {
        // fall through to the listing below
      }
    }
    // The lstat above only vets the final segment; an intermediate symlinked directory (e.g.
    // linkdir/ -> /etc) is transparently followed during path resolution, so confirm the whole path
    // still resolves inside the workspace before we walk it.
    if (!files.resolvesInsideRoot(path)) {
      throw WorkspaceFilesException.invalidPath("Invalid directory path: " + path);
    }

    List<String> eager = new ArrayList<>();
    List<LazyDir> dirs = new ArrayList<>();
    for (WorkspaceFiles.Entry child : files.list(path)) {
      switch (child.type()) {
        case FILE -> eager.add(child.path());
        case DIRECTORY -> dirs.add(new LazyDir(child.path(), child.childCount()));
        default -> {
          // SYMLINK/OTHER skipped — not followed, not surfaced
        }
      }
    }
    eager.sort(Comparator.naturalOrder());
    dirs.sort(Comparator.comparing(LazyDir::path));
    // A lazy level holds only its own slice, so fingerprint the whole tree separately — the client
    // gates on the same token regardless of which level triggered the fetch.
    return new FileListing(eager, dirs, WorkspaceTreeFingerprint.compute(files));
  }

  /** The active laziness strategy, selected by id from the ones the daemon wired in. */
  private LazyDirectoryStrategy strategy() {
    return lazyStrategies.stream()
        .filter(s -> s.id().equals(lazinessStrategyId))
        .findFirst()
        .orElseThrow(
            () ->
                WorkspaceFilesException.internal(
                    "Unknown file-tree laziness strategy: " + lazinessStrategyId));
  }

  /**
   * Reads a single file's working-tree content. {@code path} is user-supplied, so it is lexically
   * guarded against {@code ../} traversal; a committed symlink is rejected outright rather than
   * followed (cloned repositories are untrusted and git checks out symlinks). A file that contains
   * NUL bytes or exceeds {@link #MAX_CONTENT_BYTES} is reported as {@code binary} with no content
   * — a soft degrade rather than an error, because the viewer already renders that state and a
   * 413 would make an ordinary big file look like a failure.
   */
  public FileContent readFile(String path) {
    if (path == null || path.isBlank()) {
      throw WorkspaceFilesException.invalidPath("File path is required");
    }
    requireSafeRelativePath(path);

    WorkspaceFiles.Entry stat = files.stat(path);
    switch (stat.type()) {
      case MISSING, DIRECTORY, OTHER ->
          throw WorkspaceFilesException.notFound("File not found: " + path);
      // a symlink committed inside the (untrusted) workspace is never dereferenced
      case SYMLINK -> throw WorkspaceFilesException.invalidPath("Invalid file path: " + path);
      case FILE -> {
        // fall through to the read below
      }
    }
    // The lstat above only vets the final segment; an intermediate symlinked directory (e.g.
    // linkdir/ -> /etc in `linkdir/passwd`) is transparently followed during path resolution, so
    // confirm the whole path still resolves inside the workspace before we read it.
    if (!files.resolvesInsideRoot(path)) {
      throw WorkspaceFilesException.invalidPath("Invalid file path: " + path);
    }
    // Checked against the stat, not the read, so an oversized file is never pulled into the heap.
    if (stat.size() > MAX_CONTENT_BYTES) {
      return new FileContent(path, null, true);
    }

    byte[] bytes = files.read(path);
    if (isBinary(bytes)) {
      return new FileContent(path, null, true);
    }
    return new FileContent(path, new String(bytes, StandardCharsets.UTF_8), false);
  }

  /**
   * Lexically rejects a user-supplied path that is absolute or contains a {@code ..} segment,
   * before it ever reaches the filesystem. Reads resolve against the workspace root with a relative
   * path, so this guard (plus the outright symlink rejection and the containment check in the
   * callers) is what keeps a request inside the root. The backslash case is not Windows paranoia:
   * it stops a {@code \\?\}-style prefix or a backslash-quoted segment from reaching a resolver
   * that treats it as a separator.
   */
  private static void requireSafeRelativePath(String path) {
    if (path.startsWith("/") || path.startsWith("\\") || path.indexOf('\0') >= 0) {
      throw WorkspaceFilesException.invalidPath("Invalid file path: " + path);
    }
    for (String segment : path.split("/")) {
      if (segment.equals("..")) {
        throw WorkspaceFilesException.invalidPath("Invalid file path: " + path);
      }
    }
  }

  /** A file is treated as binary when any NUL byte appears in it — the usual git heuristic. */
  private static boolean isBinary(byte[] bytes) {
    for (byte b : bytes) {
      if (b == 0) {
        return true;
      }
    }
    return false;
  }
}
