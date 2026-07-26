package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * qits → {@code workspace-daemon}: a request for the workspace's parsed {@code .qits-config.yml}
 * (read in-container from the checkout the daemon self-cloned), correlated by {@code
 * correlationId}. The reply is a {@link ConfigView} echoing the same id. Unlike the Part-1 {@link
 * Describe} → {@link WorkspaceInfo} stub (FIFO-matched), config replies carry the correlation id so
 * concurrent requests never cross. See docs/epics/qits-workspace-daemon/ Part 2 (in-container
 * config discovery).
 */
public record DescribeConfig(String correlationId) implements DaemonMessage {}
