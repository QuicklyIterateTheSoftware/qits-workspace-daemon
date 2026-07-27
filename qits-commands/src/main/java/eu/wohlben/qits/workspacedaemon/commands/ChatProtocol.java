package eu.wohlben.qits.workspacedaemon.commands;

/**
 * The transport half of a live chat, pluggable so one {@link ChatSession} can drive either Claude's
 * <strong>stream-json</strong> pipe (events already in the envelope, {@link
 * StreamJsonChatProtocol}) or Kimi's <strong>ACP</strong> JSON-RPC stdio (normalized into the
 * envelope by an in-JVM client in {@code qits-coding-agents}). The session owns everything
 * transport-agnostic — the scrollback ring, the live broadcast, the log buffer, the exit latch,
 * process-group termination — and delegates only the bytes-on-the-pipe work here.
 *
 * <p>Lifecycle: {@link #start} once (the protocol begins reading the process output and calls
 * {@code onEnd} when that stream closes), {@link #sendUser} per turn, {@link #close} on teardown
 * (before the session kills the process group).
 */
public interface ChatProtocol {

  /**
   * Begins pumping the process output: each conversation event is normalized (if needed) and handed
   * to {@code wire}; when the output stream closes, {@code onEnd} runs exactly once so the session
   * can latch the exit. Non-blocking — the protocol spawns its own reader thread.
   */
  void start(ChatWire wire, Runnable onEnd);

  /** Delivers a user turn to the agent (and echoes it into the stream so the sender sees it). */
  void sendUser(String text);

  /** Releases transport resources (close stdin, send any protocol-level cancel). Best-effort. */
  void close();
}
