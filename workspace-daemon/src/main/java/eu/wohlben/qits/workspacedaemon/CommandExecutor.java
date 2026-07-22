package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Runs a {@link RunCommand} in the container via {@link ProcessBuilder}, streaming its output back
 * as {@link CommandChunk}s and closing with a {@link CommandExit}. Framework-free (no Vert.x, no
 * CDI) so it unit-tests directly against a collecting {@code Consumer}; {@link ControlSocket} calls
 * it on a worker thread and marshals each emitted message onto the connection's event loop.
 *
 * <p>Part 1 wires no production caller — only the demonstration/extended tests send a {@code
 * RunCommand}. The one behaviour it must guarantee is that a failed spawn (e.g. command not found)
 * still produces a terminal {@link CommandExit} rather than throwing, so a caller's correlation
 * future always completes.
 */
public final class CommandExecutor {

  /** Reads a stream in ~4 KiB slices rather than line-by-line, so binary/partial output flows. */
  private static final int BUFFER_SIZE = 4096;

  private CommandExecutor() {}

  /** Run {@code command}, emitting chunks then a single exit; never throws. */
  public static void run(RunCommand command, Consumer<DaemonMessage> emit) {
    String correlationId = command.correlationId();
    ProcessBuilder builder = new ProcessBuilder(command.argv());
    if (command.cwd() != null && !command.cwd().isBlank()) {
      builder.directory(new File(command.cwd()));
    }
    if (command.env() != null) {
      // Put entries individually, skipping null keys/values: ProcessBuilder.environment().putAll
      // throws NPE on a null value (the wire codec can decode one), which would escape the
      // try/catch
      // below and break the "always emits a terminal CommandExit" guarantee.
      command
          .env()
          .forEach(
              (key, value) -> {
                if (key != null && value != null) {
                  builder.environment().put(key, value);
                }
              });
    }
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      // Spawn failed (bad argv, missing binary): report as stderr + a conventional 127, so the
      // caller still gets a terminal exit.
      emit.accept(new CommandChunk(correlationId, Stream.STDERR, String.valueOf(e.getMessage())));
      emit.accept(new CommandExit(correlationId, 127));
      return;
    }

    // Pump stderr on a side thread while stdout streams on this (worker) thread.
    Thread stderrPump =
        new Thread(
            () -> pump(process.getErrorStream(), correlationId, Stream.STDERR, emit),
            "workspace-daemon-cmd-stderr-" + correlationId);
    stderrPump.setDaemon(true);
    stderrPump.start();

    pump(process.getInputStream(), correlationId, Stream.STDOUT, emit);

    int exitCode;
    try {
      exitCode = process.waitFor();
      stderrPump.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      exitCode = 130; // 128 + SIGINT, the conventional "interrupted" code
    }
    emit.accept(new CommandExit(correlationId, exitCode));
  }

  private static void pump(
      InputStream stream, String correlationId, Stream channel, Consumer<DaemonMessage> emit) {
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
      // Stream closed under us (process died) — nothing more to read; the exit code carries the
      // outcome.
    }
  }
}
