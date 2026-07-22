package eu.wohlben.qits.workspacedaemon.protocol;

/** A backend request for a {@link WorkspaceInfo} snapshot, correlated by {@code correlationId}. */
public record Describe(String correlationId) implements DaemonMessage {}
