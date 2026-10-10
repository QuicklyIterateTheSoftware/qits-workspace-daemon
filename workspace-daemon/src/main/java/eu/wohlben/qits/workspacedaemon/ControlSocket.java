package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.AgentAuthStatus;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentPluginService;
import eu.wohlben.qits.agents.AgentSessionQueryService;
import eu.wohlben.qits.agents.AgentSessionStore;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentTranscriptService;
import eu.wohlben.qits.agents.AgentTranscriptTailService;
import eu.wohlben.qits.agents.CommandsAgentCommands;
import eu.wohlben.qits.agents.EntityFacts;
import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.agents.HarnessCapabilityService;
import eu.wohlben.qits.agents.LocalProcessExecutor;
import eu.wohlben.qits.agents.ProcessRunner;
import eu.wohlben.qits.agents.PromptRefinementService;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.ActionResolver;
import eu.wohlben.qits.workspacedaemon.detection.DeclaredFramework;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.AgentBranchPushed;
import eu.wohlben.qits.workspacedaemon.protocol.ConfigView;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Describe;
import eu.wohlben.qits.workspacedaemon.protocol.DescribeConfig;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.PullBranch;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceChanged;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The persistent dial-home socket: {@code workspace-daemon} connects to qits-workspaces' {@code
 * /workspaces/daemon/{workspaceId}} WebSocket, sends a {@link Hello}, and thereafter serves backend
 * requests ({@link RunCommand}, {@link Describe}) from in-container, streaming results back. The
 * path is <b>not</b> a constant here — the daemon dials the url it was handed, verbatim; it is
 * named only so this reads against the same convention the rest of the system uses.
 *
 * <p>Two invariants make Part 1 behaviour-neutral and safe:
 *
 * <ul>
 *   <li><b>Never exits on failure.</b> The container's PID-1 child used to be {@code sleep
 *       infinity}; it is now {@code workspace-daemon}. So every connect/close/error path re-arms a
 *       capped-backoff retry instead of propagating — a backend that's down or a missing dial-home
 *       URL leaves the container alive exactly as {@code sleep} would, and the existing {@code
 *       docker exec} paths keep working untouched.
 *   <li><b>No blocking on the event loop.</b> Frame handling runs on a Vert.x event loop; command
 *       execution ({@link CommandExecutor}) and git reads ({@link WorkspaceDescriber}) run on a
 *       worker pool, and every reply is marshalled back onto the connection's context to write.
 * </ul>
 */
@ApplicationScoped
public class ControlSocket {

  private static final Logger LOG = Logger.getLogger(ControlSocket.class);

  @Inject Vertx vertx;

  /**
   * The HTTP API: agent worktrees, their files, commands and the agent surface. Injected rather
   * than constructed because, unlike {@link HookWebhook}, it carries its own config knobs; started
   * from {@link #startWorkspace} once the base clone exists.
   */
  @Inject WorkspaceApi workspaceApi;

  /**
   * Full dial-home URL the factory injected, e.g. {@code ws://qits:8080/workspaces/daemon/<id>}.
   *
   * <p><b>This is the only address the container is handed, and it is one service's.</b> It reaches
   * qits-workspaces' control socket. The daemon also needs a git host (qits-artifacts) and two MCP
   * servers (qits-projects, qits-observability) — four hosts on {@code qits-net}. Everything below
   * that names one of those is here because deriving it from this url would only be right if a
   * single authority routed every segment, and whether the daemon is handed the gateway is not
   * settled (migration-path-conventions.md §4 item 9). See {@link Provisioner} and {@link
   * DaemonMcpEndpoints} for what each one falls back to when unset, and how loudly.
   */
  @ConfigProperty(name = "qits.workspace-daemon.url")
  Optional<String> url;

  /** The per-container IdP client commissioned by qits-workspaces. */
  @ConfigProperty(name = "qits.commissioned-client-id")
  Optional<String> commissionedClientId;

  /** Its one-time secret, paired with {@link #commissionedClientId}. */
  @ConfigProperty(name = "qits.commissioned-client-secret")
  Optional<String> commissionedClientSecret;

  /** Where that client exchanges its pair for the control socket's bearer token. */
  @ConfigProperty(name = "qits.workspace-daemon.auth-token-url")
  Optional<String> authTokenUrl;

  /** The qits-workspaces audience required by the protected control socket. */
  @ConfigProperty(name = "qits.workspace-daemon.auth-audience")
  Optional<String> authAudience;

  /**
   * The workspace token ({@code QITS_TOKEN}): a non-expiring {@code tok-…} bearer the platform
   * minted for this workspace, set on a runner-placed workspace and, since qits-1084, on an admin
   * or editor (DIRECT) workspace too. Where it is set it is the whole credential — the control
   * socket and every tunnel dial-back present it as is and nothing is minted ({@link
   * #authorization()}) — because such a workspace reaches qits only through the public edge, which
   * admits a bearer and nothing else. The commissioned-pair branch below is what a container still
   * holding a pair instead falls back to; removing that branch outright is qits-876. Read here and
   * nowhere else; the tunnel is handed it.
   */
  @ConfigProperty(name = "qits.workspace-daemon.token")
  Optional<String> token;

  // Identity is Optional<String>, not @ConfigProperty(defaultValue = ""): SmallRye treats an empty
  // default as "no value" and fails to resolve a plain String when the env is absent (the same
  // reason WorkspaceContainerFactory.timezone is Optional). Resolved to "" below.
  @ConfigProperty(name = "qits.workspace-daemon.workspace-id")
  Optional<String> workspaceIdConfig;

  @ConfigProperty(name = "qits.workspace-daemon.repository-id")
  Optional<String> repositoryIdConfig;

  // The project-scoped address the daemon self-clones from (<gitBase>/<projectId>/<repoName>), so
  // committed relative submodule urls resolve against the project's siblings
  // (docs/epics/qits-workspace-daemon/ Part 1). BOTH halves are needed: either one blank ⇒ the
  // Provisioner falls back to the internal id-addressed route (<gitBase>/<repositoryId>).
  @ConfigProperty(name = "qits.workspace-daemon.project-id")
  Optional<String> projectIdConfig;

  @ConfigProperty(name = "qits.workspace-daemon.repo-name")
  Optional<String> repoNameConfig;

  // The git host the self-clone reads from: qits-githost, serving /git/<projectId>/<repoName>.
  // Unset ⇒ the Provisioner refuses to clone and says so, because the git host's address is not
  // derivable from this one (see Provisioner's javadoc).
  @ConfigProperty(name = "qits.workspace-daemon.git-base-url")
  Optional<String> gitBaseUrlConfig;

