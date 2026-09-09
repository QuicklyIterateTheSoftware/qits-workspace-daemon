package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.AgentDefaults;
import eu.wohlben.qits.agents.AgentPromptTemplate;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resolves the agent preferences the host used to read from its {@code setting} table, and answers
 * the two things only this container can: the per-surface configuration document it was born with,
 * and what it knows about itself.
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
  private final AgentSurfaceConfigurations surfaces;
  private final Map<String, String> ambientFacts;

  DaemonAgentDefaults(
      Supplier<DaemonQitsConfig> config,
      Optional<String> daemonDefault,
      boolean activityTrackingDefault,
      Optional<String> refinementModel,
      AgentSurfaceConfigurations surfaces,
      Map<String, String> ambientFacts) {
    this.config = config;
    this.daemonDefault = AgentType.parse(daemonDefault.orElse(null)).orElse(AgentType.CLAUDE);
    this.activityTrackingDefault = activityTrackingDefault;
    this.refinementModel = refinementModel;
    this.surfaces = surfaces == null ? AgentSurfaceConfigurations.shipped() : surfaces;
    this.ambientFacts = ambientFacts == null ? Map.of() : Map.copyOf(ambientFacts);
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

  /**
   * The document this container was created with — read <b>once at boot</b> by {@link ControlSocket}
   * and held, never re-read per launch.
   *
   * <p>A container keeps what it was born with: the bytes arrive in its environment, are written to
   * a file before anything else starts, and are parsed there. An edit in qits-projects applies to the
   * next container and nothing here goes looking for it, which is what keeps a launch a pure local
   * render with no runtime dependency on the store. Absent is not an error — it means a container
   * created before this shipped, and every surface answers the library's shipped constants.
   */
  @Override
  public AgentSurfaceConfigurations surfaceConfigurations() {
    return surfaces;
  }

  /**
   * What this container knows about itself, for a configured initial prompt's placeholders.
   *
   * <p>Assembled once at boot from the identity the host injected, because none of it changes for the
   * life of a container. The names are {@link AgentPromptTemplate#NAMES} minus {@code branch} and
   * {@code commit}, which the library answers itself off {@link WorkspaceContext}.
   */
  @Override
  public Map<String, String> ambientFacts() {
    return ambientFacts;
  }

  /**
   * The facts a workspace container can answer, from what the host injected at creation.
   *
   * <p>Three are told outright and two are <b>read off the branch</b>, which needs stating because it
   * is a convention rather than an injected value: qits-projects' "Start implementation" stands a
   * workspace on {@code epic/<slug>} and its ticket dispatch cuts {@code ticket/<slug>}, so the
   * branch is the only place this container is told which epic or ticket it exists for. Nothing else
   * carries it — the container's environment names a workspace, a repository and a project and stops
   * there.
   *
   * <p>The cost of getting it wrong is bounded by design: an absent fact leaves its placeholder
   * <em>literal</em> ({@code {{ticket}}} stays {@code {{ticket}}}), so an ad-hoc workspace on {@code
   * main} renders a prompt an agent can see has a hole in it, rather than one that reads as if a fact
   * were known. That is why this may derive at all — a wrong guess would be worse than an absence,
   * and a branch prefix is not a guess.
   *
   * @param projectId the project this container serves, or blank
   * @param repoName the repository's project-scoped name, falling back to its id
   * @param repoId the repository id, used when there is no name
   * @param workspaceId this workspace's id, or blank
   * @param branch the branch the container was created on — the boot value, not a live read: a
   *     container that later switches branch keeps the epic or ticket it was cut for, which is the
   *     honest answer to "what is this workspace for"
   */
  static Map<String, String> ambientFactsOf(
      String projectId, String repoName, String repoId, String workspaceId, String branch) {
    Map<String, String> facts = new LinkedHashMap<>();
    put(facts, "project", projectId);
    put(facts, "repository", isBlank(repoName) ? repoId : repoName);
    put(facts, "workspace", workspaceId);
    put(facts, "epic", slugAfter(branch, "epic/"));
    put(facts, "ticket", slugAfter(branch, "ticket/"));
    return Map.copyOf(facts);
  }

  /** The remainder of {@code branch} after {@code prefix}, or null when it does not carry it. */
  private static String slugAfter(String branch, String prefix) {
    if (isBlank(branch) || !branch.startsWith(prefix) || branch.length() == prefix.length()) {
      return null;
    }
    return branch.substring(prefix.length());
  }

  /** Blank is absent: the library treats a blank fact as unresolvable, so never record one. */
  private static void put(Map<String, String> facts, String name, String value) {
    if (!isBlank(value)) {
      facts.put(name, value.trim());
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private DaemonQitsConfig.AgentSection declared() {
    DaemonQitsConfig current = config.get();
    return current == null ? null : current.agent();
  }
}
