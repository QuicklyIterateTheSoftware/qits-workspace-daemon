package eu.wohlben.qits.workspacedaemon.commands;

/**
 * A destination for a command's terminal output — the registry fans every chunk of PTY output out
 * to all attached sinks. Kept framework-free (no websocket type) so this module stays a plain
 * library; the daemon module's websocket handler adapts a connection to a sink.
 */
public interface CommandOutputSink {

  /**
   * Forward a chunk of already terminal-encoded output to the client (written verbatim to xterm).
   */
  void write(String data);

  /** Whether this sink can still receive output; the registry prunes sinks that report false. */
  boolean isOpen();
}
