package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Answers a {@code Describe} from in-container git: the current {@code HEAD} and whether the tree
 * is dirty, alongside the identity the factory injected. Framework-free; {@link ControlSocket} runs
 * it on a worker thread. A Part-1 stub — if git can't be read (unprovisioned tree), it returns
 * blanks rather than failing.
 */
public final class WorkspaceDescriber {

  /** Where the branch clones live in every workspace container (image {@code WORKDIR}). */
  private static final File WORKSPACE_DIR = new File("/workspace");

  private WorkspaceDescriber() {}

  public static WorkspaceInfo describe(
      String workspaceId, String repoId, String branch, String parent) {
    // One git fork, not two: `status --porcelain=v2 --branch` reports the HEAD oid (in a
    // `# branch.oid` header) AND the dirty state (any non-header line) together.
    return parse(
        workspaceId,
        repoId,
        branch,
        parent,
        capture("git", "status", "--porcelain=v2", "--branch"));
  }

  /**
   * Derive {@code head}/{@code dirty} from {@code git status --porcelain=v2 --branch} output. HEAD
   * is the {@code # branch.oid} value ({@code (initial)} — an unborn branch — maps to blank); the
   * tree is dirty if any non-{@code #} entry line is present. Blank input (git unreadable) yields
   * blank head + not-dirty. Package-private for unit testing without a real git tree.
   */
  static WorkspaceInfo parse(
      String workspaceId, String repoId, String branch, String parent, String statusV2) {
    String head = "";
    boolean dirty = false;
    for (String line : statusV2.split("\n", -1)) {
      if (line.startsWith("# branch.oid ")) {
        String oid = line.substring("# branch.oid ".length()).trim();
        head = oid.equals("(initial)") ? "" : oid;
      } else if (!line.isBlank() && !line.startsWith("#")) {
        dirty = true;
      }
    }
    return new WorkspaceInfo(workspaceId, repoId, branch, parent, head, dirty);
  }

  /** Run a git command in {@code /workspace} and return its stdout, or "" on any failure. */
  private static String capture(String... argv) {
    try {
      // Discard stderr to the OS null rather than a pipe: reading stdout to completion before
      // draining a stderr pipe deadlocks if git fills the ~64KB stderr buffer (many warnings), and
      // the 10s timeout below is only reached after the stdout read returns. DISCARD removes the
      // pipe entirely, so only stdout is read and there is nothing to deadlock on.
      ProcessBuilder builder =
          new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD);
      if (WORKSPACE_DIR.isDirectory()) {
        builder.directory(WORKSPACE_DIR);
      }
      Process process = builder.start();
      byte[] out = process.getInputStream().readAllBytes();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return "";
      }
      return process.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : "";
    } catch (Exception e) {
      return "";
    }
  }
}
