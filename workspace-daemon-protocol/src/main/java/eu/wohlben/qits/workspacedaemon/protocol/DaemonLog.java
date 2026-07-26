package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * A line of {@code workspace-daemon}'s own stdout/stderr or a structured event, streamed home so a
 * crashing or misbehaving client is visible in qits without {@code docker logs}. The thin-client
 * streaming pattern later parts reuse for daemon/command output.
 */
public record DaemonLog(String level, String message) implements DaemonMessage {}
