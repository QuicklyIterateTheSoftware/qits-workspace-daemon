package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.BootstrapDecl;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapOutcome;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapStep;
import eu.wohlben.qits.workspacedaemon.protocol.Bootstrapped;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs the <b>bootstrap chain</b> (install / migrate / seed) inside the daemon's own startup
 * sequence — between the {@linkplain Provisioner self-clone} and daemon start — driven from the
 * checkout's own {@code .qits-config.yml} ({@link DaemonQitsConfig#bootstrap()}), autonomously,
 * with no per-command instruction from qits (docs/epics/qits-workspace-daemon/ Part 3). Each step
 * runs the optional {@code check} guard (non-zero ⇒ SKIPPED, the command never runs) then the
 * {@code execute}; the chain aborts on the first FAILED step (a dev server on an unbootstrapped
 * checkout would only crash-loop). Every run ends with a terminal {@link Bootstrapped} the host
 * awaits — {@code ok:true} lets the workspace proceed to daemon auto-start, {@code ok:false} gates
 * it off.
 *
 * <p>Framework-free (no Vert.x/CDI/JGit — native-image lean): it forks {@code bash -lc <script>}
 * via {@link ProcessBuilder}, streaming each step's output as {@link CommandChunk}s tagged {@link
 * DaemonProtocol#bootstrapCorrelationId(String)} so the host routes them to that step's {@code
 * bootstrap:<name>} process segment. Modeled on {@link Provisioner}'s streaming helpers, adding a
 * per-step correlation id, the step's environment, and a per-step timeout (a timed-out step is
 * terminated and reported FAILED — a straggling install whose successors were aborted would only
 * fight a re-run). Never throws — a runtime error ends the chain with {@code
 * Bootstrapped{ok:false}}, preserving the daemon's "never exit on failure" invariant.
 */
public final class BootstrapRunner {

  private static final int BUFFER_SIZE = 4096;

  private BootstrapRunner() {}

  /**
   * Run {@code chain} (whole chain when {@code onlyName} is null/blank, else just that one step) at
   * {@code workingDir}, emitting {@link BootstrapStep}/{@link BootstrapOutcome} per step and a
   * terminal {@link Bootstrapped}. {@code stepTimeoutMs} bounds each {@code check}/{@code execute}.
   * Returns whether the chain succeeded, so the boot sequence can gate service auto-start on it.
   */
  public static boolean run(
      String workspaceId,
      List<BootstrapDecl> chain,
      String onlyName,
      File workingDir,
      long stepTimeoutMs,
      Consumer<DaemonMessage> emit) {
    boolean ok = true;
    try {
      boolean single = onlyName != null && !onlyName.isBlank();
      for (BootstrapDecl step : chain) {
        if (single && !onlyName.equals(step.name())) {
          continue;
        }
        String outcome = runStep(workspaceId, step, workingDir, stepTimeoutMs, emit);
        if (BootstrapOutcome.Result.FAILED.equals(outcome)) {
          ok = false;
          break; // fail-fast: a failed step aborts the rest of the chain
        }
      }
    } catch (RuntimeException e) {
      emit.accept(new DaemonLog("ERROR", "bootstrap chain error: " + e.getMessage()));
      ok = false;
    }
    emit.accept(new Bootstrapped(workspaceId, ok));
    return ok;
  }

