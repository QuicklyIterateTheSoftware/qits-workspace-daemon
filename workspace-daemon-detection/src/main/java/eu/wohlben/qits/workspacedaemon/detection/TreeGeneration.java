package eu.wohlben.qits.workspacedaemon.detection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The workspace's <em>structural</em> generation token: sha256 of the newline-joined, sorted {@code
 * git ls-files} output (tracked + new untracked, gitignore honoured). It changes exactly when the
 * set of paths changes — a file added, removed, or renamed — and stays put across pure content
 * edits. Both the file listing and {@link Detection} stamp it so the client can render tree +
 * detection <em>generation-consistent</em>: it applies detection only when its token matches the
 * files token it is showing, so the user never sees a skewed combination while the two independent
 * fetches settle.
 *
 * <p>A byte-exact port of the host's {@code WorkspaceTreeFingerprint.of} (same normalization, same
 * digest, same lowercase hex), because the two sides' tokens are compared for equality by the same
 * client. Public so whatever serves the file listing computes it from the path list it already
 * holds instead of re-running {@code ls-files}.
 *
 * <p>Deliberately <em>not</em> the working-tree marker: that one also moves on tracked-content
 * edits and is the cache-freshness signal (see {@link DetectionService#detect}); this is
 * structure-only so the token is stable while a file's <em>contents</em> change.
 */
public final class TreeGeneration {

  private TreeGeneration() {}

  /**
   * Fingerprints an already-normalized ({@code ls-files} → blank-filtered, distinct, sorted) path
   * list. The normalization must match on both sides for the two responses' tokens to agree on an
   * identical tree — {@link DetectionService} and the file listing share {@link #normalize}.
   */
  public static String of(List<String> normalizedSortedPaths) {
    return sha256(String.join("\n", normalizedSortedPaths));
  }

  /**
   * The one normalization every producer of the path list owes: drop blanks, dedupe, sort. {@code
   * ls-files --cached --others} can list the same path twice (staged and on disk), and its order is
   * index order, not lexical — both would otherwise make two identical trees hash differently.
   */
  public static List<String> normalize(String lsFilesOutput) {
    return lsFilesOutput.lines().filter(line -> !line.isBlank()).distinct().sorted().toList();
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated on every JVM and is in the native image; unreachable in practice.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
