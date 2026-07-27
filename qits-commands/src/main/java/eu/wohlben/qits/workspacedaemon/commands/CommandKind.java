package eu.wohlben.qits.workspacedaemon.commands;

/**
 * How a command's process is driven and rendered. {@link #TERMINAL} is an interactive PTY streamed
 * to xterm.js (shells, {@code claude} in a terminal, one-off runs); {@link #CHAT} is a coding-agent
 * session driven over a line-delimited JSON protocol on plain pipes and rendered as a conversation;
 * {@link #SERVICE} is a supervised long-running process (dev server, watcher) whose lifecycle
 * belongs to {@code ServiceSupervisor} in the daemon module. The frontend routes the command view
 * on this.
 *
 * <p>{@code SERVICE} is carried because the value is part of the response contract the frontend
 * already switches on, not because this module launches services — it does not. On the host,
 * {@code CommandService} had a whole service-launch path; migration-plan.md §3.3 listed it as dead
 * code to drop, and the daemon's {@code ServiceSupervisor} has owned service processes since the
 * host-exec supervisor was removed.
 */
public enum CommandKind {
  TERMINAL,
  CHAT,
  SERVICE
}
