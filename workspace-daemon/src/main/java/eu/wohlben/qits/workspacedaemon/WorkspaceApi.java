package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.BootstrapDecl;
import eu.wohlben.qits.workspacedaemon.commands.CommandNotFoundException;
import eu.wohlben.qits.workspacedaemon.commands.CommandRegistry;
import eu.wohlben.qits.workspacedaemon.agents.AgentDefaults;
import eu.wohlben.qits.workspacedaemon.agents.AgentLaunchMode;
import eu.wohlben.qits.workspacedaemon.agents.AgentLaunchRequest;
import eu.wohlben.qits.workspacedaemon.agents.AgentLaunchService;
import eu.wohlben.qits.workspacedaemon.agents.AgentMcpScope;
import eu.wohlben.qits.workspacedaemon.agents.AgentPluginService;
import eu.wohlben.qits.workspacedaemon.agents.AgentSessionQueryService;
import eu.wohlben.qits.workspacedaemon.agents.AgentType;
import eu.wohlben.qits.workspacedaemon.agents.PromptRefinementService;
import eu.wohlben.qits.workspacedaemon.commands.CommandService;
import eu.wohlben.qits.workspacedaemon.commands.CommandStatus;
import eu.wohlben.qits.workspacedaemon.commands.InvalidCommandRequestException;
import eu.wohlben.qits.workspacedaemon.commands.LogChannel;
import eu.wohlben.qits.workspacedaemon.commands.LogSeverity;
import eu.wohlben.qits.workspacedaemon.commands.WorkspaceContext;
import eu.wohlben.qits.workspacedaemon.detection.ComponentMapService;
import eu.wohlben.qits.workspacedaemon.detection.DeclaredFramework;
import eu.wohlben.qits.workspacedaemon.detection.DetectionService;
import eu.wohlben.qits.workspacedaemon.files.LocalWorkspaceFiles;
import eu.wohlben.qits.workspacedaemon.files.WorkspaceFileBrowser;
import eu.wohlben.qits.workspacedaemon.files.WorkspaceFilesException;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The daemon's read API over the checkout it owns: the file browser's listing and file content, the
 * framework detection result, and the Angular component map. It is the transport half of the two
 * capability modules ({@code workspace-daemon-files}, {@code workspace-daemon-detection}) that
 * moved off the host — everything the host used to compute through N {@code docker exec find/cat}
 * spawns per request is now a local {@code java.nio} read, and this server is what qits reaches it
 * through.
 *
 * <p>A raw {@code vertx-core} {@link HttpServer}, exactly like {@link HookWebhook} and for the same
 * reason: the module carries {@code quarkus-vertx} only — no {@code quarkus-rest}, no {@code
 * quarkus-vertx-http}, no JAX-RS — so the native image stays lean and needs nothing registered.
 * Bodies are hand-built {@code JsonObject}s ({@link WorkspaceJson}), because there is no Jackson
 * here either.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   GET /files?path=&lt;rel&gt;           200  {paths[], lazyDirs[{path,childCount}], generation}
 *   GET /files/content?path=&lt;rel&gt;   200  {path, content?, binary}
 *   GET /detection                   200  {projects[], frameworks[], links[], generation}
 *   GET /component-map               200  {framework, components[]}
 * </pre>
 *
 * <p>{@code path} is workspace-root-relative and optional on {@code /files} (absent ⇒ the root
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

  static final String FILES_PATH = "/files";
  static final String CONTENT_PATH = "/files/content";
  static final String DETECTION_PATH = "/detection";
  static final String COMPONENT_MAP_PATH = "/component-map";

  /**
   * The two write endpoints, and the only ones: integrating the parent branch into this workspace.
   * They came from the host, where they were {@code POST
   * /repositories/{repoId}/workspaces/{workspaceId}/{fast-forward,update-from-parent}} driving
   * {@code docker exec git} into this container. Here the checkout is a local path and git runs in
   * this process, serialized behind {@link OriginSync}'s auto-push.
   */
  static final String FAST_FORWARD_PATH = "/fast-forward";

  static final String UPDATE_FROM_PARENT_PATH = "/update-from-parent";

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

  /** {@code GET /commands/actions} — what this checkout declares. New; see {@link CommandJson}. */
  static final String COMMAND_ACTIONS_PATH = "/commands/actions";

  /**
   * The coding-agent surface. Prefix-free like {@link #COMMANDS_PATH} and for the same reason: the
   * daemon serves one workspace, so a {@code /{repoId}/{workspaceId}} prefix would be a constant the
   * caller has to get right. {@code AgentJson} puts both ids back into the response bodies.
   */
  static final String AGENTS_PATH = "/agents";

  static final String AGENTS_AVAILABLE_PATH = "/agents/available";

  static final String AGENT_SESSIONS_PATH = "/agent-sessions";

  static final String AGENT_PLUGINS_PATH = "/agent-plugins";

  static final String PROMPT_REFINEMENTS_PATH = "/prompt-refinements";

  /**
   * The service-supervision surface and the bootstrap chain's. Both were host routes — {@code
   * /workspaces/{id}/services…} and {@code /workspaces/{id}/bootstrap-commands…} — that were
   * <em>deleted rather than moved</em> when the work went into the container: {@link
   * ServiceSupervisor} and {@link BootstrapRunner} do it here, and nothing ever grew routes for
   * them. So the capability survived the move and the addressability did not. These two put the
   * addressability back where the capability already is.
   *
   * <p>Prefix-free like {@link #COMMANDS_PATH} and for the same reason: the daemon serves exactly
   * one workspace, so a {@code /{repoId}/{workspaceId}} prefix would be a constant the caller has
   * to get right.
   */
  static final String SERVICES_PATH = "/services";

  static final String BOOTSTRAP_COMMANDS_PATH = "/bootstrap-commands";

  private static final String BEARER = "Bearer ";

  @Inject Vertx vertx;

  /**
   * Owns the {@link OriginSync} these two routes need — it is created only once the checkout is
   * provisioned, so it is read per request rather than captured.
   */
  @Inject ControlSocket controlSocket;

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
   * property the control-socket url has — handed over whole, dialled verbatim, never parsed — and
   * the same arrangement {@code ServiceProxyRoute} already has with a dev server's {@code
   * QITS_PUBLIC_BASE}.
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
  private volatile WorkspaceFileBrowser browser;
  private volatile DetectionService detection;
  private volatile ComponentMapService componentMap;
  private volatile Supplier<String> marker;
  private volatile String token;

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

  /** The agent capability, wired alongside commands; null until then — every route answers 503. */
  private volatile AgentLaunchService agentLaunch;

  private volatile AgentSessionQueryService agentSessions;
  private volatile AgentPluginService agentPlugins;
  private volatile PromptRefinementService promptRefinement;
  private volatile AgentDefaults agentDefaults;

  /** The service supervisor, wired by {@link ControlSocket}; null ⇒ every route answers 503. */
  private volatile ServiceSupervisor services;

  /** The bootstrap wiring, null until {@link #wireBootstrap} runs; same 503 rule. */
  private volatile BootstrapWiring bootstrap;

  /**
   * Everything {@link BootstrapRunner#run} needs, captured at wiring time. It is a static utility
   * with no state of its own, so there is nothing to hold a reference to — and the module is
   * framework-free and cannot read configuration, so the chain, the working directory and the step
   * timeout all have to arrive from {@link ControlSocket}, the single config reader.
   */
  private record BootstrapWiring(
      String workspaceId,
      Supplier<List<BootstrapDecl>> chain,
      File workingDir,
      long stepTimeoutMs,
      Consumer<DaemonMessage> emit) {}

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
      AgentLaunchService agentLaunch,
      AgentSessionQueryService agentSessions,
      AgentPluginService agentPlugins,
      PromptRefinementService promptRefinement,
      AgentDefaults agentDefaults) {
    this.agentLaunch = agentLaunch;
    this.agentSessions = agentSessions;
    this.agentPlugins = agentPlugins;
    this.promptRefinement = promptRefinement;
    this.agentDefaults = agentDefaults;
  }

  /**
   * Wire the service-supervision surface. Separate from the others for the reason they are separate
   * from each other — the preconditions differ. This one is available earliest of all: {@link
   * ControlSocket} constructs the supervisor before provisioning even starts, so {@code
   * GET /services} answers a declared-but-stopped list while the checkout is still cloning.
   */
  void wireServices(ServiceSupervisor services) {
    this.services = services;
  }

  /** Wire the bootstrap surface; see {@link BootstrapWiring} for why it takes five arguments. */
  void wireBootstrap(
      String workspaceId,
      Supplier<List<BootstrapDecl>> chain,
      File workingDir,
      long stepTimeoutMs,
      Consumer<DaemonMessage> emit) {
    this.bootstrap = new BootstrapWiring(workspaceId, chain, workingDir, stepTimeoutMs, emit);
  }

  /**
   * Wire the capabilities over {@code root} and bind, unless no token is configured. Called from
   * {@link ControlSocket} once the checkout is provisioned — before that there is no git tree, and
   * every endpoint would answer 500 from a failed {@code ls-files}.
   *
   * @param root the workspace checkout (the daemon's {@code /workspace}), passed in rather than
   *     hardcoded so this stays the same object {@link ControlSocket} clones and runs git in
   * @param declaredFrameworks the checkout's own {@code frameworks:} hints; a supplier, not a list,
   *     because the file it comes from lives in the working tree and an agent can edit it
   * @param marker the working-tree marker {@link GitStatusMonitor} already computed behind its
   *     inotify debounce — the detection caches key on it, and recomputing it here would fork the
   *     same two git processes again and could disagree with the value just reported home
   */
  public void start(
      Path root, Supplier<List<DeclaredFramework>> declaredFrameworks, Supplier<String> marker) {
    String configured = apiTokenConfig.map(String::trim).orElse("");
    if (configured.isEmpty()) {
      LOG.warn(
          "No qits.workspace-daemon.api-token configured — the workspace read API stays unbound. It"
              + " is reachable from the whole docker network and serves an untrusted checkout, so"
              + " it is never served anonymously; qits falls back to its host-side file access.");
      return;
    }
    listen(vertx, apiBindAddress, apiPort, configured, root, declaredFrameworks, marker)
        .onSuccess(
            s ->
                LOG.infof(
                    "workspace-daemon read API listening on %s:%d", apiBindAddress, s.actualPort()))
        .onFailure(
            t ->
                LOG.errorf(
                    t, "workspace-daemon read API failed to bind %s:%d", apiBindAddress, apiPort));
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
      Path root,
      Supplier<List<DeclaredFramework>> declaredFrameworks,
      Supplier<String> marker) {
    LocalWorkspaceFiles files = new LocalWorkspaceFiles(root);
    this.browser = new WorkspaceFileBrowser(files);
    this.detection = new DetectionService(files, declaredFrameworks);
    this.componentMap = new ComponentMapService(files);
    this.marker = marker;
    this.token = token;
    // Null when constructed directly rather than by CDI, which is how every test here builds it.
    this.basePath = normalizeBase(apiBasePath == null ? null : apiBasePath.orElse(null));
    HttpServer bound = vertx.createHttpServer();
    this.server = bound;
    return bound
        .requestHandler(this::onRequest)
        // The interactive half of commands. Authenticated at the handshake so an unauthenticated
        // caller never gets a socket, and served here rather than over the control socket because
        // that protocol's command messages are fire-and-collect — no stdin, no resize.
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
    if (isAgentPath(path)) {
      onAgentRequest(request, path);
      return;
    }
    if (isLifecyclePath(path)) {
      onLifecycleRequest(request, path);
      return;
    }
    boolean write = FAST_FORWARD_PATH.equals(path) || UPDATE_FROM_PARENT_PATH.equals(path);
    // GET everywhere except the two parent-integration routes, which mutate the checkout and are
    // POST-only. Checking the pairing here keeps a GET from ever reaching git.
    if (write ? request.method() != HttpMethod.POST : request.method() != HttpMethod.GET) {
      respond(request, 405, WorkspaceJson.error("Method not allowed"));
      return;
    }
    Context context = vertx.getOrCreateContext();
    String param = request.getParam(write ? "parent" : "path");
    try {
      workers.execute(
          () -> {
            Reply reply = dispatch(path, param);
            context.runOnContext(v -> respond(request, reply.status(), reply.body()));
          });
    } catch (RejectedExecutionException shuttingDown) {
      // The pool is gone (a request that raced @PreDestroy). Answer rather than let the rejection
      // become an unhandled event-loop exception; the caller retries against the next container.
      respond(request, 503, WorkspaceJson.error("Shutting down"));
    }
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

  /** Whether {@code path} belongs to the coding-agent surface. */
  private static boolean isAgentPath(String path) {
    return path.equals(AGENTS_PATH)
        || path.equals(AGENTS_AVAILABLE_PATH)
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
            ? new Reply(200, AgentJson.available(agentDefaults.defaultAgentType()))
            : new Reply(405, WorkspaceJson.error("Method not allowed"));
      }
      if (AGENTS_PATH.equals(path)) {
        return method == HttpMethod.POST
            ? new Reply(
                200, AgentJson.launched(agentLaunch.launch(launchRequest(body)), repoId, workspaceId))
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
    } catch (RuntimeException e) {
      LOG.errorf(e, "workspace-daemon agents API failed handling %s", path);
      return new Reply(500, WorkspaceJson.error("Internal error"));
    }
  }

  /** Whether {@code path} belongs to the services / bootstrap surface. */
  private static boolean isLifecyclePath(String path) {
    return path.equals(SERVICES_PATH)
        || path.startsWith(SERVICES_PATH + "/")
        || path.equals(BOOTSTRAP_COMMANDS_PATH)
        || path.startsWith(BOOTSTRAP_COMMANDS_PATH + "/");
  }

  /**
   * The services / bootstrap routes. Same shape as {@link #onAgentRequest} — GET/POST only, body
   * read on the event loop, work handed to a worker — because they have the same two needs: a path
   * segment after a fixed prefix, and a request body.
   */
  private void onLifecycleRequest(HttpServerRequest request, String path) {
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
                      Reply reply = dispatchLifecycle(method, path, request, body.toString());
                      context.runOnContext(v -> respond(request, reply.status(), reply.body()));
                    });
              } catch (RejectedExecutionException shuttingDown) {
                respond(request, 503, WorkspaceJson.error("Shutting down"));
              }
            });
  }

  /**
   * Route and run one services / bootstrap request.
   *
   * <p><b>Every write here answers 202, not 200.</b> Starting a service and running a bootstrap
   * chain are long-running and already report themselves over the control socket — a service as
   * {@code ServiceTransition}s, a chain as {@code BootstrapStep}/{@code BootstrapOutcome}/{@code
   * Bootstrapped} — and a bootstrap step is bounded only by {@code bootstrap-timeout-ms}, which
   * defaults to an hour. Holding a response open for that is not a contract anyone wants, and
   * inventing a second, synchronous report of an outcome the caller is already subscribed to would
   * be two sources of one truth.
   */
  private Reply dispatchLifecycle(
      HttpMethod method, String path, HttpServerRequest request, String body) {
    try {
      return path.equals(SERVICES_PATH) || path.startsWith(SERVICES_PATH + "/")
          ? dispatchService(method, path, request, body)
          : dispatchBootstrap(method, path);
    } catch (InvalidCommandRequestException e) {
      return new Reply(400, WorkspaceJson.error(e.getMessage()));
    } catch (RuntimeException e) {
      // Same posture as dispatch(): an arbitrary exception's text can carry container paths the
      // caller has no business seeing, so it is logged here and not returned.
      LOG.errorf(e, "workspace-daemon lifecycle API failed handling %s", path);
      return new Reply(500, WorkspaceJson.error("Internal error"));
    }
  }

  /** {@code /services} — list, start one, signal one. */
  private Reply dispatchService(
      HttpMethod method, String path, HttpServerRequest request, String body) {
    ServiceSupervisor supervisor = services;
    if (supervisor == null) {
      return new Reply(503, WorkspaceJson.error("Services are not available yet"));
    }
    String rest = path.substring(SERVICES_PATH.length());
    if (rest.isEmpty() || rest.equals("/")) {
      return method == HttpMethod.GET
          ? new Reply(200, WorkspaceJson.services(supervisor.states()))
          : new Reply(405, WorkspaceJson.error("Method not allowed"));
    }
    if (method != HttpMethod.POST) {
      return new Reply(405, WorkspaceJson.error("Method not allowed"));
    }
    String[] segments = rest.substring(1).split("/", 2);
    String name = segments[0];
    String verb = segments.length > 1 ? segments[1] : "";
    return switch (verb) {
      case "start" -> {
        JsonObject json = jsonBody(body);
        supervisor.start(name, json.getString("script"), stringMap(json.getJsonObject("env")));
        yield new Reply(202, WorkspaceJson.accepted());
      }
      case "signal" -> {
        // The query parameter wins over the body so a signal can be sent with no body at all; both
        // absent is the stop signal, which is what SignalService's own default resolves to.
        String signal = request.getParam("signal");
        supervisor.signal(name, signal != null ? signal : jsonBody(body).getString("signal"));
        yield new Reply(202, WorkspaceJson.accepted());
      }
      default -> new Reply(404, WorkspaceJson.error("No such endpoint"));
    };
  }

  /** {@code /bootstrap-commands} — list the chain, run it whole, or run one named step. */
  private Reply dispatchBootstrap(HttpMethod method, String path) {
    BootstrapWiring wiring = bootstrap;
    if (wiring == null) {
      return new Reply(503, WorkspaceJson.error("Bootstrap is not available yet"));
    }
    String rest = path.substring(BOOTSTRAP_COMMANDS_PATH.length());
    if (rest.isEmpty() || rest.equals("/")) {
      return method == HttpMethod.GET
          ? new Reply(200, WorkspaceJson.bootstrapCommands(wiring.chain().get()))
          : new Reply(405, WorkspaceJson.error("Method not allowed"));
    }
    if (method != HttpMethod.POST) {
      return new Reply(405, WorkspaceJson.error("Method not allowed"));
    }
    String[] segments = rest.substring(1).split("/", 2);
    // The whole chain is /bootstrap-commands/run and one step is /bootstrap-commands/{name}/run, so
    // "run" is a reserved step name here. Checking the collection form first is what makes it so.
    if (segments.length == 1 && "run".equals(segments[0])) {
      runBootstrap(wiring, null);
      return new Reply(202, WorkspaceJson.accepted());
    }
    if (segments.length == 2 && "run".equals(segments[1])) {
      runBootstrap(wiring, segments[0]);
      return new Reply(202, WorkspaceJson.accepted());
    }
    return new Reply(404, WorkspaceJson.error("No such endpoint"));
  }

  /**
   * Hand the chain to the worker pool and return. The run streams itself home over the control
   * socket exactly as {@code RunBootstrap} does, so there is nothing left to answer with — and it is
   * bounded by a step timeout that defaults to an hour, so returning immediately is the point.
   */
  private void runBootstrap(BootstrapWiring wiring, String onlyName) {
    workers.execute(
        () ->
            BootstrapRunner.run(
                wiring.workspaceId(),
                wiring.chain().get(),
                onlyName,
                wiring.workingDir(),
                wiring.stepTimeoutMs(),
                wiring.emit()));
  }

  /**
   * A JSON object read as an environment overlay. Values are coerced with {@code String.valueOf}
   * rather than {@code getString}, because a {@code .qits-config.yml}-shaped body writing {@code
   * PORT: 8080} means the number, and a null overlay entry is dropped rather than becoming the text
   * "null" in a child process's environment.
   */
  private static java.util.Map<String, String> stringMap(JsonObject json) {
    if (json == null) {
      return null;
    }
    java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
    for (String key : json.fieldNames()) {
      Object value = json.getValue(key);
      if (value != null) {
        map.put(key, String.valueOf(value));
      }
    }
    return map;
  }

  /** {@code POST /agents} — the launch request, with the enums validated like a query parameter. */
  private static AgentLaunchRequest launchRequest(String body) {
    JsonObject json = jsonBody(body);
    return new AgentLaunchRequest(
        parseEnum(json.getString("scope"), AgentMcpScope::valueOf, "scope"),
        parseEnum(json.getString("mode"), AgentLaunchMode::valueOf, "mode"),
        json.getString("initialContext"),
        json.getString("resumeSessionId"),
        Boolean.TRUE.equals(json.getBoolean("fork")),
        Boolean.TRUE.equals(json.getBoolean("deliverTaskPrompt")),
        parseEnum(json.getString("agentType"), AgentType::valueOf, "agentType"));
  }

  private static JsonObject jsonBody(String body) {
    try {
      return new JsonObject(body == null || body.isBlank() ? "{}" : body);
    } catch (RuntimeException notJson) {
      throw new InvalidCommandRequestException("Expected a JSON body");
    }
  }

  /** One answered request: the status and the body that goes with it. */
  private record Reply(int status, JsonObject body) {}

  /**
   * Route and run, converting every failure into a status. {@link WorkspaceFilesException} already
   * carries the one the host used to answer with ({@link WorkspaceFilesException#status()}), so the
   * browser UI's "invalid path" and "no such file" states keep resolving exactly as before;
   * anything else is a 500 whose message is logged here and not returned, because an arbitrary
   * exception's text can carry container paths the caller has no business seeing.
   */
  private Reply dispatch(String path, String pathParam) {
    try {
      return switch (path) {
        case FILES_PATH -> new Reply(200, WorkspaceJson.listing(browser.listFiles(pathParam)));
        case CONTENT_PATH -> new Reply(200, WorkspaceJson.content(browser.readFile(pathParam)));
        case DETECTION_PATH -> new Reply(200, WorkspaceJson.detection(detection.detect(marker())));
        case COMPONENT_MAP_PATH ->
            new Reply(200, WorkspaceJson.componentMap(componentMap.componentMap(marker())));
        case FAST_FORWARD_PATH -> integrate(pathParam, true);
        case UPDATE_FROM_PARENT_PATH -> integrate(pathParam, false);
        default -> new Reply(404, WorkspaceJson.error("No such endpoint"));
      };
    } catch (WorkspaceFilesException e) {
      return new Reply(e.status(), WorkspaceJson.error(e.getMessage()));
    } catch (RuntimeException e) {
      LOG.errorf(e, "workspace-daemon read API failed handling %s", path);
      return new Reply(500, WorkspaceJson.error("Internal error"));
    }
  }

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
        return method == HttpMethod.POST
            ? launchCommand(body, repoId, workspaceId)
            : new Reply(
                200,
                CommandJson.commands(
                    commands.list(parseStatus(request.getParam("status"))), repoId, workspaceId));
      }
      if (COMMAND_ACTIONS_PATH.equals(path) && method == HttpMethod.GET) {
        return new Reply(200, CommandJson.actions(commands.availableActions()));
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

  /** {@code POST /commands} — launch a declared action by id. */
  private Reply launchCommand(String body, String repoId, String workspaceId) {
    String actionId;
    try {
      actionId = new JsonObject(body == null || body.isBlank() ? "{}" : body).getString("actionId");
    } catch (RuntimeException notJson) {
      return new Reply(400, WorkspaceJson.error("Expected a JSON body"));
    }
    if (actionId == null || actionId.isBlank()) {
      return new Reply(400, WorkspaceJson.error("actionId is required"));
    }
    return new Reply(
        200, CommandJson.launched(commands.launch(actionId), repoId, workspaceId));
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
   * Integrate {@code parentBranch} into this workspace's checkout: fast-forward onto it when {@code
   * fastForwardOnly}, otherwise merge it in with a merge commit. The two differ only in that,
   * exactly as the host's two routes did.
   *
   * <p>A refusal is a <b>400</b>, not a 500: "this branch has diverged" and "that merge would
   * conflict" are the answers the UI acts on, and git's own text goes back with them so the user
   * sees what it saw. 503 when the checkout has no {@link OriginSync} yet — the daemon is up but
   * has not finished provisioning, and the caller should retry rather than treat it as a failure.
   */
  private Reply integrate(String parentBranch, boolean fastForwardOnly) {
    OriginSync sync = controlSocket.originSync();
    if (sync == null) {
      return new Reply(503, WorkspaceJson.error("Workspace is not provisioned yet"));
    }
    OriginSync.ParentOpResult result =
        fastForwardOnly ? sync.fastForwardOntoParent(parentBranch) : sync.mergeParentIn(parentBranch);
    return result.ok()
        ? new Reply(200, WorkspaceJson.output(result.output()))
        : new Reply(400, WorkspaceJson.error(result.failure()));
  }

  /**
   * The cache key the two detection services validate against. Coalesced to {@code ""} because the
   * monitor has no marker until its first report (a git read that failed at boot), and the services
   * compare it with {@code equals} — a null would NPE on the second call rather than simply missing
   * the cache.
   */
  private String marker() {
    String current = marker.get();
    return current == null ? "" : current;
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
  private static void respond(HttpServerRequest request, int status, JsonObject body) {
    try {
      request
          .response()
          .setStatusCode(status)
          .putHeader("Content-Type", "application/json")
          // The bodies embed repository-controlled text; nosniff keeps a client from ever deciding
          // this is anything other than the JSON it is labelled as.
          .putHeader("X-Content-Type-Options", "nosniff")
          .end(body.encode());
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
