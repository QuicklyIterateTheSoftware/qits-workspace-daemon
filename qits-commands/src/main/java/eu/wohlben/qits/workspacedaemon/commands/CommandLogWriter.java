package eu.wohlben.qits.workspacedaemon.commands;

import java.time.Instant;

/**
 * Sink for captured command log lines. Kept framework-free (like {@link CommandOutputSink}) so the
 * session can call it without depending on where the lines end up. The session assigns {@code
 * sequence} and {@code timestamp} at capture time so order and timing are fixed at the source
 * rather than at the store.
 */
public interface CommandLogWriter {

  void append(
      String commandId, long sequence, LogChannel channel, String content, Instant timestamp);
}
