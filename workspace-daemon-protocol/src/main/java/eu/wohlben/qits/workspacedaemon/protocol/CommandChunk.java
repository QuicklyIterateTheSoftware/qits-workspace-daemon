package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * One streamed slice of a {@link RunCommand}'s output, tagged with its {@link Stream} and
 * correlated back to the request. Emitted zero-or-more times before the terminal {@link
 * CommandExit}.
 */
public record CommandChunk(String correlationId, Stream stream, String text)
    implements DaemonMessage {}
