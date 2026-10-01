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
  private final String entityId;
  private final boolean entityBlocked;
  private final String entityTitle;
  private final String entityStatus;

  DaemonAgentDefaults(
      Supplier<DaemonQitsConfig> config,
      Optional<String> daemonDefault,
      boolean activityTrackingDefault,
      Optional<String> refinementModel,
      AgentSurfaceConfigurations surfaces,
      Map<String, String> ambientFacts) {
    this(
        config,
        daemonDefault,
        activityTrackingDefault,
        refinementModel,
        surfaces,
        ambientFacts,
        null);
  }

  DaemonAgentDefaults(
      Supplier<DaemonQitsConfig> config,
      Optional<String> daemonDefault,
      boolean activityTrackingDefault,
      Optional<String> refinementModel,
      AgentSurfaceConfigurations surfaces,
      Map<String, String> ambientFacts,
      String entityId) {
    this(
        config,
        daemonDefault,
        activityTrackingDefault,
        refinementModel,
        surfaces,
        ambientFacts,
        entityId,
        false);
  }

  DaemonAgentDefaults(
      Supplier<DaemonQitsConfig> config,
      Optional<String> daemonDefault,
      boolean activityTrackingDefault,
      Optional<String> refinementModel,
      AgentSurfaceConfigurations surfaces,
      Map<String, String> ambientFacts,
      String entityId,
      boolean entityBlocked) {
    this(
        config,
        daemonDefault,
        activityTrackingDefault,
        refinementModel,
        surfaces,
        ambientFacts,
        entityId,
        entityBlocked,
        null,
        null);
  }

  DaemonAgentDefaults(
      Supplier<DaemonQitsConfig> config,
      Optional<String> daemonDefault,
      boolean activityTrackingDefault,
      Optional<String> refinementModel,
      AgentSurfaceConfigurations surfaces,
      Map<String, String> ambientFacts,
      String entityId,
      boolean entityBlocked,
      String entityTitle,
      String entityStatus) {
    this.config = config;
    this.daemonDefault = AgentType.parse(daemonDefault.orElse(null)).orElse(AgentType.CLAUDE);
    this.activityTrackingDefault = activityTrackingDefault;
    this.refinementModel = refinementModel;
    this.surfaces = surfaces == null ? AgentSurfaceConfigurations.shipped() : surfaces;
    this.ambientFacts = ambientFacts == null ? Map.of() : Map.copyOf(ambientFacts);
    this.entityId = entityId == null ? "" : entityId.trim();
    this.entityBlocked = entityBlocked;
    this.entityTitle = entityTitle == null ? "" : entityTitle.trim();
    this.entityStatus = entityStatus == null ? "" : entityStatus.trim();
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
   * The qualified ticket or epic id ({@code <project-slug>-<number>}, e.g. {@code qits-614}) this
   * container exists for — the <b>boot value</b>, injected as {@code
   * QITS_WORKSPACE_DAEMON_ENTITY_ID} by the host that created the container (qits-workspaces-service
   * for a workspace container, qits-projects-service for a refinement one) and held here for the
   * life of the container, exactly like {@link #ambientFacts()}: a container keeps what it was born
   * with, and nothing here goes looking for a later answer.
   *
   * <p>Empty for every container the host cannot name an entity for — an ad-hoc workspace, the web
   * editor, or one created before the host started injecting this — in which case {@link
   * AgentLaunchService} falls back to its older {@code qits <surface> <branch>} session name. Present,
   * it heads the name {@code [❗]<status square> <id> <title>}, with {@link #entityTitle()} and
   * {@link #entityStatus()} supplying the rest.
   *
   * <p>Deliberately <b>not</b> folded into {@link #ambientFacts()}: the ambient facts are prompt-
   * template placeholders, a surface mirrored by hand in qits-projects-service's editor, and widening
   * that list is a separate, reviewed change this is not making. This answers one specific caller —
   * the Claude Remote Control session name — not a general-purpose fact.
   */
  @Override
  public Optional<String> entityId() {
    return entityId.isBlank() ? Optional.empty() : Optional.of(entityId);
  }

  /**
   * Whether {@link #entityId()} was BLOCKED when this container booted — the boot-time seed for
   * {@code AgentLaunchService}'s blocked flag, which {@code POST /agents/entity} (or the older
   * {@code POST /agents/blocked}) moves from then on through {@code AgentLaunchService.setEntity}. Read here only once, at construction, exactly
   * like {@link #entityId()}: a daemon that restarts inside a container created for an
   * already-blocked ticket needs the marker back without anybody blocking the ticket again, which a
   * default of {@code false} could not give it.
   *
   * <p>Injected as {@code QITS_WORKSPACE_DAEMON_ENTITY_BLOCKED}, absent meaning {@code false} —
   * unlike {@link #entityId()} there is no third state to distinguish from absence, so this is a
   * primitive rather than an {@code Optional}.
   */
  @Override
  public boolean entityBlocked() {
    return entityBlocked;
  }

  /**
   * The title of {@link #entityId()} when this container booted — the boot-time seed for the title
   * half of {@code AgentLaunchService}'s entity facts, which a session name ({@code [❗]<status
   * square> <id> <title>}) is rendered from. Injected as {@code QITS_WORKSPACE_DAEMON_ENTITY_TITLE}
   * and held for the life of the container like {@link #entityId()}; a rename of the ticket after
   * boot arrives through {@code POST /agents/entity}, never by re-reading this. Blank is absent.
   */
  @Override
  public Optional<String> entityTitle() {
    return entityTitle.isBlank() ? Optional.empty() : Optional.of(entityTitle);
  }

  /**
   * The status word of {@link #entityId()} when this container booted (e.g. {@code IMPLEMENTING}) —
   * the seed for the status square in a session name, moved from then on by {@code POST
   * /agents/entity}. Injected as {@code QITS_WORKSPACE_DAEMON_ENTITY_STATUS}; the daemon passes the
   * word through untouched and the library decides which square it draws. Blank is absent.
   */
  @Override
  public Optional<String> entityStatus() {
    return entityStatus.isBlank() ? Optional.empty() : Optional.of(entityStatus);
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
