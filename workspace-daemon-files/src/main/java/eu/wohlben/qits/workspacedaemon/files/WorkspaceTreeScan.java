package eu.wohlben.qits.workspacedaemon.files;

import java.util.ArrayList;
import java.util.List;

/**
 * One enumeration of the checkout's repositories: the superproject, and every submodule reachable
 * through a gitlink that has a checkout behind it. This is the single producer of the workspace's
 * eager path list — {@link WorkspaceFileBrowser} renders it, {@link WorkspaceTreeFingerprint}
 * hashes it, and detection re-hashes it — which is what keeps the generation tokens byte-identical
 * across the three without each re-deriving the walk.
 *
 * <p>It exists because {@code git ls-files} stops at a gitlink: in a superproject checkout the
 * command lists each submodule as one <em>path</em> — which the file browser then served as a file
 * that 404s on open — and never lists what is inside it. For a workspace whose content is almost
 * entirely submodules (qits-qits itself), that made the Files tab useless. Here a gitlink whose
 * directory holds a {@code .git} is walked exactly like the superproject, recursively, so its
 * tracked and new untracked files (its own gitignore honoured) join the eager tree under their
 * workspace-relative paths.
 *
 * <p>Uninitialized and broken submodules degrade instead of failing the tree: a gitlink with no
 * {@code .git} behind it, one deeper than {@link #MAX_DEPTH}, or one whose git calls fail (a
 * corrupt checkout an agent left behind) is reported in {@link #uninitializedSubmodules} and its
 * subtree contributes nothing. The browser turns those into collapsed stubs — visible, openable as
 * a plain directory, never a reason the rest of the workspace won't render. Only the
 * <em>superproject's</em> git failure propagates, as everywhere else in this module: a silent empty
 * listing would read as "the workspace has no files".
 *
 * @param eagerPaths every tracked + new untracked file across the superproject and initialized
 *     submodules, workspace-root-relative, gitlink entries removed; blank-filtered, deduped and
 *     sorted exactly as {@link WorkspaceTreeFingerprint#normalize} would
 * @param initializedSubmodules gitlink directories that were walked, workspace-root-relative
 * @param uninitializedSubmodules gitlink directories that were not walked (no checkout, too deep,
 *     or broken), workspace-root-relative
 */
public record WorkspaceTreeScan(
    List<String> eagerPaths,
    List<String> initializedSubmodules,
    List<String> uninitializedSubmodules) {

  /**
   * Nesting this deep is not a real repository layout; the cap only bounds a hand-crafted checkout
   * (submodule directories cannot recurse into themselves on disk, so this is belt over braces).
   */
  private static final int MAX_DEPTH = 10;

  /** Walks the checkout. Two git forks per repository, plus one {@code lstat} per gitlink. */
  public static WorkspaceTreeScan of(WorkspaceFiles files) {
    List<String> eager = new ArrayList<>();
    List<String> initialized = new ArrayList<>();
    List<String> uninitialized = new ArrayList<>();
    walk(files, "", 0, eager, initialized, uninitialized);
    return new WorkspaceTreeScan(
        WorkspaceTreeFingerprint.normalize(eager),
        List.copyOf(initialized),
        List.copyOf(uninitialized));
  }

  private static void walk(
      WorkspaceFiles files,
      String prefix,
      int depth,
      List<String> eager,
      List<String> initialized,
      List<String> uninitialized) {
    // --stage rather than a plain --cached: the mode column is the only way to tell a gitlink
    // (160000) from a file, and the same call yields the tracked paths, so telling them apart
    // costs no extra fork.
    String tracked = lsFiles(files, prefix, "--cached", "--stage");
    String untracked = lsFiles(files, prefix, "--others", "--exclude-standard");

    List<String> gitlinks = new ArrayList<>();
    for (String line : tracked.lines().toList()) {
      int tab = line.indexOf('\t');
      if (tab < 0) {
        continue;
      }
      String path = prefixed(prefix, line.substring(tab + 1));
      if (line.startsWith("160000 ")) {
        gitlinks.add(path);
      } else {
        eager.add(path);
      }
    }
    untracked.lines().filter(line -> !line.isBlank()).forEach(p -> eager.add(prefixed(prefix, p)));

    for (String sub : gitlinks) {
      // ".git" is a file in a `submodule update` checkout and a directory in a standalone clone;
      // either way its presence is what distinguishes a checkout from git's empty placeholder dir.
      if (depth + 1 > MAX_DEPTH || files.stat(sub + "/.git").type() == WorkspaceFiles.EntryType.MISSING) {
        uninitialized.add(sub);
        continue;
      }
      // Buffered so a submodule that fails mid-walk contributes nothing at all — half a submodule
      // in the tree would be indistinguishable from the submodule genuinely holding half its files.
      List<String> subEager = new ArrayList<>();
      List<String> subInitialized = new ArrayList<>();
      List<String> subUninitialized = new ArrayList<>();
      try {
        walk(files, sub, depth + 1, subEager, subInitialized, subUninitialized);
      } catch (RuntimeException broken) {
        uninitialized.add(sub);
        continue;
      }
      initialized.add(sub);
      eager.addAll(subEager);
      initialized.addAll(subInitialized);
      uninitialized.addAll(subUninitialized);
    }
  }

  /**
   * {@code git -C <repo> ls-files <flags>}. {@code -C} rather than a re-rooted seam because the
   * seam's root is also the path-safety boundary — every path this class touches stays expressed
   * workspace-root-relative, and a second root would give the guards two truths. The gitlink path
   * handed to {@code -C} comes from git's own index, which admits no absolute paths and no {@code
   * ..} segments; it is one argv element, so nothing re-tokenizes it.
   */
  private static String lsFiles(WorkspaceFiles files, String prefix, String... flags) {
    List<String> args = new ArrayList<>(List.of("-C", prefix.isEmpty() ? "." : prefix, "ls-files"));
    args.addAll(List.of(flags));
    return files.git(args.toArray(String[]::new));
  }

  private static String prefixed(String prefix, String path) {
    return prefix.isEmpty() ? path : prefix + "/" + path;
  }
}
