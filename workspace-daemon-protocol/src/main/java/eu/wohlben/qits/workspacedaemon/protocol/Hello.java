package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * The first frame {@code workspace-daemon} sends on connect: its identity (read from container env
 * the factory injected) plus its {@link DaemonProtocol#CAPABILITY_VERSION}. The backend registers
 * the connection keyed by {@code workspaceId} and replies with {@link Ack}.
 */
public record Hello(
    String workspaceId, String repoId, String branch, String parent, int capabilityVersion)
    implements DaemonMessage {}
