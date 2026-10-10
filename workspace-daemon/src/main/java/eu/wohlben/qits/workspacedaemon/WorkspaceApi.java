package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandNotFoundException;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.agents.AgentDefaults;
import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentNotSignedInException;
import eu.wohlben.qits.agents.AgentPluginService;
import eu.wohlben.qits.agents.AgentSessionQueryService;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.EntityFacts;
import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.agents.PromptRefinementService;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import eu.wohlben.qits.commands.LogChannel;
import eu.wohlben.qits.commands.LogSeverity;
import eu.wohlben.qits.workspacedaemon.detection.ComponentMapService;
import eu.wohlben.qits.workspacedaemon.detection.DeclaredFramework;
import eu.wohlben.qits.workspacedaemon.detection.DetectionService;
import eu.wohlben.qits.workspacedaemon.files.LocalWorkspaceFiles;
import eu.wohlben.qits.workspacedaemon.files.WorkspaceFileBrowser;
import eu.wohlben.qits.workspacedaemon.files.WorkspaceFilesException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The daemon's HTTP API: the agent worktrees this workspace hosts (qits-1152), each agent's files,
 * the commands its harnesses run, and the agent surface that is not bound to one agent (sign-in,
 * plugins, sessions, prompt refinement). The file browser, detection and component map are the
 * transport half of the two capability modules ({@code workspace-daemon-files}, {@code
 * workspace-daemon-detection}) that moved off the host; since qits-1152 they are rooted at one
 * agent's wrapper worktree, because the workspace has no checkout of its own to show.
 *
 * <p>A raw {@code vertx-core} {@link HttpServer}, exactly like {@link HookWebhook} and for the same
 * reason: the module carries {@code quarkus-vertx} only — no {@code quarkus-rest}, no {@code
 * quarkus-vertx-http}, no JAX-RS — so the native image stays lean and needs nothing registered.
 * Bodies are hand-built {@code JsonObject}s ({@link WorkspaceJson}), because there is no Jackson
 * here either.
 *
 * <h2>The contract</h2>
 *
 * <p>{@code docs/openapi.yml} has it whole. The file routes sit under {@code
 * /agent-worktrees/{agentId}}:
 *
 * <pre>
 *   GET …/files?path=&lt;rel&gt;           200  {paths[], lazyDirs[{path,childCount}], generation}
 *   GET …/files/content?path=&lt;rel&gt;   200  {path, content?, binary}
 *   GET …/detection                   200  {projects[], frameworks[], links[], generation}
 *   GET …/component-map               200  {framework, components[]}
 * </pre>
 *
 * <p>{@code path} is relative to the agent's wrapper worktree and optional on {@code /files} (absent ⇒ the root
 * level), required on {@code /files/content}. Failures carry {@link WorkspaceFilesException}'s own
 * status — 400 for a path the browser refuses to resolve, 404 for one that names nothing, 413 for a
 * response the transport cannot carry — and anything else is a 500; every non-2xx body is {@code
 * {"message": …}}. No handler is allowed to throw into the event loop, so the dispatch is wrapped
 * whole.
 *
 * <h2>Why this is loopback-bound</h2>
 *
 * <p>It did not used to be. This server bound {@code 0.0.0.0} because its client was on the shared
 * {@code qits-net} docker network, and that made it reachable by DNS name from every other
 * container on that network — including <em>other workspaces</em>, each running a coding agent over
 * someone else's untrusted code with unrestricted outbound network. One shared secret stood between
 * one workspace's agent and every other workspace's working tree, and every one of those agents
 * could read that secret out of its own environment.
 *
 * <p>So the listener stopped existing rather than the secret getting harder. {@link
 * DaemonStreamTunnel} dials <em>out</em> when qits asks for a stream over the control socket, and
 * pipes that connection to this server on loopback. Nothing on {@code qits-net} can reach this port
 * at all now — a peer container's connection is refused by the network stack rather than by a token
 * check, which is a boundary the topology has rather than one a comment claims.
 *
 * <h2>Security</h2>
 *
 * <p>The threat model that shaped this surface: it serves the contents of an <em>untrusted</em>
 * cloned repository, so an unauthenticated port would make every workspace's working tree — source,
 * uncommitted work, whatever secrets a repo carries — readable by whoever could reach it. The path
 * guards in {@code WorkspaceFileBrowser} bound the damage to <em>this</em> checkout; they do nothing
 * about <em>who</em> may read it.
 *
 * <p><b>The bearer stays, and it is not the boundary.</b> Loopback is what makes this unreachable
 * from off-container; the token is defence in depth behind it, and it costs nothing to keep. What it
 * must not be described as is protection — for the whole of stage 1 it was the only thing standing
 * between peer workspaces, was a shared constant readable by every agent, and that was accepted
 * rather than overlooked. This is peer authentication (qits is calling), never user authentication:
 * the daemon has no idea who the user is and never will.
 *
 * <p>So the API requires a shared secret, {@code qits.workspace-daemon.api-token} (injected as
 * {@code QITS_WORKSPACE_DAEMON_API_TOKEN}, the same env family as the rest of the daemon's identity
 * — the host injects it per container at creation), presented as {@code Authorization: Bearer
 * <token>}. Compared with {@link MessageDigest#isEqual} so a mismatch costs the same time whatever
 * the prefix, and never logged or echoed.
 *
 * <p><b>Absent token ⇒ the server does not bind at all</b>, with a warning. Fail-closed rather than
 * fail-open is the deliberate choice: an omitted env is indistinguishable from a misconfiguration,
 * and the failure modes are not symmetric — refusing to bind costs qits a connection error on one
 * feature it already has a host-side implementation of, while serving anonymously would silently
 * publish every workspace's source across the network with nothing in the logs to say so. The
 * daemon itself stays alive either way; nothing here may take the container down.
 *
 * <p>Everything past the token check is a plain {@code GET} with no body, no cookies, no CORS
 * headers and no {@code Access-Control-Allow-*} — a browser is not a client of this port, and the
 * bearer scheme is not ambient-authority, so there is no CSRF surface to open. {@code
 * X-Content-Type-Options: nosniff} is set because the bodies embed repository-controlled text.
 */
@ApplicationScoped
public class WorkspaceApi {

  private static final Logger LOG = Logger.getLogger(WorkspaceApi.class);

  /**
   * The agent worktrees this workspace hosts (qits-1152): {@code GET} lists them, {@code POST}
   * starts one, and everything about one agent sits below {@code /agent-worktrees/{agentId}}.
   */
  static final String AGENT_WORKTREES_PATH = "/agent-worktrees";

  /** The file routes, below one agent: its wrapper worktree is their root. */
  static final String FILES_PATH = "/files";

  static final String CONTENT_PATH = "/files/content";
  static final String DETECTION_PATH = "/detection";
  static final String COMPONENT_MAP_PATH = "/component-map";

  /** The per-agent verbs, below {@code /agent-worktrees/{agentId}}. */
  static final String YIELD_PATH = "/yield";

  static final String TURN_PATH = "/turn";
  static final String ENTITY_PATH = "/entity";
  static final String BLOCKED_PATH = "/blocked";
  static final String CLEANUP_CHECK_PATH = "/cleanup-check";

  /**
   * The commands surface, from {@code qits-commands}. These came from the host's {@code
   * /api/commands**}, which drove every process through a {@code docker exec} client; here the
   * processes are this daemon's own children.
   *
   * <p>They carry no {@code {repoId}/{workspaceId}} prefix, unlike their host originals and like
   * every other route on this server: the daemon serves exactly one workspace, so the segments
   * would be a constant the caller has to get right. {@code CommandJson} puts both ids back into
   * the response bodies, so the host's {@code CommandDto} still reconstructs unchanged.
   */
  static final String COMMANDS_PATH = "/commands";

  /**
   * The agent surface that is not bound to one agent. Prefix-free like {@link #COMMANDS_PATH}: the
   * daemon serves one workspace, so a {@code /{repoId}/{workspaceId}} prefix would be a constant the
   * caller has to get right. Launching, turns and the entity moved to {@link #AGENT_WORKTREES_PATH}
   * with qits-1152, since each of those belongs to one agent.
   */
  static final String AGENTS_AVAILABLE_PATH = "/agents/available";

  /**
   * The sign-in terminal — <b>a door, and until now there was none</b>.
   *
   * <p>The login REPL used to be reachable only by accident: an unauthenticated harness made {@code
   * POST /agents} quietly answer a login terminal instead of the session that was asked for, and the
   * caller redirected the user into it without ever saying so. The harness library has removed that
   * substitution (a launch against a harness nobody has signed in refuses, see the 409 below), which
   * would have left the terminal unreachable and an unauthenticated platform with no way to become
   * an authenticated one. So the door is explicit: somebody has to complete the OAuth once per
   * credential volume, and they do it by asking for it.
   */
  static final String AGENTS_SIGN_IN_PATH = "/agents/sign-in";

  static final String AGENT_SESSIONS_PATH = "/agent-sessions";

  static final String AGENT_PLUGINS_PATH = "/agent-plugins";

  static final String PROMPT_REFINEMENTS_PATH = "/prompt-refinements";

  private static final String BEARER = "Bearer ";

  @Inject Vertx vertx;

  // The port qits reaches this daemon's API on, through the reverse tunnel. Still distinct from
  // hooks-port: they are different surfaces with different callers, and collapsing them onto one
  // listener would put the unauthenticated hook endpoint behind the tunnel too.
  @ConfigProperty(name = "qits.workspace-daemon.api-port", defaultValue = "13338")
  int apiPort;

  // Loopback, like the hook webhook, and for what is now the same reason: the only client that
  // reaches this server shares the container's network namespace. That client is DaemonStreamTunnel,
  // which dials out to qits and pipes the connection here. Configurable, but there is no longer a
  // deployment shape that wants it wider — see the class javadoc.
  @ConfigProperty(name = "qits.workspace-daemon.api-bind-address", defaultValue = "127.0.0.1")
  String apiBindAddress;

  /**
   * The public base this API is addressed at, injected by qits-workspaces as {@code
   * /workspaces/container/{workspaceId}}. Empty when nothing fronts the daemon, which is what every
   * direct caller (a test, a loopback probe) gets.
   *
   * <p><b>Told, never derived.</b> The proxy in front of this daemon forwards the caller's path
   * untouched — deliberately, because a hop that rewrites a path leaves the two ends disagreeing
   * about the daemon's own address, and that disagreement surfaces far from the rewrite. So the
   * daemon is configured with the part of the path that is its address rather than guessing at one:
   * no leading segment is stripped by shape, no prefix is matched by pattern. It is the same
   * property the control-socket url has — handed over whole, dialled verbatim, never parsed.
   *
   * <p>The routes below stay written as the paths they are, {@code /files} and not {@code
   * <base>/files}: the base is where this server is mounted, not part of what it serves, so exactly
   * one place — {@link #route} — knows about it.
   *
   * <p>{@code Optional<String>} rather than a {@code defaultValue = ""}, for the reason README.md
   * gives for every identity value: SmallRye reads an empty default as <em>no value</em> and then
   * fails to resolve a plain {@code String} when nothing is injected. A daemon with no base is the
   * normal case, so that spelling made the binary die on startup with "Failed to load config value
   * of type class java.lang.String" — and nothing in the suite could see it, because these tests
   * construct {@code WorkspaceApi} directly and never resolve config at all. Running the image with
   * no environment is what catches this class of mistake.
   */
  @ConfigProperty(name = "qits.workspace-daemon.api-base-path")
  Optional<String> apiBasePath;

  /** {@link #apiBasePath}, normalized: no trailing slash, empty when nothing fronts the daemon. */
  private String basePath = "";

  // The shared secret every request must present as `Authorization: Bearer <token>`. Optional<> for
  // the same SmallRye reason as ControlSocket's identity knobs (an empty default resolves as "no
  // value"). Blank/absent ⇒ the server never binds; see the class javadoc for why fail-closed.
  @ConfigProperty(name = "qits.workspace-daemon.api-token")
  Optional<String> apiTokenConfig;

  /**
   * Off-event-loop pool for the handlers: every endpoint forks git and reads files, and a blocking
   * call on the event loop would stall the socket writes and the hook webhook with it. One thread
   * per in-flight request, mirroring {@link ControlSocket}'s worker pool.
   */
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-api");
            thread.setDaemon(true);
            return thread;
          });

  private volatile HttpServer server;
  private volatile AgentWorktrees worktrees;
  private volatile Supplier<List<DeclaredFramework>> declaredFrameworks = List::of;
  private volatile String token;

  /** One agent's file services, made on its first file request and dropped with the agent. */
  private record AgentFiles(
      WorkspaceFileBrowser browser, DetectionService detection, ComponentMapService componentMap) {}

  private final Map<String, AgentFiles> agentFiles = new ConcurrentHashMap<>();

  /**
   * The commands capability, wired separately from {@link #start} because it is available earlier:
   * the file and detection endpoints need a provisioned checkout to read, while commands only needs
   * the process machinery. {@link ControlSocket} still wires it in the same sequence for
   * simplicity. Null until wired, in which case every {@code /commands} route answers 503 rather
   * than NPEing into the event loop.
   */
  private volatile CommandService commands;

  private volatile CommandRegistry registry;
  private volatile WorkspaceContext workspaceContext;

  /** The agents, wired alongside commands; null until then — every agent route answers 503. */
  private volatile AgentRuntime agents;

  /** The workspace's own launch service: it opens the sign-in terminal, which is nobody's agent. */
  private volatile AgentLaunchService agentLaunch;

  private volatile AgentSessionQueryService agentSessions;
  private volatile AgentPluginService agentPlugins;
  private volatile PromptRefinementService promptRefinement;
  private volatile AgentDefaults agentDefaults;

  /**
   * The workspace image version this container runs, answered beside the capability reports so the
   * host can key its cache by it. Told by {@link ControlSocket}; never derived here.
   */
  private volatile String imageVersion = "";

  /**
   * The boot-time harness capability reports, read through a supplier because the probe runs off the
   * boot thread and lands after this surface is wired. A request that arrives first gets an empty
   * list — an honest "nothing reported yet", which the host reads as a cache miss.
   */
  private volatile java.util.function.Supplier<List<HarnessCapabilities>> harnessCapabilities =
      List::of;

  /**
   * Wire the commands surface. Separate from {@link #start} so the two capabilities' preconditions
   * stay independent and a test can exercise either alone.
   */
  void wireCommands(
      CommandService commands, CommandRegistry registry, WorkspaceContext workspaceContext) {
    this.commands = commands;
    this.registry = registry;
    this.workspaceContext = workspaceContext;
  }

  /**
   * Wire the coding-agent surface. Separate from {@link #wireCommands} only so a test can exercise
   * commands without standing up a harness; {@link ControlSocket} wires both together.
   */
  void wireAgents(
      AgentRuntime agents,
      AgentLaunchService agentLaunch,
      AgentSessionQueryService agentSessions,
      AgentPluginService agentPlugins,
      PromptRefinementService promptRefinement,
      AgentDefaults agentDefaults,
      String imageVersion,
      java.util.function.Supplier<List<HarnessCapabilities>> harnessCapabilities) {
    this.agents = agents;
    this.agentLaunch = agentLaunch;
    this.agentSessions = agentSessions;
    this.agentPlugins = agentPlugins;
    this.promptRefinement = promptRefinement;
    this.agentDefaults = agentDefaults;
    this.imageVersion = imageVersion == null ? "" : imageVersion;
    this.harnessCapabilities = harnessCapabilities == null ? List::of : harnessCapabilities;
  }

  /**
   * Bind, unless no token is configured. Called from {@link ControlSocket} once the base clone is
   * provisioned: before that no agent worktree can exist, so nothing here could be answered.
   *
   * @param worktrees the base clone and the agent worktrees made from it; each agent's wrapper
   *     worktree is the root of its file routes
   * @param declaredFrameworks the base clone's own {@code frameworks:} hints; a supplier, because a
   *     {@code SIGHUP} re-reads them
   */
  public void start(
      AgentWorktrees worktrees, Supplier<List<DeclaredFramework>> declaredFrameworks) {
    String configured = apiTokenConfig.map(String::trim).orElse("");
    if (configured.isEmpty()) {
      LOG.warn(
          "No qits.workspace-daemon.api-token configured — the workspace API stays unbound. It"
              + " serves untrusted checkouts, so it is never served anonymously.");
      return;
    }
    listen(vertx, apiBindAddress, apiPort, configured, worktrees, declaredFrameworks)
        .onSuccess(
            s ->
                LOG.infof(
                    "workspace-daemon API listening on %s:%d", apiBindAddress, s.actualPort()))
        .onFailure(
            t ->
                LOG.errorf(
                    t, "workspace-daemon API failed to bind %s:%d", apiBindAddress, apiPort));
  }

  /**
   * The wiring and bind, with everything explicit. Package-private and returning the listen future
   * so a test can bind an ephemeral port (pass {@code 0}) and read the one it actually got — the
   * handlers here fork git and touch the filesystem, so they are worth exercising over a real
   * socket rather than only through a seam.
   */
  Future<HttpServer> listen(
      Vertx vertx,
      String bindAddress,
      int port,
      String token,
      AgentWorktrees worktrees,
      Supplier<List<DeclaredFramework>> declaredFrameworks) {
    this.worktrees = worktrees;
    this.declaredFrameworks = declaredFrameworks == null ? List::of : declaredFrameworks;
    this.token = token;
    // Null when constructed directly rather than by CDI, which is how every test here builds it.
    this.basePath = normalizeBase(apiBasePath == null ? null : apiBasePath.orElse(null));
    HttpServer bound = vertx.createHttpServer();
    this.server = bound;
    return bound
        .requestHandler(this::onRequest)
        // The interactive half of commands. Authenticated at the handshake so an unauthenticated
        // caller never gets a socket, and served here rather than over the control socket because
        // that protocol's command messages are fire-and-collect — no stdin, no resize. A command id
        // is unique across agents, so the socket needs no agent in its path.
        .webSocketHandshakeHandler(
            handshake ->
                CommandSockets.onHandshake(
                    handshake,
                    registry != null && authorized(handshake.headers()),
                    route(handshake.path())))
        .webSocketHandler(socket -> CommandSockets.attach(socket, registry, route(socket.path())))
        .listen(port, bindAddress);
  }

  /**
   * Normalize a configured base: a leading slash, no trailing one, and empty for every spelling of
   * "nothing fronts me" ({@code null}, blank, {@code "/"}). Empty is the default and keeps a
   * directly-addressed daemon behaving exactly as it did before a base existed.
   */
  private static String normalizeBase(String configured) {
    if (configured == null || configured.isBlank() || configured.equals("/")) {
      return "";
    }
    String value = configured.strip();
    if (!value.startsWith("/")) {
      value = "/" + value;
    }
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  /**
   * The route a request addresses: what is left of its path once the base this server is mounted at
   * is accounted for, or {@code null} when the request was not addressed to this daemon at all.
   *
   * <p>The trailing-slash check is what keeps {@code /workspaces/container/12/files} from matching
   * a base of {@code /workspaces/container/1}. A plain {@code startsWith} would route one
   * workspace's request into another's daemon — which, on a host that runs a container per
   * workspace, is a cross-workspace read, not a 404.
   */
  private String route(String path) {
    if (basePath.isEmpty()) {
      return path;
    }
    if (path == null || !path.startsWith(basePath)) {
      return null;
    }
    String rest = path.substring(basePath.length());
    if (rest.isEmpty()) {
      return "/";
    }
    return rest.startsWith("/") ? rest : null;
  }

  /**
   * The configured port, readable before {@link #start} runs — {@link DaemonStreamTunnel} needs it
   * to reach this server on loopback, and is constructed earlier in the boot sequence than the
   * bind. Distinct from {@link #actualPort()}, which is what was actually bound (and is {@code 0}
   * until then, so a test can ask for an ephemeral).
   */
  int apiPort() {
    return apiPort;
  }

  /** The bound port, {@code 0} before a successful listen — the test's handle on an ephemeral. */
  int actualPort() {
    HttpServer s = server;
    return s == null ? 0 : s.actualPort();
  }

  /**
   * Authenticate, then hand the request to a worker. Nothing blocking runs here: the reply is
   * marshalled back onto the request's own context to write, the same discipline {@link
   * ControlSocket} uses for its socket frames.
   */
  private void onRequest(HttpServerRequest request) {
    if (!authorized(request)) {
      // Deliberately indistinguishable from a bad token and stated without detail: a caller with no
      // credential learns only that one is required, never whether the path it asked for exists.
      respond(request, 401, WorkspaceJson.error("Unauthorized"));
      return;
    }
    String path = route(request.path());
    if (path == null) {
      // Addressed at some other base — the same 404 an unknown endpoint gets, because a caller who
      // guessed the wrong container should learn no more than one who guessed the wrong path.
      respond(request, 404, WorkspaceJson.error("No such endpoint"));
      return;
    }
    if (path.equals(COMMANDS_PATH) || path.startsWith(COMMANDS_PATH + "/")) {
      onCommandRequest(request, path);
      return;
    }
    if (path.equals(AGENT_WORKTREES_PATH) || path.startsWith(AGENT_WORKTREES_PATH + "/")) {
      onWorktreeRequest(request, path);
      return;
    }
    if (isAgentPath(path)) {
      onAgentRequest(request, path);
      return;
    }
    respond(request, 404, WorkspaceJson.error("No such endpoint"));
  }

  /**
   * The {@code /commands} routes. Split out from {@link #onRequest} because they need two things
   * the read API never did: path segments after a fixed prefix ({@code /commands/{id}/log}), and a
   * request body ({@code POST /commands} carries the action id). The body is read on the event loop
   * — it is a few dozen bytes — and only then is the work handed to a worker.
   */
  private void onCommandRequest(HttpServerRequest request, String path) {
    if (commands == null) {
      // Wired late, or not at all in a degraded boot. A retryable status, like the unprovisioned
      // checkout's 503, rather than a 404 that reads as "this daemon will never serve commands".
      respond(request, 503, WorkspaceJson.error("Commands are not available yet"));
      return;
    }
    HttpMethod method = request.method();
    if (method != HttpMethod.GET && method != HttpMethod.POST) {
      respond(request, 405, WorkspaceJson.error("Method not allowed"));
      return;
    }
    Context context = vertx.getOrCreateContext();
    request
        .body()
        .onFailure(t -> respond(request, 400, WorkspaceJson.error("Could not read the request body")))
        .onSuccess(
            body -> {
              try {
                workers.execute(
                    () -> {
                      Reply reply = dispatchCommand(method, path, request, body.toString());
                      context.runOnContext(v -> respond(request, reply.status(), reply.body()));
                    });
              } catch (RejectedExecutionException shuttingDown) {
                respond(request, 503, WorkspaceJson.error("Shutting down"));
              }
            });
  }

  /**
   * The {@code /agent-worktrees} routes (qits-1152). Same shape as {@link #onAgentRequest} — 503
   * until wired, body read on the event loop, work on a worker — plus {@code DELETE}, which only
   * these routes take.
   */
  private void onWorktreeRequest(HttpServerRequest request, String path) {
    if (worktrees == null) {
      respond(request, 503, WorkspaceJson.error("Agents are not available yet"));
      return;
    }
    HttpMethod method = request.method();
    if (method != HttpMethod.GET && method != HttpMethod.POST && method != HttpMethod.DELETE) {
      respond(request, 405, WorkspaceJson.error("Method not allowed"));
      return;
    }
    Context context = vertx.getOrCreateContext();
    request
        .body()
        .onFailure(t -> respond(request, 400, WorkspaceJson.error("Could not read the request body")))
        .onSuccess(
            body -> {
              try {
                workers.execute(
                    () -> {
                      Reply reply = dispatchWorktree(method, path, request, body.toString());
                      context.runOnContext(v -> respond(request, reply.status(), reply.body()));
                    });
              } catch (RejectedExecutionException shuttingDown) {
                respond(request, 503, WorkspaceJson.error("Shutting down"));
              }
            });
  }

  /** Route and run one {@code /agent-worktrees} request, every failure turned into a status. */
  private Reply dispatchWorktree(
      HttpMethod method, String path, HttpServerRequest request, String body) {
    try {
      String rest = path.substring(AGENT_WORKTREES_PATH.length());
      // The file routes need only the worktrees; everything else needs the agents, which are wired
      // a moment later and not at all on a daemon that cannot reach its MCP servers.
      boolean files = rest.endsWith(FILES_PATH) || rest.endsWith(CONTENT_PATH)
          || rest.endsWith(DETECTION_PATH) || rest.endsWith(COMPONENT_MAP_PATH);
      if (agents == null && !files) {
        return new Reply(503, WorkspaceJson.error("Agents are not available yet"));
      }
      if (rest.isEmpty() || rest.equals("/")) {
        return switch (method.name()) {
          case "GET" -> new Reply(200, AgentWorktreeJson.views(agents.list()));
          case "POST" -> new Reply(200, AgentWorktreeJson.started(agents.start(startRequest(body))));
          default -> new Reply(405, WorkspaceJson.error("Method not allowed"));
        };
      }
      String[] segments = rest.substring(1).split("/", 2);
      String agentId = segments[0];
      String verb = segments.length > 1 ? "/" + segments[1] : "";
      if (!AgentWorktrees.validAgentId(agentId)) {
        return new Reply(404, WorkspaceJson.error("No such agent"));
      }
      return switch (verb) {
        case "" ->
            switch (method.name()) {
              case "GET" -> new Reply(200, AgentWorktreeJson.view(agents.view(agentId)));
              case "DELETE" -> remove(agentId, "true".equalsIgnoreCase(request.getParam("force")));
              default -> new Reply(405, WorkspaceJson.error("Method not allowed"));
            };
        case YIELD_PATH ->
            method == HttpMethod.POST
                ? new Reply(200, AgentWorktreeJson.yielded(agentId, agents.yield(agentId)))
                : new Reply(405, WorkspaceJson.error("Method not allowed"));
        case TURN_PATH ->
            method == HttpMethod.POST
                ? deliverTurn(agentId, body)
                : new Reply(405, WorkspaceJson.error("Method not allowed"));
        case ENTITY_PATH ->
            method == HttpMethod.POST
                ? setEntity(agentId, body)
                : new Reply(405, WorkspaceJson.error("Method not allowed"));
        case BLOCKED_PATH ->
            method == HttpMethod.POST
                ? setBlocked(agentId, body)
                : new Reply(405, WorkspaceJson.error("Method not allowed"));
        case CLEANUP_CHECK_PATH ->
            method == HttpMethod.GET
                ? new Reply(200, AgentWorktreeJson.cleanupCheck(agents.cleanupCheck(agentId)))
                : new Reply(405, WorkspaceJson.error("Method not allowed"));
        case FILES_PATH, CONTENT_PATH, DETECTION_PATH, COMPONENT_MAP_PATH ->
            method == HttpMethod.GET
                ? dispatchFiles(agentId, verb, request.getParam("path"))
                : new Reply(405, WorkspaceJson.error("Method not allowed"));
        default -> new Reply(404, WorkspaceJson.error("No such endpoint"));
      };
    } catch (AgentWorktrees.AgentWorktreeException e) {
      return new Reply(e.status(), WorkspaceJson.error(e.getMessage()));
    } catch (AgentRuntime.UnknownAgentException e) {
      return new Reply(404, WorkspaceJson.error(e.getMessage()));
    } catch (WorkspaceFilesException e) {
      return new Reply(e.status(), WorkspaceJson.error(e.getMessage()));
    } catch (CommandNotFoundException e) {
      return new Reply(404, WorkspaceJson.error(e.getMessage()));
    } catch (InvalidCommandRequestException e) {
      return new Reply(400, WorkspaceJson.error(e.getMessage()));
    } catch (AgentNotSignedInException e) {
      return new Reply(409, notSignedIn(e));
    } catch (RuntimeException e) {
      LOG.errorf(e, "workspace-daemon agent-worktrees API failed handling %s", path);
      return new Reply(500, WorkspaceJson.error("Internal error"));
    }
  }

  /**
   * {@code DELETE /agent-worktrees/{agentId}} — refused with 409 and the cleanup check while there
   * is work that would be lost (D5), unless {@code force=true}.
   */
  private Reply remove(String agentId, boolean force) {
    AgentWorktrees.CleanupCheck refused = agents.remove(agentId, force);
    if (refused != null) {
      return new Reply(409, AgentWorktreeJson.cleanupCheck(refused));
    }
    agentFiles.remove(agentId);
    return new Reply(200, AgentWorktreeJson.removed(agentId));
  }

  /**
   * {@code POST /agent-worktrees} — the start request. {@code agentId}, {@code workId} and {@code
   * wrapperBranch} are required; {@code env} is the agent's credential and must be an object of
   * strings, because it becomes a process environment and nothing else.
   */
  private static AgentRuntime.Start startRequest(String body) {
    JsonObject json = jsonBody(body);
    String agentId = json.getString("agentId");
    if (agentId == null || agentId.isBlank()) {
      throw new InvalidCommandRequestException("agentId is required");
    }
    String wrapperBranch = json.getString("wrapperBranch");
    if (wrapperBranch == null || wrapperBranch.isBlank()) {
      throw new InvalidCommandRequestException("wrapperBranch is required");
    }
    Map<String, String> env = new java.util.LinkedHashMap<>();
    Object rawEnv = json.getValue("env");
    if (rawEnv != null) {
      if (!(rawEnv instanceof JsonObject envObject)) {
        throw new InvalidCommandRequestException("env must be an object of strings");
      }
      for (Map.Entry<String, Object> entry : envObject) {
        if (!(entry.getValue() instanceof String value)) {
          throw new InvalidCommandRequestException("env must be an object of strings");
        }
        env.put(entry.getKey(), value);
      }
    }
    return new AgentRuntime.Start(
        agentId,
        json.getString("workId"),
        json.getString("entityId"),
        wrapperBranch,
        parseEnum(json.getString("harness"), AgentType::valueOf, "harness"),
        json.getString("sessionId"),
        env,
        json.getString("instruction"),
        surface(json.getString("surface")),
        parseEnum(json.getString("mode"), AgentLaunchMode::valueOf, "mode"),
        entityFacts(json));
  }

  /** The entity facts a start may carry; null when it carries none. */
  private static EntityFacts entityFacts(JsonObject json) {
    Object title = json.getValue("entityTitle");
    Object status = json.getValue("entityStatus");
    Object blocked = json.getValue("entityBlocked");
    Object blockSource = json.getValue("blockSource");
    if (title == null && status == null && blocked == null && blockSource == null) {
      return null;
    }
    if ((title != null && !(title instanceof String))
        || (status != null && !(status instanceof String))
        || (blocked != null && !(blocked instanceof Boolean))
        || (blockSource != null && !(blockSource instanceof String))) {
      throw new InvalidCommandRequestException(
          "entityTitle, entityStatus and blockSource must be strings, entityBlocked a boolean");
    }
    return new EntityFacts(
        (String) title, (String) status, Boolean.TRUE.equals(blocked), (String) blockSource);
  }

  /**
   * {@code POST /agent-worktrees/{agentId}/turn} — deliver {@code text} to the agent's harness. A
   * stopped harness is started again with its session first, the turn as its opening turn (D16), so
   * {@code delivered} is true whenever the agent is known; {@code restarted} says which happened.
   *
   * <p>Blank text is a 400 rather than a delivered no-op: on the chat arm an empty user turn is a
   * turn the harness will answer, and on the terminal arm a bare carriage return into whatever
   * holds the prompt. Neither is what a caller with an empty string meant.
   *
   * <p>The keystroke caveat stays: a TERMINAL session has no stdin channel of its own, so a turn is
   * keystrokes, and a TUI that is still starting has no prompt to type into yet. {@code delivered}
   * means the bytes were accepted for a live session, never that a prompt consumed them.
   */
  private Reply deliverTurn(String agentId, String body) {
    String text = jsonBody(body).getString("text");
    if (text == null || text.isBlank()) {
      return new Reply(400, WorkspaceJson.error("text is required"));
    }
    return new Reply(200, AgentWorktreeJson.turn(agents.turn(agentId, text)));
  }

  /**
   * {@code POST /agent-worktrees/{agentId}/blocked} — mark the agent's entity BLOCKED or not,
   * keeping its title and status, and rename its live session to match. {@code blocked} is
   * required and must be a JSON boolean; {@code blockSource} is optional.
   */
  private Reply setBlocked(String agentId, String body) {
    JsonObject json = jsonBody(body);
    if (!(json.getValue("blocked") instanceof Boolean blocked)) {
      return new Reply(400, WorkspaceJson.error("blocked is required and must be a boolean"));
    }
    Object blockSource = json.getValue("blockSource");
    if (blockSource != null && !(blockSource instanceof String)) {
      return new Reply(400, WorkspaceJson.error("blockSource must be a string or null"));
    }
    int renamed = agents.setBlocked(agentId, blocked, (String) blockSource);
    return new Reply(200, AgentJson.blocked(blocked, renamed));
  }

  /**
   * {@code POST /agent-worktrees/{agentId}/entity} — replace the agent's entity facts and rename its
   * live session to match: the daemon-side twin of {@code AgentLaunchService.setEntity}.
   *
   * <p>{@code blocked} is required and a JSON boolean. {@code title} and {@code status} are each a
   * string, or null or absent for "not known" — which the session name renders by dropping that
   * fact: the body is the whole of what the host knows, so a field it omits is cleared. {@code
   * blockSource} ({@code EXPLICIT}, {@code AGENT_WAITING} or {@code BOTH}) is passed through
   * untouched; the library alone decides which mark it draws.
   */
  private Reply setEntity(String agentId, String body) {
    JsonObject json = jsonBody(body);
    if (!(json.getValue("blocked") instanceof Boolean blocked)) {
      return new Reply(400, WorkspaceJson.error("blocked is required and must be a boolean"));
    }
    Object title = json.getValue("title");
    if (title != null && !(title instanceof String)) {
      return new Reply(400, WorkspaceJson.error("title must be a string or null"));
    }
    Object status = json.getValue("status");
    if (status != null && !(status instanceof String)) {
      return new Reply(400, WorkspaceJson.error("status must be a string or null"));
    }
    Object blockSource = json.getValue("blockSource");
    if (blockSource != null && !(blockSource instanceof String)) {
      return new Reply(400, WorkspaceJson.error("blockSource must be a string or null"));
    }
    EntityFacts facts =
        new EntityFacts((String) title, (String) status, blocked, (String) blockSource);
    int renamed = agents.setEntity(agentId, facts);
    return new Reply(200, AgentJson.entity(facts, renamed));
  }

  /**
   * The file routes of one agent, rooted at its wrapper worktree. The services are made on first
   * use and kept, so the detection caches survive between requests; they key on the worktree's
   * marker, so an edit is seen on the next request.
   */
  private Reply dispatchFiles(String agentId, String verb, String pathParam) {
    if (!worktrees.exists(agentId)) {
      return new Reply(404, WorkspaceJson.error("No such agent"));
    }
    AgentFiles files =
        agentFiles.computeIfAbsent(
            agentId,
            id -> {
              LocalWorkspaceFiles local = new LocalWorkspaceFiles(worktrees.wrapperDir(id));
              return new AgentFiles(
                  new WorkspaceFileBrowser(local),
                  new DetectionService(local, () -> declaredFrameworks.get()),
                  new ComponentMapService(local));
            });
    return switch (verb) {
      case FILES_PATH -> new Reply(200, WorkspaceJson.listing(files.browser().listFiles(pathParam)));
      case CONTENT_PATH ->
          new Reply(200, WorkspaceJson.content(files.browser().readFile(pathParam)));
      case DETECTION_PATH ->
          new Reply(
              200, WorkspaceJson.detection(files.detection().detect(worktrees.marker(agentId))));
      default ->
          new Reply(
              200,
              WorkspaceJson.componentMap(
                  files.componentMap().componentMap(worktrees.marker(agentId))));
    };
  }

  /**
   * The 409 a launch against a harness nobody signed in answers, with a machine-readable
   * discriminator: {@code error} is the contract and the sentence is not.
   */
  private static JsonObject notSignedIn(AgentNotSignedInException e) {
    return new JsonObject()
        .put("error", "not-signed-in")
        .put("agentType", e.harness() == null ? null : e.harness().name())
        .put("message", e.getMessage());
  }

  /** Whether {@code path} belongs to the coding-agent surface. */
  private static boolean isAgentPath(String path) {
    return path.equals(AGENTS_AVAILABLE_PATH)
        || path.equals(AGENTS_SIGN_IN_PATH)
        || path.equals(AGENT_SESSIONS_PATH)
        || path.equals(AGENT_PLUGINS_PATH)
        || path.startsWith(AGENT_PLUGINS_PATH + "/")
        || path.equals(PROMPT_REFINEMENTS_PATH);
  }

  /**
   * The coding-agent routes. Same shape as {@link #onCommandRequest} — 503 until wired, GET/POST
   * only, body read on the event loop and the work handed to a worker — because they have the same
   * two needs: a path segment after a fixed prefix ({@code /agent-plugins/{id}/install}) and a
   * request body.
   */
  private void onAgentRequest(HttpServerRequest request, String path) {
    if (agentLaunch == null) {
      respond(request, 503, WorkspaceJson.error("Coding agents are not available yet"));
      return;
    }
    HttpMethod method = request.method();
    if (method != HttpMethod.GET && method != HttpMethod.POST) {
      respond(request, 405, WorkspaceJson.error("Method not allowed"));
      return;
    }
    Context context = vertx.getOrCreateContext();
    request
        .body()
        .onFailure(t -> respond(request, 400, WorkspaceJson.error("Could not read the request body")))
        .onSuccess(
            body -> {
              try {
                workers.execute(
                    () -> {
                      Reply reply = dispatchAgent(method, path, body.toString());
                      context.runOnContext(v -> respond(request, reply.status(), reply.body()));
                    });
              } catch (RejectedExecutionException shuttingDown) {
                respond(request, 503, WorkspaceJson.error("Shutting down"));
              }
            });
  }

  /**
   * Route and run one coding-agent request.
   *
   * <p>The catch ladder is {@link #dispatchCommand}'s, unchanged, and that is deliberate: because
   * qits-coding-agents depends on qits-commands, its services throw the <em>same</em> two exceptions
   * rather than declaring their own, so one mapping serves both surfaces and the frontend's error
   * handling does not fork.
   */
  private Reply dispatchAgent(HttpMethod method, String path, String body) {
    try {
      String repoId = workspaceContext.repoId();
      String workspaceId = workspaceContext.workspaceId();
      if (AGENTS_AVAILABLE_PATH.equals(path)) {
        return method == HttpMethod.GET
            ? new Reply(
                200,
                AgentJson.available(
                    agentDefaults.defaultAgentType(),
                    imageVersion,
                    harnessCapabilities.get()))
            : new Reply(405, WorkspaceJson.error("Method not allowed"));
      }
      if (AGENTS_SIGN_IN_PATH.equals(path)) {
        // The same {command: …} envelope every other launch answers, so one client-side decoder
        // serves it: a sign-in terminal is a command in this container like any other, and opening
        // one is a normal launch rather than a special case.
        return method == HttpMethod.POST
            ? new Reply(
                200,
                AgentJson.launched(
                    agentLaunch.launchLogin(signInHarness(body)), repoId, workspaceId))
            : new Reply(405, WorkspaceJson.error("Method not allowed"));
      }
      if (AGENT_SESSIONS_PATH.equals(path)) {
        return method == HttpMethod.GET
            ? new Reply(200, AgentJson.sessions(agentSessions.sessionTree()))
            : new Reply(405, WorkspaceJson.error("Method not allowed"));
      }
      if (AGENT_PLUGINS_PATH.equals(path)) {
        return method == HttpMethod.GET
            ? new Reply(200, AgentJson.plugins(agentPlugins.listInstalled()))
            : new Reply(405, WorkspaceJson.error("Method not allowed"));
      }
      if (path.startsWith(AGENT_PLUGINS_PATH + "/")) {
        String rest = path.substring(AGENT_PLUGINS_PATH.length() + 1);
        if (!rest.endsWith("/install") || method != HttpMethod.POST) {
          return new Reply(404, WorkspaceJson.error("No such endpoint"));
        }
        String pluginId = rest.substring(0, rest.length() - "/install".length());
        return new Reply(200, AgentJson.plugins(agentPlugins.install(pluginId)));
      }
      if (PROMPT_REFINEMENTS_PATH.equals(path)) {
        if (method != HttpMethod.POST) {
          return new Reply(405, WorkspaceJson.error("Method not allowed"));
        }
        JsonObject json = jsonBody(body);
        return new Reply(
            200,
            AgentJson.refinement(
                promptRefinement.refine(json.getString("transcript"), json.getString("preamble"))));
      }
      return new Reply(404, WorkspaceJson.error("No such endpoint"));
    } catch (CommandNotFoundException e) {
      return new Reply(404, WorkspaceJson.error(e.getMessage()));
    } catch (InvalidCommandRequestException e) {
      return new Reply(400, WorkspaceJson.error(e.getMessage()));
    } catch (AgentNotSignedInException e) {
      // 409, NOT 500, and with a machine-readable discriminator. Without this arm the refusal falls
      // into the catch below, whose message is deliberately withheld (an arbitrary exception's text
      // can carry container paths), and a browser cannot tell a signed-out platform from a broken
      // one — it would show "Internal error" for a state one click fixes.
      //
      // `error` is the contract and the sentence is not. Matching the prose is the mistake this
      // epic exists to delete: the frontend used to sort sessions by looking for " (tickets desk)"
      // in a display name, and a reworded label silently moved every one of them. A key means the
      // message can be rewritten freely, and `agentType` means the caller can name the harness
      // without parsing it out of a sentence.
      return new Reply(409, notSignedIn(e));
    } catch (RuntimeException e) {
      LOG.errorf(e, "workspace-daemon agents API failed handling %s", path);
      return new Reply(500, WorkspaceJson.error("Internal error"));
    }
  }

  /**
   * The harness a sign-in terminal is opened for: what the body named, else the resolved default.
   *
   * <p>Optional because the common caller is "the harness I was just refused for", which it knows,
   * and the uncommon one is an operator opening the door on a fresh estate, who should not have to.
   */
  private AgentType signInHarness(String body) {
    AgentType requested = parseEnum(jsonBody(body).getString("agentType"), AgentType::valueOf, "agentType");
    return agentDefaults.resolve(requested);
  }

  /**
   * The optional {@code surface} of a start: where in the product the agent was started from, which
   * keys its configuration. Absent means {@code ticket.dispatch}, the surface every dispatched agent
   * runs on. An unknown one is a 400 ({@code AgentSurface.of} refuses it), never a silent default.
   */
  private static AgentSurface surface(String raw) {
    return raw == null || raw.isBlank() ? null : AgentSurface.of(raw);
  }

  private static JsonObject jsonBody(String body) {
    try {
      return new JsonObject(body == null || body.isBlank() ? "{}" : body);
    } catch (RuntimeException notJson) {
      throw new InvalidCommandRequestException("Expected a JSON body");
    }
  }

  /** One answered request: the status and its body, a JSON object or (for a list) array. */
  private record Reply(int status, Object body) {}

  /**
   * Route and run one {@code /commands} request.
   *
   * <p>The status mapping mirrors what the host's JAX-RS exception mappers produced, so the
   * frontend's error handling does not move with the endpoints: an unknown command is 404, a
   * malformed request or unknown action is 400. That is the same obligation migration-plan.md §8
   * step 6 flags — no target inherits {@code DomainExceptionMapper}, so a boundary that does not
   * re-provide the mapping returns 500 where the caller expects 400.
   */
  private Reply dispatchCommand(
      HttpMethod method, String path, HttpServerRequest request, String body) {
    try {
      String repoId = workspaceContext.repoId();
      String workspaceId = workspaceContext.workspaceId();
      // Everything after "/commands", so "" for the collection and "/{id}[/verb]" otherwise.
      String rest = path.substring(COMMANDS_PATH.length());
      if (rest.isEmpty() || rest.equals("/")) {
        // POST /commands launched a checkout-declared action; actions went with the Actions tab
        // (qits-1152).
        return method == HttpMethod.GET
            ? new Reply(
                200,
                CommandJson.commands(
                    commands.list(parseStatus(request.getParam("status"))), repoId, workspaceId))
            : new Reply(405, WorkspaceJson.error("Method not allowed"));
      }
      String[] segments = rest.substring(1).split("/", 2);
      String commandId = segments[0];
      String verb = segments.length > 1 ? segments[1] : "";
      return switch (verb) {
        case "" ->
            method == HttpMethod.GET
                ? new Reply(200, CommandJson.command(commands.get(commandId), repoId, workspaceId))
                : new Reply(405, WorkspaceJson.error("Method not allowed"));
        case "log" ->
            method == HttpMethod.GET
                ? new Reply(
                    200,
                    CommandJson.log(
                        commands.log(
                            commandId,
                            parseSeverity(request.getParam("severity")),
                            parseChannel(request.getParam("channel")))))
                : new Reply(405, WorkspaceJson.error("Method not allowed"));
        case "terminate" ->
            method == HttpMethod.POST
                ? new Reply(
                    200,
                    CommandJson.command(commands.terminate(commandId), repoId, workspaceId))
                : new Reply(405, WorkspaceJson.error("Method not allowed"));
        default -> new Reply(404, WorkspaceJson.error("No such endpoint"));
      };
    } catch (CommandNotFoundException e) {
      return new Reply(404, WorkspaceJson.error(e.getMessage()));
    } catch (InvalidCommandRequestException e) {
      return new Reply(400, WorkspaceJson.error(e.getMessage()));
    } catch (RuntimeException e) {
      // Same posture as dispatch(): the text of an arbitrary exception can carry container paths
      // the caller has no business seeing, so it is logged here and not returned.
      LOG.errorf(e, "workspace-daemon commands API failed handling %s", path);
      return new Reply(500, WorkspaceJson.error("Internal error"));
    }
  }

  /**
   * Query-parameter enums. An unparseable value is a 400 rather than being silently ignored: the
   * host's JAX-RS binding rejected it, and quietly widening a filter would show a caller more than
   * it asked for.
   */
  private static CommandStatus parseStatus(String raw) {
    return parseEnum(raw, CommandStatus::valueOf, "status");
  }

  private static LogSeverity parseSeverity(String raw) {
    return parseEnum(raw, LogSeverity::valueOf, "severity");
  }

  private static LogChannel parseChannel(String raw) {
    return parseEnum(raw, LogChannel::valueOf, "channel");
  }

  private static <T> T parseEnum(String raw, java.util.function.Function<String, T> of, String name) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return of.apply(raw.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidCommandRequestException("Invalid " + name + ": " + raw);
    }
  }

  /**
   * Constant-time bearer check. {@link MessageDigest#isEqual} rather than {@link String#equals}:
   * the latter returns on the first differing character, which over a network-reachable port is a
   * byte-at-a-time oracle on a secret that never rotates within a container's life.
   */
  private boolean authorized(HttpServerRequest request) {
    return authorized(request.headers());
  }

  /** The bearer check itself, over any carrier's headers — a request's or a handshake's. */
  private boolean authorized(io.vertx.core.MultiMap headers) {
    String header = headers.get("Authorization");
    if (header == null || !header.startsWith(BEARER)) {
      return false;
    }
    return MessageDigest.isEqual(
        header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8),
        token.getBytes(StandardCharsets.UTF_8));
  }

  /** Write one JSON answer. Always runs on the request's context, never throws. */
  private static void respond(HttpServerRequest request, int status, Object body) {
    try {
      request
          .response()
          .setStatusCode(status)
          .putHeader("Content-Type", "application/json")
          // The bodies embed repository-controlled text; nosniff keeps a client from ever deciding
          // this is anything other than the JSON it is labelled as.
          .putHeader("X-Content-Type-Options", "nosniff")
          .end(body instanceof JsonArray array ? array.encode() : ((JsonObject) body).encode());
    } catch (RuntimeException e) {
      // A client that vanished mid-response must not surface as an event-loop exception.
      LOG.debugf("workspace-daemon read API could not write a response: %s", e.getMessage());
    }
  }

  /** Stop accepting first, then drop the pool — the reverse order rejects live requests. */
  @PreDestroy
  void close() {
    HttpServer s = server;
    if (s != null) {
      s.close();
    }
    workers.shutdownNow();
  }
}
