package eu.wohlben.qits.workspacedaemon;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
 * <h2>Why this one is not loopback-bound</h2>
 *
 * <p>{@link HookWebhook} binds {@code 127.0.0.1} because its only client — the coding agent's
 * lifecycle hook — shares the container's network namespace. This server's client is qits-gateway,
 * on the shared {@code qits-net} docker network, so it must bind an address reachable from outside
 * the container (default {@code 0.0.0.0}) on a port of its own. That is the whole difference
 * between the two listeners, and it is the reason the security posture below exists at all.
 *
 * <h2>Security</h2>
 *
 * <p>Three facts set the threat model. (1) This port serves the contents of an <em>untrusted</em>
 * cloned repository. (2) It is reachable by DNS name from every other container on {@code qits-net}
 * — including <em>other workspaces</em>, each running a coding agent over someone else's untrusted
 * code with unrestricted outbound network. (3) The daemon's other channels do not answer this: the
 * control socket is <em>outbound</em> (the daemon dials qits and qits never dials back), so nothing
 * about its reachability model transfers to an inbound listener. An unauthenticated port here would
 * therefore make every workspace's working tree — source, uncommitted work, whatever secrets a repo
 * carries — readable by every other workspace's agent. The path guards in {@code
 * WorkspaceFileBrowser} bound the damage to <em>this</em> checkout; they do nothing about
 * <em>who</em> may read it.
 *
 * <p><b>The token's justification is the network, not the gateway.</b> This used to cite
 * qits-gateway as authenticating nothing itself; it now authenticates every human request
 * (migration-auth-plan.md). That changes nothing here, and the reason is worth keeping: the gateway
 * is a perimeter against the internet, not a boundary on {@code qits-net}. The callers this token
 * defends against are already <em>inside</em> — peer workspaces that never traverse the front door
 * at all. This is peer authentication, not user authentication, and no amount of edge auth
 * substitutes for it.
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

  private static final String BEARER = "Bearer ";

  @Inject Vertx vertx;

  /**
   * Owns the {@link OriginSync} these two routes need — it is created only once the checkout is
   * provisioned, so it is read per request rather than captured.
   */
  @Inject ControlSocket controlSocket;

  // The port qits-gateway reaches this daemon's read API on. Distinct from hooks-port on purpose:
  // that one is a loopback-only agent-hook sink, this one is network-reachable, and collapsing them
  // onto one listener would put the hook endpoint on the docker network too.
  @ConfigProperty(name = "qits.workspace-daemon.api-port", defaultValue = "13338")
  int apiPort;

  // Must be an address reachable from off-container (the container's qits-net interface), unlike
  // the hook webhook's 127.0.0.1. Configurable so a deployment that puts the daemon on a narrower
  // interface than "every one of them" can.
  @ConfigProperty(name = "qits.workspace-daemon.api-bind-address", defaultValue = "0.0.0.0")
  String apiBindAddress;

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
                    handshake, registry != null && authorized(handshake.headers())))
        .webSocketHandler(socket -> CommandSockets.attach(socket, registry))
        .listen(port, bindAddress);
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
    String path = request.path();
    if (path.equals(COMMANDS_PATH) || path.startsWith(COMMANDS_PATH + "/")) {
      onCommandRequest(request, path);
      return;
    }
    if (isAgentPath(path)) {
      onAgentRequest(request, path);
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
