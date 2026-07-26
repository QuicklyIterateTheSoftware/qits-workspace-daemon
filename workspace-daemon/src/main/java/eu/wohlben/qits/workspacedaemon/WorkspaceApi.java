package eu.wohlben.qits.workspacedaemon;

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
 * about its reachability model transfers to an inbound listener, and qits-gateway's own posture
 * (its README's "Security posture") is explicit that it authenticates nothing itself and forwards
 * to components that authenticate their own requests. An unauthenticated port here would therefore
 * make every workspace's working tree — source, uncommitted work, whatever secrets a repo carries —
 * readable by every other workspace's agent. The path guards in {@code WorkspaceFileBrowser} bound
 * the damage to <em>this</em> checkout; they do nothing about <em>who</em> may read it.
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
    return bound.requestHandler(this::onRequest).listen(port, bindAddress);
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
    String header = request.getHeader("Authorization");
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
