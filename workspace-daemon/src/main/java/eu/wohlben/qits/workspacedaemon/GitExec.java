package eu.wohlben.qits.workspacedaemon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs one {@code git} command in a stated directory and keeps stdout and stderr apart.
 *
 * <p>The agent-worktree code parses stdout ({@code worktree list --porcelain}, {@code rev-parse})
 * and must not see a warning in it; {@link Out#message()} joins both for a log or a push
 * classification.
 *
 * <p>{@code env} is laid over the daemon's own environment for this one process. That is how an
 * agent's push carries the agent's credential and nothing else ever sees it: it is never written to
 * disk or to git config (D18).
 */
final class GitExec {

  /** One finished git process. */
  record Out(int exit, String stdout, String stderr) {
    boolean ok() {
      return exit == 0;
    }

    /** stdout without its trailing newline. */
    String line() {
      return stdout.strip();
    }

    /** stdout and stderr on one line, for a log or an error message. */
    String message() {
      return (stdout + " " + stderr).strip().replace('\n', ' ');
    }
  }

  private static final long TIMEOUT_SECONDS = 300;

  private GitExec() {}

  static Out git(Path dir, String... args) {
    return git(dir, Map.of(), args);
  }

  static Out git(Path dir, Map<String, String> env, String... args) {
    List<String> argv = new ArrayList<>(args.length + 1);
    argv.add("git");
    argv.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(argv);
    if (dir != null && dir.toFile().isDirectory()) {
      builder.directory(dir.toFile());
    }
    builder.environment().putAll(env);
    // Never wait on a prompt: a missing credential must fail, not hang the sync thread.
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      return new Out(127, "", String.valueOf(e.getMessage()));
    }
    CompletableFuture<String> stderr =
        CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
    String stdout = read(process.getInputStream());
    try {
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new Out(-1, stdout, "timed out after " + TIMEOUT_SECONDS + "s");
      }
      return new Out(process.exitValue(), stdout, stderr.join());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return new Out(130, stdout, "interrupted");
    }
  }

  private static String read(InputStream stream) {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      // The process died under us; its exit code carries the outcome.
      return "";
    }
  }
}
