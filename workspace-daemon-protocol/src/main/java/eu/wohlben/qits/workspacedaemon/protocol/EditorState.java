package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: the supervised web editor's lifecycle state — what the host
 * gates the editor proxy and its splash on.
 *
 * <p><b>Its presence is the capability announcement.</b> A workspace image without
 * openvscode-server, or with the editor switched off, supervises nothing and therefore sends this
 * frame <em>never</em>: no announcement, no transitions, and a plain workspace behaves exactly as
 * it did before an editor existed. So the host learns "this container has an editor" by being
 * told a state at all, not by reading a flag — one signal instead of two that can disagree. Where
 * supervision is active the daemon sends the current state once per control-socket connect (the
 * reconnect re-report every other in-daemon reporter does) and then one frame per transition.
 *
 * <p>No {@code workspaceId}: like {@link AgentActivity}, this rides a socket the host opened for
 * one workspace and already routes by. {@code state} is a plain String for the reason {@link
 * ServiceTransition}'s is — the framework-free protocol module stays free of the host's display
 * enums, which mirror {@link State} by name.
 */
public record EditorState(String state) implements DaemonMessage {

  /**
   * The {@link #state()} values.
   *
   * <p>Three, not five: the editor is one process the daemon owns outright, so there is no policy
   * outcome to report the way {@link ServiceTransition} reports {@code RESTARTING}/{@code CRASHED}
   * for a checkout-declared dev server. A restart is the editor going back to {@link #STARTING};
   * what the host's proxy needs to know is only whether the port answers yet, and what its splash
   * needs to know is whether it should keep waiting.
   */
  public static final class State {

    /** Spawned (or about to be respawned) and not yet serving. The splash waits. */
    public static final String STARTING = "STARTING";

    /** Listening on its loopback port — a stream to {@link StreamTarget#EDITOR} will be served. */
    public static final String RUNNING = "RUNNING";

    /**
     * Not coming back in this container: the daemon is shutting down, or the editor crash-looped
     * past its restart budget. Terminal, so the splash can stop waiting and say so rather than
     * spinning for the container's lifetime.
     */
    public static final String ENDED = "ENDED";

    private State() {}
  }
}
