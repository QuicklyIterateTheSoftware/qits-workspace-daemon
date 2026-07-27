package eu.wohlben.qits.workspacedaemon.commands;

/**
 * Notified once, on the reader thread, when a registry process ends — whether it exited on its own
 * or was explicitly terminated. The single place command status transitions away from {@code
 * RUNNING}.
 */
@FunctionalInterface
public interface CommandExitListener {

  /**
   * @param commandId the command whose process ended
   * @param exitCode the OS exit code (best-effort; a forcibly-killed process reports its signal
   *     code)
   * @param terminatedManually true if the end was caused by {@code terminate()}, false if self-exit
   */
  void onExit(String commandId, int exitCode, boolean terminatedManually);
}
