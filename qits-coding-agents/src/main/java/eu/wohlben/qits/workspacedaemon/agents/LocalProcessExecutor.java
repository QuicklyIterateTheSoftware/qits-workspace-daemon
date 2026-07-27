package eu.wohlben.qits.workspacedaemon.agents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** The real {@link ProcessRunner}: a plain child process of this daemon. */
public final class LocalProcessExecutor implements ProcessRunner {

  @Override
  public Result exec(List<String> command, Path cwd, Map<String, String> env, Duration timeout) {
    ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
    builder.environment().putAll(env);
    try {
      Process process = builder.start();
      // Drain both pipes concurrently so neither can fill its buffer and deadlock the child.
      CompletableFuture<String> stdout = readAsync(process, true);
      CompletableFuture<String> stderr = readAsync(process, false);
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor();
      }
      return new Result(process.exitValue(), stdout.join(), stderr.join(), !finished);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to start process: " + command, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for process: " + command, e);
    }
  }

  private CompletableFuture<String> readAsync(Process process, boolean out) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (var stream = out ? process.getInputStream() : process.getErrorStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        },
        runnable -> Thread.ofVirtual().start(runnable));
  }
}
