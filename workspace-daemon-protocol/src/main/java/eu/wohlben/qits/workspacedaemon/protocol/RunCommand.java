package eu.wohlben.qits.workspacedaemon.protocol;

import java.util.List;
import java.util.Map;

/**
 * A backend request to run a command in the container: the argv, working directory (nullable ⇒
 * container default), and extra environment. {@code workspace-daemon} streams the output back as
 * {@link CommandChunk}s and closes with a {@link CommandExit}, all correlated by {@code
 * correlationId}. In Part 1 this is exercised only by the demonstration/extended tests — no
 * production call site sends it.
 */
public record RunCommand(
    String correlationId, List<String> argv, String cwd, Map<String, String> env)
    implements DaemonMessage {}
