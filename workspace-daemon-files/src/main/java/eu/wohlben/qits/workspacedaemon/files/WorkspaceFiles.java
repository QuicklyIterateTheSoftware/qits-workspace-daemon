package eu.wohlben.qits.workspacedaemon.files;

import java.util.List;

/**
 * The small set of read-only filesystem primitives the workspace file browser and the detection
 * services need, over the checkout this daemon owns.
 *
 * <p>This is the in-container counterpart of the host's former {@code WorkspaceFileAccess}. Two
 * things changed in the move and both are the point of it:
 *
 * <ul>
 *   <li>No {@code repoId}/{@code workspaceId} parameters. The daemon <em>is</em> one workspace;
 *       there is nothing to resolve, no 404, and no container to ensure first.
 *   <li>The default implementation is {@code java.nio}, not {@code docker exec find/cat/realpath}.
 *       That retires a per-call process spawn, the parsing of {@code find} output, and an
 *       argv-injection surface — which is why detection and the component map, whose whole cost was
 *       N small reads per cache miss, moved here with it.
 * </ul>
 *
 * <p>All paths are relative to the workspace root; callers still owe the lexical {@code
 * ..}/absolute-escape check before calling, and {@link #resolvesInsideRoot} for the symlink guard
 * that the lexical check cannot provide.
 */
public interface WorkspaceFiles {

  /** The kind of a single entry, with symlinks unfollowed. */
  enum EntryType {
    FILE,
    DIRECTORY,
    SYMLINK,
    OTHER,
    MISSING
  }

  /**
   * One filesystem entry. {@code path} is workspace-root-relative; {@code size} is the byte size for
   * files (0 otherwise); {@code childCount} is the immediate-child count for directories (0
   * otherwise), populated by {@link #list} for the subdirectories it returns.
   */
  record Entry(String path, EntryType type, long size, int childCount) {}

  /**
   * Runs {@code git <args>} in the workspace root and returns its combined stdout, throwing on a
   * non-zero exit. Used for the git-aware listings (tracked + untracked, ignored dirs) the browser
   * and the lazy-directory strategy need.
   */
  String git(String... args);

  /**
   * The type and size of a single path <em>without following symlinks</em>. Returns an entry with
   * {@link EntryType#MISSING} (and zero size/childCount) when the path does not exist.
   */
  Entry stat(String path);

  /**
   * Lists a directory one level deep. Immediate regular files and symlinks are returned with their
   * type; immediate subdirectories carry their {@code childCount}. Symlinked directories are
   * reported as {@link EntryType#SYMLINK} and never descended into.
   */
  List<Entry> list(String dir);

  /** The cheap immediate-child count of a directory (one level, never a recursive walk). */
  int childCount(String dir);

  /**
   * Whether {@code path} resolves — following <em>every</em> intermediate and final symlink — to a
   * location still inside the workspace root. This is the containment guard the lexical {@code ..}
   * check cannot provide: a committed symlink at any path segment that points outside the workspace
   * is rejected. A missing path, or a broken or looping symlink, resolves to {@code false}. Cloned
   * repositories are untrusted and git checks out symlinks, so this must run before any read that
   * would otherwise dereference an intermediate link.
   */
  boolean resolvesInsideRoot(String path);

  /**
   * The raw bytes of a regular file, read exactly so binary content and line endings survive. The
   * caller enforces the size limit (via {@link #stat}) before reading and does binary sniffing on
   * the returned bytes.
   */
  byte[] read(String path);
}
