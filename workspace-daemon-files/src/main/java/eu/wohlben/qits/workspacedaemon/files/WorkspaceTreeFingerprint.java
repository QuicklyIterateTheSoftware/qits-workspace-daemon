package eu.wohlben.qits.workspacedaemon.files;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The workspace's <em>structural</em> generation token: a hash of the sorted {@code git ls-files}
 * output (tracked + new untracked, gitignore honoured). It changes exactly when the set of paths
 * changes — a file added, removed, or renamed — and stays put across pure content edits.
 *
 * <p>Both the file listing and detection stamp it on their responses so the client can render tree
 * + detection <em>generation-consistent</em>: it applies detection only when its token matches the
 * files token it is showing, so the user never sees a skewed combination while the two independent
 * fetches settle. That is the whole reason this is a shared class rather than a private helper on
 * {@link WorkspaceFileBrowser} — {@code workspace-daemon-detection} must produce a byte-identical
 * token, which means sharing the normalization <em>and</em> the digest, not re-deriving both.
 */
public final class WorkspaceTreeFingerprint {

  private WorkspaceTreeFingerprint() {}

  /**
   * Walks the tree ({@link WorkspaceTreeScan}, submodules included) and fingerprints it. Use when
   * you don't already hold the path list.
   */
  public static String compute(WorkspaceFiles files) {
    return of(WorkspaceTreeScan.of(files).eagerPaths());
  }

  /**
   * The canonical normalization of raw {@code ls-files} output: blank lines dropped, duplicates
   * collapsed (a path can be listed twice when it is both cached and modified), sorted. Every
   * producer of a token must run its paths through this, or two views of an identical tree
   * disagree.
   */
  public static List<String> normalize(String lsFilesOutput) {
    return normalize(lsFilesOutput.lines().toList());
  }

  /** The same normalization over an already-split path list — what {@link WorkspaceTreeScan} runs. */
  public static List<String> normalize(List<String> paths) {
    return paths.stream().filter(path -> !path.isBlank()).distinct().sorted().toList();
  }

  /**
   * Fingerprints an already-{@link #normalize(String) normalized} path list, so a caller that
   * already fetched it (the listing root, detection) fingerprints without a second git call.
   */
  public static String of(List<String> normalizedSortedPaths) {
    return sha256(String.join("\n", normalizedSortedPaths));
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every JDK and is reachable in the native image; unreachable in
      // practice, but the checked exception has to go somewhere.
      throw WorkspaceFilesException.internal("SHA-256 unavailable", e);
    }
  }
}