  // Build identity baked into the native image (filtered from Maven at build time, see pom.xml +
  // application.properties). Announced in the Hello so the backend's workspace registry can show
  // which daemon build a running container is on (docs/epics/qits-workspace-registry/). Optional so
  // a dev jar built without filtering (unresolved tokens absent) still boots.
  @ConfigProperty(name = "qits.workspace-daemon.build.version")
  Optional<String> buildVersionConfig;

  @ConfigProperty(name = "qits.workspace-daemon.build.time")
  Optional<String> buildTimeConfig;

  private String workspaceId = "";
  private String repositoryId = "";
  private String projectId = "";
  private String repoName = "";

  @ConfigProperty(name = "qits.workspace-daemon.heartbeat-interval-ms", defaultValue = "20000")
  long heartbeatIntervalMs;

  @ConfigProperty(name = "qits.workspace-daemon.reconnect-max-backoff-ms", defaultValue = "30000")
  long maxBackoffMs;

  // Loopback port the in-container coding-agent lifecycle hooks POST to (the daemon's only inbound
  // listener; see HookWebhook). Must match the port AgentLaunchService renders into the hook curl —
  // both default to 13337 (docs/epics/qits-coding-agents/ agent-activity tracking).
  // Grace period a terminate gives the process group between SIGTERM and SIGKILL. Carried over
  // from the host's qits.workspace.term-grace-ms, which sat on the registry for the same purpose.
  @ConfigProperty(name = "qits.workspace-daemon.term-grace-ms", defaultValue = "5000")
  long termGraceMs;

  @ConfigProperty(name = "qits.workspace-daemon.hooks-port", defaultValue = "13337")
  int hooksPort;

  // How long a Stop whose only in-flight work is background shells waits, with no further hook for
  // its command, before HookWebhook reports it as awaiting input (qits-895). Long enough for a
  // build left running to finish and wake the agent; short enough that a forgotten dev server does
  // not hide a genuinely idle agent for good. Read here because HookWebhook cannot read config.
  @ConfigProperty(
      name = "qits.workspace-daemon.agent-waiting.background-shell-grace",
      defaultValue = "25m")
  Duration backgroundShellGrace;

  // How long a turn for an interactive harness waits for the harness's SessionStart hook before it
  // is typed anyway (qits-1152, TerminalTurnGate).
  @ConfigProperty(name = "qits.workspace-daemon.agent-ready-timeout", defaultValue = "60s")
  Duration agentReadyTimeout;

  /**
   * Where the shared agent-credential volume is mounted in this container. Read here and nowhere
   * else: the launch service overlays it as the agent's HOME and the transcript service resolves
   * config dirs under it, and two independent reads of one key is how those two silently disagree.
   */
  @ConfigProperty(name = "qits.workspace.claude-mount", defaultValue = "/claude-home")
  String claudeMount;

  /** The harness a launch uses when neither the request nor the base clone's config names one. */
  @ConfigProperty(name = "qits.agent.default-type")
  Optional<String> agentDefaultType;

  /** Whether launches wire the turn-boundary activity hooks; the lineage hook is unconditional. */
  @ConfigProperty(name = "qits.agent.activity-tracking-enabled", defaultValue = "true")
  boolean agentActivityTrackingEnabled;

  @ConfigProperty(name = "qits.agent.transcript-tail-poll-ms", defaultValue = "500")
  long transcriptTailPollMs;

  /** Model override for prompt refinement; unset means Claude's haiku and Kimi's own default. */
  @ConfigProperty(name = "qits.refinement.model")
  Optional<String> refinementModel;

  /**
   * The per-surface agent configuration this container was created with, and where to write it.
   *
   * <p>Two keys, both or neither — {@link AgentConfigurationFile} has the whole arrangement and why
   * the bytes travel in the environment rather than as a mount. Read here for the reason every
   * other capability setting is read here: the harness library is framework-free and cannot read
   * configuration itself, so {@link ControlSocket} is the single reader.
   *
   * <p>{@code Optional<String>} rather than a blank default, for the SmallRye reason the identity
   * knobs carry: an empty {@code defaultValue} resolves as "no value" and a plain {@code String}
   * then fails to resolve at startup.
   */
  @ConfigProperty(name = "qits.workspace-daemon.agent-configuration")
  Optional<String> agentConfigurationDocument;

  @ConfigProperty(name = "qits.workspace-daemon.agent-configuration-path")
  Optional<String> agentConfigurationPath;

  /**
   * The workspace image version this container runs, for the host's capability cache.
   *
   * <p><b>Told when it can be, and otherwise the daemon's own build version.</b> Nothing injects an
   * image version into a workspace container today — qits-workspaces composes {@code
   * <repo>:<version>} from its own configuration and sets no environment key for it — so this
   * optional key exists for the day it does, under the name the host already uses for the value
   * ({@code QITS_WORKSPACE_IMAGE_VERSION}), and the fallback is {@code
   * qits.workspace-daemon.build.version}: the calver stamped into this binary at native-image build
   * time, which <em>is</em> the image's calver, because {@code docker/Dockerfile} compiles the
   * daemon and layers it into {@code qits/workspace:<version>} in one build from one reactor.
   *
   * <p>It is a coarser key than the image reference on a fold-built image, which is sha-tagged
   * while the reactor version is not — two folds of one release request report the same version.
   * The host caches a capability report per (harness, image version), so the cost of that is a
   * report from a rebuilt-but-unreleased image not displacing the previous one, which is the
   * direction to be wrong in.
   */
  @ConfigProperty(name = "qits.workspace.image-version")
  Optional<String> workspaceImageVersion;

  /**
   * Explicit MCP base URLs, one per named server. Two jobs now: pointing an agent at a different
   * qits instance (the original), and naming the server's host when it is not the control socket's
   * — {@code repository} is qits-projects, {@code observability} is qits-observability.
   *
   * <p>{@code actions} has no derivable form at all: no service in the split serves it (the tools
   * are still monolith-only, migration-plan.md §9 item 6), so an ACTIONS-scope launch fails with
   * that message unless this key names one.
   */
  @ConfigProperty(name = "qits.actions-mcp.url")
  Optional<String> actionsMcpUrl;

  @ConfigProperty(name = "qits.repository-mcp.url")
  Optional<String> repositoryMcpUrl;

