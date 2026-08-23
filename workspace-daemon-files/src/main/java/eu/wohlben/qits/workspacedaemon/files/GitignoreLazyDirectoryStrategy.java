package eu.wohlben.qits.workspacedaemon.files;

import java.util.ArrayList;
import java.util.List;

/**
 * The default lazy-directory strategy: gitignored directories are the lazy boundary, so {@code
 * node_modules/}, {@code dist/}, build output and the like become collapsed stubs instead of
 * flooding the file list. This keeps the out-of-the-box tree cheap (the expensive dirs are present
 * as openable stubs rather than being silently dropped by {@code --exclude-standard}).
 *
 * <p>Implemented with {@code git ls-files --others --ignored --exclude-standard --directory
 * --no-empty-directory}: the {@code --directory} flag makes git collapse a wholly-ignored directory
 * into a single {@code node_modules/} entry <em>without recursing into it</em> — exactly the cheap
 * lazy boundary we want. Trailing-slash entries are directories; individually-ignored files (no
 * trailing slash) are left hidden, as before.
 */
public final class GitignoreLazyDirectoryStrategy implements LazyDirectoryStrategy {

  /** The id {@link WorkspaceFileBrowser} selects this strategy by, and the daemon's default. */
  public static final String ID = "gitignore";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public List<String> lazyDirectories(WorkspaceFiles files, WorkspaceTreeScan scan) {
    List<String> dirs;
    try {
      dirs = new ArrayList<>(ignoredDirectories(files, ""));
    } catch (Exception e) {
      // Wrapped rather than propagated: a strategy failure is an internal fault of the listing, not
      // something the caller's path could have caused, whatever the seam happened to throw.
      throw WorkspaceFilesException.internal(
          "Failed to resolve lazy directories: " + e.getMessage());
    }
    // Each submodule's own gitignore draws its own lazy boundary — a node_modules three submodules
    // deep is exactly as much noise as one at the root. A failing submodule contributes no stubs
    // rather than failing the listing: the scan vetted its checkout, so a failure here is a race
    // with an agent rewriting it, and the next listing sees the settled state.
    for (String submodule : scan.initializedSubmodules()) {
      try {
        dirs.addAll(ignoredDirectories(files, submodule));
      } catch (Exception raced) {
        // nothing to add
      }
    }
    return dirs.stream().distinct().sorted().toList();
  }

  /** One repository's wholly-ignored directories, workspace-root-relative ({@code ""} = the root). */
  private static List<String> ignoredDirectories(WorkspaceFiles files, String prefix) {
    String output =
        files.git(
            "-C",
            prefix.isEmpty() ? "." : prefix,
            "ls-files",
            "--others",
            "--ignored",
            "--exclude-standard",
            "--directory",
            "--no-empty-directory");
    return output
        .lines()
        .filter(line -> line.endsWith("/"))
        .map(line -> line.substring(0, line.length() - 1))
        .filter(line -> !line.isBlank())
        .map(line -> prefix.isEmpty() ? line : prefix + "/" + line)
        .toList();
  }
}
