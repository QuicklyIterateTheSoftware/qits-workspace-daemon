package eu.wohlben.qits.workspacedaemon.commands;

/**
 * Notified whenever the command list changes — a launch, a status transition, a session report.
 *
 * <p>This is what {@code WorkspaceChangePublisher.fire(repoId, workspaceId, Topic.COMMANDS)}
 * became. On the host that was a payload-free CDI async hint that the workspace SSE stream turned
 * into a nudge for the browser to refetch. It could not be carried across as-is for two reasons,
 * and the second is the load-bearing one.
 *
 * <p>The first is migration-plan.md §5's CDI caveat: a duplicated event type is not the type the
 * original boundary observes, so a verbatim copy compiles, runs and delivers nothing, with no error
 * anywhere. {@code qits-observability} hit exactly this and cut a context-local publisher instead.
 *
 * <p>The second is that the boundary is no longer in-process at all. The observer lives on the
 * host, in {@code qits-workspaces}, and the publisher is now in a container — so this cannot be an
 * event bus of any kind, only an explicit notification the daemon module chooses what to do with.
 * Today that is the control socket; nothing in this module needs to know.
 *
 * <p>Absent (null) is a valid wiring: the commands still run and their state is still queryable,
 * the browser just refetches on its own schedule rather than being nudged.
 */
@FunctionalInterface
public interface CommandChangeListener {

  /** The command list changed. Must not block — it runs on capture and reader threads. */
  void commandsChanged();
}
