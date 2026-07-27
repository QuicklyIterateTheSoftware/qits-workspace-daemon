package eu.wohlben.qits.workspacedaemon.agents;

import java.util.Optional;

/**
 * The instance-level agent preferences a launch falls back on when the request does not state them.
 *
 * <p>On the host these were rows in a {@code setting} table read through {@code SettingsService}.
 * This daemon keeps nothing beyond the life of its container, so a preference that must outlive a
 * container recreate cannot live here — {@code domain.setting} deliberately stays host-side. What
 * replaces it is a resolution order, implemented by the daemon module:
 *
 * <ol>
 *   <li>the request parameter, when the caller stated one;
 *   <li>the checkout's own {@code .qits-config.yml}, which travels with the repository;
 *   <li>a daemon-level default from configuration.
 * </ol>
 *
 * <p>Only step 1 lives here, in {@link #resolve}; the implementation owns the rest. An interface
 * rather than a class because this module is framework-free and cannot read configuration itself —
 * {@code ControlSocket} is the single reader, exactly as it is for the hook port and the claude
 * mount.
 */
public interface AgentDefaults {

  /** The harness to launch when the request names none. Never null. */
  AgentType defaultAgentType();

  /**
   * Whether to wire the turn-boundary activity hooks (BUSY/IDLE/WAITING/ENDED). The SessionStart
   * lineage hook is emitted regardless — see {@link CodingAgent#activityTracking(boolean)}.
   */
  boolean activityTrackingEnabled();

  /**
   * The model override for prompt refinement, or empty for the harness's own choice. Refinement is a
   * short, cheap, non-interactive call, so a small model is usually right.
   */
  Optional<String> refinementModel();

  /**
   * The harness for this launch: what the request asked for, else the resolved default. The one
   * place a null request parameter becomes a concrete type.
   */
  default AgentType resolve(AgentType requested) {
    return requested != null ? requested : defaultAgentType();
  }
}
