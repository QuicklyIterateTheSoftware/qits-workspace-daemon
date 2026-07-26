package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * qits → {@code workspace-daemon}: deliver a signal to one running workspace service (typically the
 * stop request), correlated by {@code correlationId}. {@code id} is the service name; {@code
 * signal} is the bare name ({@code "TERM"}, {@code "KILL"}, {@code "HUP"}, …) the daemon prefixes
 * with {@code SIG} for {@code pkill}. The daemon marks the service stop-requested (so its restart
 * policy does not resurrect it), signals the whole session group, and reports the resulting {@link
 * ServiceTransition} transition. docs/epics/qits-workspace-daemon/ Part 4.
 */
public record SignalService(String correlationId, String id, String signal)
    implements DaemonMessage {}