  /**
   * Run one step: the optional {@code check} (non-zero ⇒ SKIPPED), else {@code execute}. Returns
   * the {@link BootstrapOutcome.Result} vocabulary string.
   */
  private static String runStep(
      String workspaceId,
      BootstrapDecl step,
      File workingDir,
      long stepTimeoutMs,
      Consumer<DaemonMessage> emit) {
    String name = step.name();
    String corr = DaemonProtocol.bootstrapCorrelationId(name);
    if (step.execute() == null || step.execute().isBlank()) {
      emit.accept(
          new DaemonLog("WARN", "bootstrap step '" + name + "' has no execute — failing it."));
      emit.accept(new BootstrapStep(workspaceId, name, BootstrapStep.Phase.EXECUTE));
      emit.accept(new BootstrapOutcome(workspaceId, name, BootstrapOutcome.Result.FAILED, -1));
      return BootstrapOutcome.Result.FAILED;
    }
    if (step.check() != null && !step.check().isBlank()) {
      emit.accept(new BootstrapStep(workspaceId, name, BootstrapStep.Phase.CHECK));
      int checkExit =
          runStreaming(step.check(), step.environment(), workingDir, corr, stepTimeoutMs, emit);
      if (checkExit != 0) {
        emit.accept(new BootstrapStep(workspaceId, name, BootstrapStep.Phase.SKIP));
        emit.accept(
            new BootstrapOutcome(workspaceId, name, BootstrapOutcome.Result.SKIPPED, checkExit));
        return BootstrapOutcome.Result.SKIPPED;
      }
    }
    emit.accept(new BootstrapStep(workspaceId, name, BootstrapStep.Phase.EXECUTE));
    int exit =
        runStreaming(step.execute(), step.environment(), workingDir, corr, stepTimeoutMs, emit);
    String outcome = exit == 0 ? BootstrapOutcome.Result.SUCCEEDED : BootstrapOutcome.Result.FAILED;
    emit.accept(new BootstrapOutcome(workspaceId, name, outcome, exit));
    return outcome;
  }

  /**
   * Fork {@code bash -lc <script>} at {@code workingDir} with {@code environment} overlaid,
   * streaming stdout+stderr as {@link CommandChunk}s tagged {@code correlationId}, and return its
   * exit code. A step exceeding {@code timeoutMs} is force-terminated and reported as exit {@code
   * 124} (the timeout convention) ⇒ FAILED.
   */
  static int runStreaming(
      String script,
      Map<String, String> environment,
      File workingDir,
      String correlationId,
      long timeoutMs,
      Consumer<DaemonMessage> emit) {
    ProcessBuilder builder = new ProcessBuilder("bash", "-lc", script);
    if (workingDir != null && workingDir.isDirectory()) {
      builder.directory(workingDir);
    }
    if (environment != null) {
      Map<String, String> env = builder.environment();
      environment.forEach(
          (key, value) -> {
            if (value != null) {
              env.put(key, value);
            }
          });
    }
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      emit.accept(new CommandChunk(correlationId, Stream.STDERR, String.valueOf(e.getMessage())));
      return 127;
    }
    // Both pumps run on their own threads so the calling thread is free to enforce the timeout:
    // pump() blocks on read until the stream closes (the process exits), so pumping stdout inline
    // would defer waitFor(timeout) until the process is already done — the timeout would never
    // fire.
    Thread stdoutPump = pumpThread(process.getInputStream(), Stream.STDOUT, correlationId, emit);
    Thread stderrPump = pumpThread(process.getErrorStream(), Stream.STDERR, correlationId, emit);
    stdoutPump.start();
    stderrPump.start();
    try {
      if (timeoutMs > 0 && !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor();
        stdoutPump.join();
        stderrPump.join();
        emit.accept(
            new CommandChunk(
                correlationId,
                Stream.STDERR,
                "bootstrap step timed out after " + timeoutMs + "ms — terminated.\n"));
        return 124;
      }
      int exit = process.waitFor();
      stdoutPump.join();
      stderrPump.join();
      return exit;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return 130;
    }
  }

  private static Thread pumpThread(
      InputStream stream, Stream channel, String correlationId, Consumer<DaemonMessage> emit) {
    Thread thread =
        new Thread(
            () -> pump(stream, channel, correlationId, emit),
            "workspace-daemon-bootstrap-" + channel.name().toLowerCase(java.util.Locale.ROOT));
    thread.setDaemon(true);
    return thread;
  }

  private static void pump(
      InputStream stream, Stream channel, String correlationId, Consumer<DaemonMessage> emit) {
    byte[] buffer = new byte[BUFFER_SIZE];
    try (stream) {
      int read;
      while ((read = stream.read(buffer)) != -1) {
        if (read > 0) {
          emit.accept(
              new CommandChunk(
                  correlationId, channel, new String(buffer, 0, read, StandardCharsets.UTF_8)));
        }
      }
    } catch (IOException e) {
      // Stream closed under us (process died) — the exit code carries the outcome.
    }
  }
}
