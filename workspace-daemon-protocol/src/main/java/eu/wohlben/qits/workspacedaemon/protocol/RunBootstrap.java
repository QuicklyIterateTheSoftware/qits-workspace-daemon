package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * qits → {@code workspace-daemon}: run the bootstrap chain again on demand (the manual re-run the
 * Bootstrap tab triggers), correlated by {@code correlationId}. A null/blank {@code name} runs the
 * whole chain in order; a non-blank {@code name} runs just that one step. The daemon replies with
 * the same {@link BootstrapStep}/{@link BootstrapOutcome} stream and a terminal {@link
 * Bootstrapped} as the autonomous boot run does. Unlike the boot run, this is qits-initiated (the
 * autonomous chain runs once, on fresh clone, with no request). docs/epics/qits-workspace-daemon/
 * Part 3.
 */
public record RunBootstrap(String correlationId, String name) implements DaemonMessage {}
