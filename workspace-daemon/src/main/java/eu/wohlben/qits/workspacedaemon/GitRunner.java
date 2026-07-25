package eu.wohlben.qits.workspacedaemon;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * A one-shot git invocation seam: runs {@code git …} and returns its exit code plus its combined
 * stdout+stderr. Package-private and functional so {@link OriginSync}'s push/pull decision logic
 * can be unit-tested with a canned runner (no real repository), while production forks the real
 * {@code git} in {@code /workspace} — the same "fork git locally, no {@code docker exec}" model as
 * {@link GitStatusMonitor}/{@link Provisioner}.
 *
 * <p><b>Combined</b> streams on purpose: {@code git push}/{@code git fetch} write their progress
 * and their rejection/lock diagnostics to <em>stderr</em>, and {@link OriginSync} classifies a
 * failed push by matching that text — so stderr must be captured alongside stdout.
 */
@FunctionalInterface
interface GitRunner {

  /** The outcome of one git invocation: its process exit code and combined output. */
  record Result(int exitCode, String output) {
    boolean ok() {
      return exitCode == 0;
    }
  }

  /** Run {@code argv} (expected to start with {@code "git"}) and capture the result. */
  Result run(String... argv);

  /**
   * The production runner: forks {@code argv} in {@code dir} with stderr merged into stdout,
   * bounded by {@code timeoutSeconds}. Any spawn/interrupt failure surfaces as a non-zero {@link
   * Result} so the caller treats it like a failed git command rather than throwing.
   */
  static GitRunner forking(File dir, long timeoutSeconds) {
    return argv -> {
      try {
        Process process =
            new ProcessBuilder(argv)
                .directory(dir.isDirectory() ? dir : null)
                .redirectErrorStream(true)
                .start();
        byte[] out = process.getInputStream().readAllBytes();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          return new Result(-1, "timed out after " + timeoutSeconds + "s");
        }
        return new Result(process.exitValue(), new String(out, StandardCharsets.UTF_8));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Result(-1, "interrupted");
      } catch (Exception e) {
        return new Result(-1, String.valueOf(e.getMessage()));
      }
    };
  }
}