  @ConfigProperty(name = "qits.observability-mcp.url")
  Optional<String> observabilityMcpUrl;

  /**
   * The central platform-access MCP server's base url (qits-630) — a wholly separate service from
   * qits-workspaces, so unlike {@code repository}/{@code observability} there is no derivable
   * fallback. Optional like {@code qits.actions-mcp.url}, but unlike it this server is attached by
   * default on every surface the agent configuration document carries, so an unset value must not
   * refuse every launch — see {@code DaemonMcpEndpoints.platformUrl()} and {@code
   * WorkspaceMcpServers}.
   */
  @ConfigProperty(name = "qits.platform-mcp.url")
  Optional<String> platformMcpUrl;

  // Auto-push kill switch (host's qits.workspace.auto-push.enabled, injected as
  // QITS_WORKSPACE_DAEMON_AUTO_PUSH_ENABLED). When false the daemon never pushes an agent's branch;
  // the base clone's fetch is unaffected.
  @ConfigProperty(name = "qits.workspace-daemon.auto-push-enabled", defaultValue = "true")
  boolean autoPushEnabled;

  // How long a burst of harness hooks is coalesced before one push cycle (a turn's last hooks
  // should push once, not once each).
  @ConfigProperty(name = "qits.workspace-daemon.auto-push.coalesce-ms", defaultValue = "500")
  long autoPushCoalesceMs;

  // The push cycle's poll: how often every agent branch is checked for commits to push, besides the
  // nudge each harness hook gives. One `git worktree list` per repository finds every agent at once.
  @ConfigProperty(name = "qits.workspace-daemon.auto-push.poll-ms", defaultValue = "10000")
  long autoPushPollMs;

  // How often the base clone and its submodules fetch every branch head from the git host (D8),
  // besides the fetch each PullBranch hint asks for. <= 0 fetches on a hint only.
  @ConfigProperty(name = "qits.workspace-daemon.base-sync.interval-ms", defaultValue = "60000")
  long baseSyncIntervalMs;

  // Push-conflict retry bounds: a push rejected by origin's ref lock (a concurrent host push) is
  // retried up to max-attempts with exponential backoff between backoff-initial and backoff-max.
  @ConfigProperty(name = "qits.workspace-daemon.auto-push.max-attempts", defaultValue = "5")
  int autoPushMaxAttempts;

  @ConfigProperty(name = "qits.workspace-daemon.auto-push.backoff-initial-ms", defaultValue = "500")
  long autoPushBackoffInitialMs;

  @ConfigProperty(name = "qits.workspace-daemon.auto-push.backoff-max-ms", defaultValue = "5000")
  long autoPushBackoffMaxMs;

