package eu.wohlben.qits.workspacedaemon.agents;

/** How an agent launch is driven and rendered. */
public enum AgentLaunchMode {
  /** The stream-json conversation over pipes, rendered as a chat (the default). */
  CHAT,
  /** The full interactive agent TUI over a PTY, rendered in xterm.js. */
  INTERACTIVE
}
