package eu.wohlben.qits.workspacedaemon.commands;

/**
 * Lifecycle state of a launched command in the registry.
 *
 * <p>{@code INTERRUPTED} meant something specific on the host: a command left {@code RUNNING} in
 * the database by a qits restart, reconciled at startup because its OS process had died with the
 * previous JVM. In here the store is not durable — it lives and dies with the daemon process, which
 * lives and dies with the container — so there is no row that can outlive its process and nothing
 * to reconcile. The value is kept because it is part of the response contract the frontend already
 * renders, and because a future durable store would need it again.
 */
public enum CommandStatus {
  /** The process is alive in the registry (a client may or may not be attached). */
  RUNNING,
  /** The process exited on its own; {@code exitCode} holds its status. */
  EXITED,
  /** The user explicitly terminated the process (SIGKILL via the registry). */
  TERMINATED,
  /** Reserved: a command whose process was lost to a restart. Never set by this daemon. */
  INTERRUPTED
}
