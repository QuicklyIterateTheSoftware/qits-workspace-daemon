package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * A periodic liveness ping from {@code workspace-daemon}, so the backend sees a silent-but-alive
 * client.
 */
public record Heartbeat(String workspaceId) implements DaemonMessage {}