  /** Off-event-loop pool for blocking process/git work; one thread per in-flight request. */
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-worker");
            thread.setDaemon(true);
            return thread;
          });

  private volatile WebSocketClient client;
  private final HttpClient tokenClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private volatile WebSocket socket;
  private volatile Context socketContext;

  /**
   * The loopback HTTP listener the in-container coding agent's lifecycle hooks POST to; relays
   * {@link eu.wohlben.qits.workspacedaemon.protocol.AgentActivity} home. Started unconditionally in
   * {@link #start()} (not gated on provisioning — a hook can fire in a reconnect-adopted container,
   * and SessionStart drives session-lineage which must always be captured); re-reports on
   * reconnect.
   */
  private volatile HookWebhook hooks;

  /**
   * The agents this workspace hosts, or null before {@link #wireAgents} ran (or when the agent
   * surface stayed unwired). {@link HookWebhook} is started before it exists, so the webhook is
   * handed listeners that read this field — a hook that fires first is sent untagged and not
   * forwarded, which no agent would have acted on anyway.
   */
  private volatile AgentRuntime agents;

  /**
   * The reverse tunnel {@link WorkspaceApi} is reached through, now that it binds loopback and has
   * no address on {@code qits-net} at all.
   */
  private volatile DaemonStreamTunnel tunnel;

  /**
   * Fetches the base clone and pushes agents' branches ({@link OriginSync}); created once the base
   * clone is provisioned.
   */
  private volatile OriginSync originSync;

  /**
   * The live terminal and chat sessions {@link #wireCommands} spawns — held so {@link #stop} can
   * terminate them ahead of everything else. See {@link #stopAgents} for why that ordering matters.
   */
  private volatile CommandRegistry commands;

  /**
   * Ensures the autonomous self-provision (clone on boot) runs at most once per daemon lifetime.
   */
  private final java.util.concurrent.atomic.AtomicBoolean provisionStarted =
      new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * The base clone's parsed config ({@code .config/qits/repository.yml}, legacy {@code
   * .qits-config.yml}), read right after the base clone and re-read on {@code SIGHUP} ({@code kill
   * -HUP 1} — docker-init/tini at PID 1 forwards it here); see {@link #reloadConfig}. Every consumer
   * — {@link DescribeConfig} replies, the frameworks hint, agent defaults — reads it through a
   * supplier, so a reload reaches all of them.
   * Starts as the empty config so a describe that races ahead of provisioning gets a benign empty
   * answer rather than null.
   */
  private final ConfigHolder configState = ConfigHolder.forCheckout();

  /** The workspace volume (image {@code WORKDIR}): the commands layer's root. */
  private static final java.io.File WORKSPACE_DIR = new java.io.File("/workspace");

  /** Where agent worktrees live, one directory per agent (qits-1152). */
  private static final java.nio.file.Path AGENTS_DIR = WORKSPACE_DIR.toPath().resolve("agents");

  /**
   * Frames emitted before the socket first connects (the boot self-clone can begin, and finish,
   * before the dial-home succeeds) — buffered here and flushed in {@link #onConnected} after the
   * {@link Hello}. Bounded so a never-connecting backend can't grow it without limit; the terminal
   * provisioning events always survive (they bypass the cap), streamed clone chunks are the only
   * thing dropped past it.
   */
  private final java.util.Queue<DaemonMessage> pendingOutbound =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  private static final int PENDING_OUTBOUND_CAP = 4096;

  /**
   * Guards the transition between buffering (socket down) and direct-write (socket up) so a
   * worker-thread {@link #send} can't interleave with {@link #onConnected}'s publish+flush: without
   * it a terminal frame can be stranded in {@link #pendingOutbound} (enqueued just after an empty
   * flush) or written ahead of still-buffered clone chunks (socket published before the flush).
   */
  private final Object sendLock = new Object();

  /**
   * Begin dialing home. If no URL is configured (older provisioning, or the env wasn't injected),
   * log and stay idle — the container must not die for want of a socket.
   */
  public void start() {
    workspaceId = workspaceIdConfig.orElse("");
    repositoryId = repositoryIdConfig.orElse("");
    projectId = projectIdConfig.orElse("");
    repoName = repoNameConfig.orElse("");
    // THE AGENT CONFIGURATION DOCUMENT, BEFORE ANYTHING ELSE STARTS. The host hands it over as
    // bytes plus a path; this writes one to the other and parses it, so a malformed document kills
    // the container here — in the first lines of its log, where an operator is looking — rather
    // than at the first launch of the one surface that was wrong. Ahead of the url check on
    // purpose: a daemon with no socket still serves nothing, but a container that was handed a
    // broken configuration is broken whether or not it ever dials home, and discovering that only
    // on a connected container would make the failure depend on the backend being up.
    surfaceConfigurations =
        AgentConfigurationFile.materialize(agentConfigurationDocument, agentConfigurationPath)
            .map(
                path -> {
                  LOG.infof("Agent configuration document materialized at %s", path);
                  return AgentSurfaceConfigurations.readFrom(path);
                })
            .orElseGet(
                () -> {
                  LOG.info(
                      "No agent configuration document was injected; every surface renders the"
                          + " harness library's shipped defaults.");
                  return AgentSurfaceConfigurations.shipped();
                });
    // Ahead of the url check so an idle daemon does not die on a HUP either: the JVM default for
    // SIGHUP is to exit, and PID 1 forwards every signal it gets.
    installReloadSignal();
    if (url.isEmpty() || url.get().isBlank()) {
      LOG.warn(
          "No qits.workspace-daemon.url configured — workspace-daemon is idle (container stays"
              + " alive, docker exec paths unaffected).");
      return;
    }
    // Autonomous self-provision: clone /workspace + materialize submodules from env, on boot, off
    // the
    // event loop — independent of whether the socket is up yet (its results buffer until it is).
    // qits
    // sends nothing; it only awaits the Provisioned/ProvisionFailed we emit
    // (docs/epics/qits-workspace-daemon/ Part 1).
    startProvisioning();
    // The hook webhook is independent of provisioning: a lifecycle hook can fire in a
    // reconnect-adopted (already-provisioned) container, and SessionStart drives session-lineage
    // which must be captured regardless. Its frames buffer in pendingOutbound until the socket is
    // up.
    hooks =
        new HookWebhook(
            vertx, hooksPort, this::sendTagged, this::forwardActivity, backgroundShellGrace);
    hooks.start();
    // The reverse tunnel qits reaches WorkspaceApi through. Independent of provisioning for the
    // same reason the hook webhook is: it only needs the url and the port, and a stream requested
    // before the API is up simply fails to connect to loopback and answers nothing.
    tunnel = new DaemonStreamTunnel(vertx, url.get(), bearer(), workspaceApi.apiPort());
    tunnel.start();
    client = vertx.createWebSocketClient(DaemonDial.clientOptions());
    if (heartbeatIntervalMs > 0) {
      vertx.setPeriodic(heartbeatIntervalMs, id -> heartbeat());
    }
    connect(0);
  }

  /**
   * Re-read the base clone's config on {@code SIGHUP}, so an edit to its {@code
   * .config/qits/repository.yml} reaches the frameworks hint, the agent defaults and the config view
   * without a container restart. The handler only hands off to the worker pool: a signal-dispatch thread is
   * no place for file IO and YAML parsing.
   *
   * <p>Native image needs nothing extra for this. {@code sun.misc.Signal.handle} with a Java
   * handler needs {@code EnableSignalHandling}, which on the jdk-25 Mandrel builder defaults to true
   * for executables (graal release/graal-vm/25.0 SubstrateOptions: {@code getValueOrDefault}
   * returns {@code ImageInfo.isExecutable()}), as does {@code InstallExitHandlers}. That is why
   * Quarkus 3.34's NativeImageBuildStep only passes {@code --install-exit-handlers} for GraalVM
   * older than 25 — the option is deprecated there as "enabled by default for executables". No
   * reflection or JNI registration is involved: {@code sun.misc.Signal} is substituted by
   * SubstrateVM itself.
   */
  private void installReloadSignal() {
    try {
      sun.misc.Signal.handle(
          new sun.misc.Signal("HUP"),
          signal -> {
            try {
              workers.execute(this::reloadConfig);
            } catch (RejectedExecutionException e) {
              LOG.debug("SIGHUP after shutdown; config not reloaded");
            }
          });
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          "Could not install the SIGHUP handler (%s); config edits need a container"
              + " restart",
          e.getMessage());
    }
  }

  /**
   * Re-read the base clone's config and swap it in; a broken file keeps the last good config and
   * only replaces the warning.
   */
  void reloadConfig() {
    configState.reload();
  }

  /** Kick off the base clone on the worker pool, at most once. */
  private void startProvisioning() {
    if (provisionStarted.compareAndSet(false, true)) {
      Provisioner.Env env =
          new Provisioner.Env(
              workspaceId, repositoryId, projectId, repoName, gitBaseUrlConfig.orElse(""));
      workers.execute(
          () -> {
            boolean provisioned = Provisioner.provision(env, this::send);
            // Clone → config read: read the base clone's config even if the clone failed (absent
            // file ⇒ empty), so a DescribeConfig always has an answer. The same holder SIGHUP
            // reloads, so boot is simply the first read.
            configState.reload();
            if (provisioned) {
              startWorkspace();
            }
          });
    }
  }

  /**
   * Bring the workspace up over its base clone: the agent worktrees, the origin sync, the API, and
   * the commands and agents surfaces. A failed provision means the host is tearing the workspace
   * down, so this runs only after a {@code Provisioned}.
   */
  private void startWorkspace() {
    AgentWorktrees worktrees =
        new AgentWorktrees(Provisioner.BASE_DIR.toPath(), AGENTS_DIR, wrapperName());
    worktrees.installGuards();
    OriginSync sync =
        new OriginSync(
            worktrees,
            agentId -> {
              AgentRuntime runtime = agents;
              return runtime == null ? Optional.empty() : runtime.environment(agentId);
            },
            this::send,
            autoPushEnabled,
            baseSyncIntervalMs,
            autoPushPollMs,
            autoPushCoalesceMs,
            autoPushMaxAttempts,
            autoPushBackoffInitialMs,
            autoPushBackoffMaxMs);
    originSync = sync;
    sync.start();
    // The API goes up only now: its file routes read agent worktrees, which are made from the base
    // clone. The frameworks supplier reads the held configState, so a SIGHUP reload reaches it.
    workspaceApi.start(
        worktrees,
        () ->
            configState.config().frameworks().stream()
                .map(f -> new DeclaredFramework(f.kind(), f.root()))
                .toList());
    wireCommands(worktrees);
  }

  /**
   * The wrapper's name: its directory under each agent, and the {@code repository} reported for it.
   * The project-scoped name when the container was given one, else the repository id.
   */
  private String wrapperName() {
    if (repoName != null && !repoName.isBlank() && AgentWorktrees.validAgentId(repoName)) {
      return repoName;
    }
    return repositoryId == null || repositoryId.isBlank() ? "wrapper" : repositoryId;
  }

  /**
   * Assemble {@code qits-commands} and hand it to {@link WorkspaceApi}.
   *
   * <p>The module is framework-free by design — no CDI, like {@code workspace-daemon-files} and
   * {@code workspace-daemon-detection} — so its objects are constructed here rather than injected.
   * One store, one registry and one command service serve every agent: a command id is unique
   * across them, so the command routes and sockets need no agent in their path. The registry's root
   * is the workspace volume; each agent's harness runs in its own worktree ({@link
   * AgentScopedCommands}).
   *
   * <p>No declared actions: the checkout's {@code actions} went with the Actions tab (qits-1152).
   *
   * <p>{@code commandsChanged} rides the control socket as a {@link WorkspaceChanged} frame, the
   * generic nudge added at {@code CAPABILITY_VERSION} 3; the transcript sweep uses the same
   * callback, so a finished agent session's conversation appears when it lands rather than at the
   * next poll.
   */
  private void wireCommands(AgentWorktrees worktrees) {
    DaemonWorkspaceContext context =
        new DaemonWorkspaceContext(repositoryId, workspaceId, () -> "", () -> "");
    CommandStore store = new CommandStore();
    CommandLogService logs = new CommandLogService(store, null);
    AgentKillWatch kills =
        new AgentKillWatch(store, new CgroupMemory(CgroupMemory.CONTAINER), this::reportKill);
    CommandLifecycleService lifecycle =
        new CommandLifecycleService(
            store,
            () -> {
              nudge(WorkspaceChangeTopic.COMMANDS);
              kills.commandsChanged();
            });
    CommandRegistry commandRegistry = new CommandRegistry(WORKSPACE_DIR.toPath(), termGraceMs);
    commands = commandRegistry;
    CommandService commandService =
        new CommandService(store, commandRegistry, lifecycle, logs, context, NO_ACTIONS);
    workspaceApi.wireCommands(commandService, commandRegistry, context);
    LOG.infof("workspace-daemon commands API wired for workspace %s", workspaceId);
    wireAgents(worktrees, store, logs, commandService, commandRegistry, context);
  }

  /** The checkout declares no actions any more; the commands layer still asks. */
  private static final ActionResolver NO_ACTIONS =
      new ActionResolver() {
        @Override
        public Optional<ResolvedAction> resolve(String actionId) {
          return Optional.empty();
        }

        @Override
        public List<ResolvedAction> actions() {
          return List.of();
        }
      };

  /**
   * Assemble {@code qits-coding-agents} on top of the commands wiring and hand it to {@link
   * WorkspaceApi}. Constructed by hand for the same reason commands is — the module is
   * framework-free and cannot read configuration itself, which is why every setting it needs is a
   * {@code @ConfigProperty} on this class and arrives as a constructor argument.
   *
   * <p>Each agent gets its own {@link AgentLaunchService}, built by the factory below: its own
   * entity facts (so its own session name), its own checkout context (its wrapper branch and HEAD)
   * and its own commands seam (its directory and credential). The transcript services, the auth
   * probe and the MCP mapping are shared.
   *
   * <p>{@link #hooksPort} is the one to watch. {@link HookWebhook} binds it and {@link
   * AgentLaunchService} renders it into every hook {@code curl}; if those two ever read it separately
   * and disagree, launches still succeed and simply never report session lineage or activity.
   *
   * <p>A daemon with no {@code qits.workspace-daemon.url} cannot derive the MCP endpoints an agent
   * would be launched with — but it also never connected, so it is not serving this API either. The
   * agent surface is simply left unwired and answers 503.
   */
  private void wireAgents(
      AgentWorktrees worktrees,
      CommandStore store,
      CommandLogService logs,
      CommandService commandService,
      CommandRegistry commandRegistry,
      DaemonWorkspaceContext context) {
    DaemonAgentDefaults defaults = defaultsFor(null, null, null);
    DaemonMcpEndpoints endpoints;
    try {
      endpoints =
          new DaemonMcpEndpoints(
              url.orElse(null),
              projectId,
              actionsMcpUrl,
              repositoryMcpUrl,
              observabilityMcpUrl,
              platformMcpUrl);
    } catch (IllegalStateException e) {
      LOG.warnf("Coding agents stay unwired: %s", e.getMessage());
      return;
    }
    ProcessRunner processes = new LocalProcessExecutor();
    AgentTranscriptService transcripts =
        new AgentTranscriptService(
            store,
            logs,
            agentSessionStore,
            claudeMount,
            () -> nudge(WorkspaceChangeTopic.COMMANDS));
    AgentTranscriptTailService tail =
        new AgentTranscriptTailService(transcripts, logs, transcriptTailPollMs);
    tail.start();
    this.transcriptTail = tail;
    AgentAuthStatus authStatus =
        new AgentAuthStatus(processes, claudeMount, WORKSPACE_DIR.toPath());
    // The scope→server mapping is this daemon's, not the library's: the projects daemon attaches
    // one server and this one attaches three, with different narrowing and different pre-approval.
    // Only the sign-in terminal's service carries no token at all: it attaches no MCP server.
    // Every agent's carries the agent's own token (below), never the workspace's, so a harness
    // never holds the workspace credential.
    WorkspaceMcpServers mcpServers =
        new WorkspaceMcpServers(
            endpoints, repositoryId, workspaceId, endpoints.platformUrl(), Optional.empty());
    CommandsAgentCommands shared = new CommandsAgentCommands(commandService, commandRegistry, store);
    AgentRuntime runtime =
        new AgentRuntime(
            worktrees,
            store,
            commandRegistry,
            shared,
            seat ->
                new AgentLaunchService(
                    seat.commands(),
                    authStatus,
                    transcripts,
                    tail,
                    defaultsFor(seat.wrapperBranch(), seat.entityId(), seat.entity()),
                    WorkspaceMcpServers.forAgent(
                        endpoints, repositoryId, workspaceId, endpoints.platformUrl(), seat),
                    new DaemonWorkspaceContext(
                        repositoryId,
                        workspaceId,
                        () -> seat.wrapperBranch(),
                        () -> head(seat.directory())),
                    claudeMount,
                    hooksPort),
            claudeMount,
            agentReadyTimeout);
    agents = runtime;
    // The sign-in terminal is nobody's agent: a launch service of the workspace's own serves it.
    AgentLaunchService workspaceLaunch =
        new AgentLaunchService(
            shared, authStatus, transcripts, tail, defaults, mcpServers, context, claudeMount,
            hooksPort);
    workspaceApi.wireAgents(
        runtime,
        workspaceLaunch,
        new AgentSessionQueryService(store, agentSessionStore),
        new AgentPluginService(processes, claudeMount, WORKSPACE_DIR.toPath(), defaults),
        new PromptRefinementService(
            processes, context, defaults, claudeMount, WORKSPACE_DIR.toPath()),
        defaults,
        imageVersion(),
        () -> harnessCapabilities);
    reportHarnessCapabilities(
        new HarnessCapabilityService(processes, authStatus, claudeMount, WORKSPACE_DIR.toPath()));
    LOG.infof("workspace-daemon coding-agents API wired for workspace %s", workspaceId);
  }

  /**
   * The agent defaults for one agent, or for the workspace itself when every argument is null. The
   * entity facts seed the session name; the wrapper branch fills the {@code ticket}/{@code epic}
   * prompt facts.
   */
  private DaemonAgentDefaults defaultsFor(
      String wrapperBranch, String entityId, EntityFacts entity) {
    return new DaemonAgentDefaults(
        () -> configState.config(),
        agentDefaultType,
        agentActivityTrackingEnabled,
        refinementModel,
        surfaceConfigurations,
        DaemonAgentDefaults.ambientFactsOf(
            projectId, repoName, repositoryId, workspaceId, wrapperBranch),
        entityId,
        entity != null && entity.blocked(),
        entity == null ? null : entity.title(),
        entity == null ? null : entity.status());
  }

  /** {@code HEAD} of a worktree, or blank. */
  private static String head(java.nio.file.Path directory) {
    GitExec.Out out = GitExec.git(directory, "rev-parse", "HEAD");
    return out.ok() ? out.line() : "";
  }

  /**
   * Relays a killed agent's end through the webhook, which owns the per-command replay ({@link
   * HookWebhook#killed}). The webhook is started in {@link #start()} before any command can exist,
   * so the null check is for a daemon torn down mid-exit, not for a race at boot.
   */
  private void reportKill(String commandId, String hookEvent, int exitCode, String message) {
    HookWebhook h = hooks;
    if (h != null) {
      h.killed(commandId, hookEvent, exitCode, message);
    }
  }

  /**
   * Hands an agent command's stored activity state to its agent's launch service, which retries a
   * queued interactive rename on {@code IDLE} and forgets the session on {@code ENDED}, and nudges
   * the auto-push: a hook means a turn moved, and a turn may have committed. A no-op until {@link
   * #wireAgents} has run.
   */
  private void forwardActivity(String commandId, String state) {
    AgentRuntime runtime = agents;
    if (runtime != null) {
      runtime.onActivity(commandId, state);
    }
    OriginSync sync = originSync;
    if (sync != null) {
      sync.nudge();
    }
  }

  /** Send a hook's frame, naming the agent whose harness fired it. */
  private void sendTagged(DaemonMessage message) {
    AgentRuntime runtime = agents;
    send(runtime == null ? message : runtime.tag(message));
  }

  /**
   * Probe every harness once, on the worker pool, and hold the answer for {@code GET
   * /agents/available}.
   *
   * <p><b>Once per container start, and off the request path.</b> Each report spawns one or two
   * processes; doing it per request would put a process spawn on every editor page load, and the
   * editor cannot do it itself — the binaries live in this image and the editor is a platform-wide
   * route with no container in front of it. The host caches what this answers, keyed by harness and
   * image version, and a rebuilt image refreshes the catalogue the first time a container on it
   * starts.
   *
   * <p><b>Not on the boot thread, and a failure never reaches it.</b> Two process spawns before the
   * socket is dialled would delay every container's dial-home for the sake of a dropdown, and a
   * probe that hangs on a broken binary would hold the container in a state the host reads as dead.
   * So it runs where the rest of this daemon's blocking work runs, and {@code /agents/available}
   * answers an empty capability list until it lands — an honest "nothing reported yet", which the
   * host reads as a cache miss rather than as an empty dropdown. {@link
   * HarnessCapabilityService#report} already never throws per harness; the catch here is for the
   * pool itself, because a daemon that does not start is a workspace nobody can use.
   */
  private void reportHarnessCapabilities(HarnessCapabilityService capabilities) {
    try {
      workers.execute(
          () -> {
            try {
              harnessCapabilities = capabilities.reportAll();
              LOG.infof(
                  "Harness capabilities reported for %d harnesses on image version %s",
                  harnessCapabilities.size(), imageVersion());
            } catch (RuntimeException e) {
              LOG.warnf(
                  e,
                  "Harness capability report failed; GET /agents/available answers no capabilities"
                      + " and the host keeps whatever it cached");
            }
          });
    } catch (RejectedExecutionException shuttingDown) {
      LOG.debug("Harness capability report skipped: the daemon is shutting down");
    }
  }

  /** The image version reported beside the capabilities; see {@link #workspaceImageVersion}. */
  private String imageVersion() {
    return workspaceImageVersion
        .filter(value -> !value.isBlank())
        .or(() -> buildVersionConfig.filter(value -> !value.isBlank()))
        .orElse("unknown");
  }

  /**
   * The {@code WorkspaceChangeHint.Topic} names this daemon nudges about. A String on the wire (see
   * {@link WorkspaceChanged}); this constant holder keeps the spelling in one place rather than
   * scattered at the call sites.
   */
  private static final class WorkspaceChangeTopic {
    private static final String COMMANDS = "COMMANDS";

    private WorkspaceChangeTopic() {}
  }

  /**
   * Push a change nudge home, if the socket is up. Best-effort by design: the frame carries no
   * state, so a nudge dropped while reconnecting costs one stale view until the next one, and the
   * backend's own poll is still there underneath.
   */
  private void nudge(String topic) {
    if (workspaceId == null || workspaceId.isBlank()) {
      return;
    }
    send(new WorkspaceChanged(workspaceId, topic));
  }

  /** Transcript aggregates, held here so the query service and the sweep share one instance. */
  private final AgentSessionStore agentSessionStore = new AgentSessionStore();

  /**
   * The document this container was born with, parsed once in {@link #start()}. Never null after
   * that; {@link AgentSurfaceConfigurations#shipped()} is the "created before this shipped" answer.
   */
  private volatile AgentSurfaceConfigurations surfaceConfigurations =
      AgentSurfaceConfigurations.shipped();

  /**
   * What the harnesses in this image reported, or empty until the boot probe lands (and if it
   * failed). Volatile rather than guarded: one writer at boot, many readers on the API's worker
   * threads, and a reader that sees the empty list one request early costs the host a cache miss.
   */
  private volatile List<HarnessCapabilities> harnessCapabilities = List.of();

  private volatile AgentTranscriptTailService transcriptTail;

  private void connect(int attempt) {
    URI uri;
    try {
      uri = URI.create(url.get());
    } catch (RuntimeException e) {
      LOG.errorf(e, "Malformed qits.workspace-daemon.url '%s' — workspace-daemon idle.", url.get());
      return; // an unparseable URL won't become parseable on retry; stay alive, stay idle
    }
    authorization()
        .whenComplete(
            (authorization, failure) ->
                vertx.runOnContext(
                    ignored -> {
                      if (failure != null) {
                        LOG.debugf(
                            "workspace-daemon could not mint its dial-home token (attempt %d): %s",
                            attempt, failure.getMessage());
                        reconnect(attempt);
                        return;
                      }
                      connect(uri, attempt, authorization);
                    }));
  }

  private void connect(URI uri, int attempt, Optional<String> authorization) {
    client
        .connect(dialOptions(uri, authorization))
        .onSuccess(this::onConnected)
        .onFailure(
            t -> {
              LOG.debugf(
                  "workspace-daemon dial-home failed (attempt %d): %s", attempt, t.getMessage());
              reconnect(attempt);
            });
  }

  /**
   * The control socket's connect options: TLS and the default port follow the url's scheme ({@link
   * DaemonDial}), and {@code authorization} rides as the {@code Authorization} header.
   */
  static WebSocketConnectOptions dialOptions(URI uri, Optional<String> authorization) {
    return DaemonDial.connectOptions(uri, authorization);
  }

  /** The workspace token as a header value, or empty when this workspace was handed none. */
  Optional<String> bearer() {
    return token == null
        ? Optional.empty()
        : token.filter(value -> !value.isBlank()).map(value -> "Bearer " + value.trim());
  }

  /**
   * The control socket's {@code Authorization}, without blocking the Vert.x event loop.
   *
   * <p>The workspace token wins outright: present, it is the header and nothing is minted. Absent,
   * the commissioned pair is exchanged for a machine token by {@code client_secret_post} — the
   * client id and secret in the form body, no {@code Basic} header, because the edge eats a {@code
   * Basic} header rather than forwarding it. Absent configuration keeps the clone-alone/developer
   * topology anonymous; a partial configuration fails closed and is retried with the socket.
   */
  java.util.concurrent.CompletableFuture<Optional<String>> authorization() {
    Optional<String> bearer = bearer();
    if (bearer.isPresent()) {
      return java.util.concurrent.CompletableFuture.completedFuture(bearer);
    }
    boolean any =
        commissionedClientId.isPresent()
            || commissionedClientSecret.isPresent()
            || authTokenUrl.isPresent()
            || authAudience.isPresent();
    if (!any) {
      return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }
    if (commissionedClientId.isEmpty()
        || commissionedClientSecret.isEmpty()
        || authTokenUrl.isEmpty()
        || authAudience.isEmpty()) {
      return java.util.concurrent.CompletableFuture.failedFuture(
          new IllegalStateException("commissioned dial-home authentication is incomplete"));
    }
    HttpRequest request;
    try {
      String form =
          "grant_type=client_credentials&client_id="
              + URLEncoder.encode(commissionedClientId.get(), StandardCharsets.UTF_8)
              + "&client_secret="
              + URLEncoder.encode(commissionedClientSecret.get(), StandardCharsets.UTF_8)
              + "&audience="
              + URLEncoder.encode(authAudience.get(), StandardCharsets.UTF_8);
      request =
          HttpRequest.newBuilder(URI.create(authTokenUrl.get()))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .build();
    } catch (RuntimeException e) {
      return java.util.concurrent.CompletableFuture.failedFuture(e);
    }
    return tokenClient
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(
            response -> {
              if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("idp answered " + response.statusCode());
              }
              String token = new JsonObject(response.body()).getString("access_token");
              if (token == null || token.isBlank()) {
                throw new IllegalStateException("idp answered without an access token");
              }
              return Optional.of("Bearer " + token);
            });
  }

  private void onConnected(WebSocket ws) {
    socketContext = vertx.getOrCreateContext();
    ws.textMessageHandler(this::onFrame);
    ws.closeHandler(
        v -> {
          LOG.debug("workspace-daemon control socket closed — reconnecting.");
          synchronized (sendLock) {
            socket = null;
          }
          reconnect(0);
        });
    ws.exceptionHandler(
        t -> LOG.debugf("workspace-daemon control socket error: %s", t.getMessage()));
    synchronized (sendLock) {
      // Announce, then drain the boot-provision buffer, then publish `socket` LAST — all under
      // sendLock. So a worker-thread send() (also under the lock) either ran before us (its frame
      // is
      // in the buffer we flush here, in order) or runs after (sees the published socket and writes
      // directly, after the flushed chunks). This closes both the stranding and the reordering
      // race.
      send(
          new Hello(
              workspaceId,
              repositoryId,
              null,
              null,
              DaemonProtocol.CAPABILITY_VERSION,
              buildVersionConfig.orElse(null),
              buildTimeConfig.orElse(null)),
          ws);
      // Prove the thin-client log direction: workspace-daemon's own events reach qits over the
      // socket, so a crashing/misbehaving client is visible without `docker logs`. Later parts
      // reuse
      // this to relay daemon/command output.
      send(new DaemonLog("INFO", "workspace-daemon online for workspace " + workspaceId), ws);
      // Flush anything the boot self-provision emitted before this first connect (its clone chunks
      // and terminal Provisioned/ProvisionFailed), after the Hello so wire ordering is preserved.
      flushPending(ws);
      socket = ws;
    }
    // Reconnect adoption: re-report the last known agent activity per tracked command, so a qits restart
    // rebuilds the live "cooking / idle / waiting" projection from the daemon's retained state.
    HookWebhook h = hooks;
    if (h != null) {
      workers.execute(h::reportCurrent);
    }
    LOG.infof("workspace-daemon control socket established for workspace %s", workspaceId);
  }

  private void flushPending(WebSocket ws) {
    DaemonMessage buffered;
    while ((buffered = pendingOutbound.poll()) != null) {
      send(buffered, ws);
    }
  }

  private void reconnect(int attempt) {
    long backoff = Math.min(maxBackoffMs, 500L * (1L << Math.min(attempt, 6)));
    vertx.setTimer(backoff, id -> connect(attempt + 1));
  }

  private void onFrame(String json) {
    DaemonMessage message;
    try {
      message = DaemonCodec.decode(new JsonObject(json).getMap());
    } catch (RuntimeException e) {
      LOG.debugf("workspace-daemon dropped an undecodable frame: %s", e.getMessage());
      return;
    }
    switch (message) {
      case RunCommand command -> workers.execute(() -> CommandExecutor.run(command, this::send));
      case Describe ignored ->
          workers.execute(
              () -> send(WorkspaceDescriber.describe(workspaceId, repositoryId, null, null)));
      case DescribeConfig request ->
          workers.execute(
              () -> {
                ConfigReader.State state = configState.state();
                send(
                    new ConfigView(
                        workspaceId, request.correlationId(), state.configJson(), state.warning()));
              });
      case OpenStream request -> {
        // On the event loop: both connects are non-blocking futures and the pumps are
        // handler-driven, so there is nothing here worth a worker thread.
        DaemonStreamTunnel t = tunnel;
        if (t != null) {
          t.open(request.nonce(), request.path(), request.target());
        }
      }
      case PullBranch request -> {
        // A ref moved on the git host. The base clone's working tree is never moved, so the answer
        // is a fetch, ahead of the periodic one; the branch is read for the log only.
        OriginSync s = originSync;
        if (s != null) {
          s.requestFetch();
        } else {
          LOG.debugf(
              "PullBranch for %s but the base clone is not up yet — ignoring", request.branch());
        }
      }
      default ->
          // Ack and any workspace-daemon->qits echoes are informational here; nothing to do in Part
          // 1.
          LOG.debugf("workspace-daemon received %s", message.getClass().getSimpleName());
    }
  }

  private void heartbeat() {
    WebSocket ws = socket;
    if (ws != null && !ws.isClosed()) {
      send(new Heartbeat(workspaceId), ws);
    }
  }

  /**
   * Emit a message on the current socket, marshalling the write onto its event loop. When the
   * socket isn't up yet (the boot self-provision can emit before the first connect, or
   * during a reconnect), buffer it for {@link #flushPending}: <b>terminal</b> events always buffer;
   * streamed clone chunks buffer only up to {@link #PENDING_OUTBOUND_CAP}, then drop (the outcome,
   * not the log tail, is what the host needs). {@link AgentBranchPushed} always buffers too: it is
   * the only report of a push, and the host keeps an agent's branch list from it.
   */
  private void send(DaemonMessage message) {
    synchronized (sendLock) {
      WebSocket ws = socket;
      if (ws != null) {
        send(message, ws);
        return;
      }
      // A killed agent's ENDED is terminal in the same sense: it is the only frame that will ever
      // say the agent died, and the replay on reconnect has already forgotten the command.
      boolean terminal =
          message instanceof Provisioned
              || message instanceof ProvisionFailed
              || message instanceof AgentBranchPushed
              || (message instanceof AgentActivity activity && activity.exitCode() != null);
      if (terminal || pendingOutbound.size() < PENDING_OUTBOUND_CAP) {
        pendingOutbound.offer(message);
      }
    }
  }

  private void send(DaemonMessage message, WebSocket ws) {
    String json = new JsonObject(DaemonCodec.encode(message)).encode();
    Context context = socketContext;
    if (context != null && Vertx.currentContext() != context) {
      context.runOnContext(v -> writeIfOpen(ws, json));
    } else {
      writeIfOpen(ws, json);
    }
  }

  private static void writeIfOpen(WebSocket ws, String json) {
    if (!ws.isClosed()) {
      ws.writeTextMessage(json);
    }
  }

  @PreDestroy
  void stop() {
    // Agents go first, before anything else is torn down — see stopAgents for why.
    stopAgents(commands);
    HookWebhook h = hooks;
    if (h != null) {
      h.close();
    }
    AgentRuntime runtime = agents;
    if (runtime != null) {
      runtime.close();
    }
    DaemonStreamTunnel tun = tunnel;
    if (tun != null) {
      tun.close();
    }
    AgentTranscriptTailService t = transcriptTail;
    if (t != null) {
      t.close();
    }
    OriginSync o = originSync;
    if (o != null) {
      o.close();
    }
    workers.shutdownNow();
    WebSocket ws = socket;
    if (ws != null) {
      ws.close();
    }
    WebSocketClient c = client;
    if (c != null) {
      c.close();
    }
  }

  /**
   * Terminate every live agent, ahead of everything else {@link #stop} tears down.
   *
   * <p>{@code claude --remote-control} archives its claude.ai session only on {@code SIGTERM}. Under
   * tini (this container's PID 1) a plain {@code docker stop} reaches only this daemon: each agent
   * is launched {@code setsid}'d into its own process group (see {@code CommandRegistry}'s javadoc),
   * tini does not forward a signal to a group it did not start directly, and once this process exits
   * the kernel {@code SIGKILL}s whatever is left in the container. {@link CommandRegistry#terminateAll}
   * is this daemon's only chance to hand every live agent a {@code SIGTERM} at all, so it has to run
   * before anything else — while the control socket, the network and the worker pool this method's
   * caller is about to close are still up, which is what lets an agent's exit callback (the
   * transcript sweep, the status update) report on its way out. Its grace ({@code termGraceMs},
   * default 5s) has to leave room under docker's 10s stop budget for whatever {@link #stop} still
   * does afterward.
   *
   * <p>Package-private and static so it can be exercised without standing up the rest of {@link
   * ControlSocket}.
   */
  static void stopAgents(CommandRegistry commands) {
    if (commands != null) {
      commands.terminateAll();
    }
  }
}
