package eu.wohlben.qits.workspacedaemon.protocol;

/** The terminal frame of a {@link RunCommand}: its process exit code. */
public record CommandExit(String correlationId, int exitCode) implements DaemonMessage {}
