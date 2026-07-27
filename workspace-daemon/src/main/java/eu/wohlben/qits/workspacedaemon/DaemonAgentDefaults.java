package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.agents.AgentDefaults;
import eu.wohlben.qits.workspacedaemon.agents.AgentType;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resolves the agent preferences the host used to read from its {@code setting} table.
 *
 * <p>Order is <em>request parameter &gt; the checkout's {@code .qits-config.yml} &gt; a daemon
 * default</em>. The request half lives in {@link AgentDefaults#resolve}; this supplies the other two.
 *
 * <p>The middle step is the interesting one. This daemon keeps nothing beyond its container, so a
 * preference cannot live here — but it can live in the repository, and a repository-scoped
 * {@code agent:} section travels with the checkout, survives every recreate, and is reviewable in a
 * diff. That is the same direction V42–V45 already took repo-scoped configuration off the host
 * database. What genuinely belongs to the <em>user</em> rather than the repository stays host-side:
 * {@code domain.setting} is not extracted, and {@code GET·PUT /api/settings} stay open questions.
 *
 * <p>The config is read through a {@link Supplier} rather than captured, so an agent editing
 * {@code .qits-config.yml} in its own workspace takes effect on the next launch — the same reason
 * {@link ConfigActionResolver} holds one.
 */
final class DaemonAgentDefaults implements AgentDefaults {

  private final Supplier<DaemonQitsConfig> config;
  private final AgentType daemonDefault;
  private final boolean activityTrackingDefault;
  private final Optional<String> refinementModel;

  DaemonAgentDefaults(
      Supplier<DaemonQitsConfig> config,
      Optional<String> daemonDefault,
      boolean activityTrackingDefault,
      Optional<String> refinementModel) {
    this.config = config;
    this.daemonDefault = AgentType.parse(daemonDefault.orElse(null)).orElse(AgentType.CLAUDE);
    this.activityTrackingDefault = activityTrackingDefault;
    this.refinementModel = refinementModel;
  }

  @Override
  public AgentType defaultAgentType() {
    return AgentType.parse(declared() == null ? null : declared().defaultType())
        .orElse(daemonDefault);
  }

  @Override
  public boolean activityTrackingEnabled() {
    DaemonQitsConfig.AgentSection declared = declared();
    return declared == null || declared.activityTracking() == null
        ? activityTrackingDefault
        : declared.activityTracking();
  }

  @Override
  public Optional<String> refinementModel() {
    DaemonQitsConfig.AgentSection declared = declared();
    if (declared != null && declared.refinementModel() != null
        && !declared.refinementModel().isBlank()) {
      return Optional.of(declared.refinementModel());
    }
    return refinementModel.filter(model -> !model.isBlank());
  }

  private DaemonQitsConfig.AgentSection declared() {
    DaemonQitsConfig current = config.get();
    return current == null ? null : current.agent();
  }
}
