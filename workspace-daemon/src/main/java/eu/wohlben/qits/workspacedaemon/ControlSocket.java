package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Describe;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The persistent dial-home socket: {@code workspace-daemon} connects to the qits backend's {@code
 * /api/workspace-daemon/{workspaceId}} WebSocket, sends a {@link Hello}, and thereafter serves
 * backend requests ({@link RunCommand}, {@link Describe}) from in-container, streaming results
 * back.
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
   * Full dial-home URL the factory injected, e.g. {@code ws://qits:8080/api/workspace-daemon/<id>}.
   */
  @ConfigProperty(name = "qits.workspace-daemon.url")
  Optional<String> url;

  // Identity is Optional<String>, not @ConfigProperty(defaultValue = ""): SmallRye treats an empty
  // default as "no value" and fails to resolve a plain String when the env is absent (the same
  // reason WorkspaceContainerFactory.timezone is Optional). Resolved to "" below.
  @ConfigProperty(name = "qits.workspace-daemon.workspace-id")
  Optional<String> workspaceIdConfig;

  @ConfigProperty(name = "qits.workspace-daemon.repository-id")
  Optional<String> repositoryIdConfig;

  @ConfigProperty(name = "qits.workspace-daemon.branch")
  Optional<String> branchConfig;

  @ConfigProperty(name = "qits.workspace-daemon.parent")
  Optional<String> parentConfig;

  private String workspaceId = "";
  private String repositoryId = "";
  private String branch = "";
  private String parent = "";

  @ConfigProperty(name = "qits.workspace-daemon.heartbeat-interval-ms", defaultValue = "20000")
  long heartbeatIntervalMs;

  @ConfigProperty(name = "qits.workspace-daemon.reconnect-max-backoff-ms", defaultValue = "30000")
  long maxBackoffMs;

  /** Off-event-loop pool for blocking process/git work; one thread per in-flight request. */
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-worker");
            thread.setDaemon(true);
            return thread;
          });

  private volatile WebSocketClient client;
  private volatile WebSocket socket;
  private volatile Context socketContext;

  /**
   * Begin dialing home. If no URL is configured (older provisioning, or the env wasn't injected),
   * log and stay idle — the container must not die for want of a socket.
   */
  public void start() {
    workspaceId = workspaceIdConfig.orElse("");
    repositoryId = repositoryIdConfig.orElse("");
    branch = branchConfig.orElse("");
    parent = parentConfig.orElse("");
    if (url.isEmpty() || url.get().isBlank()) {
      LOG.warn(
          "No qits.workspace-daemon.url configured — workspace-daemon is idle (container stays alive, docker exec"
              + " paths unaffected).");
      return;
    }
    client = vertx.createWebSocketClient();
    if (heartbeatIntervalMs > 0) {
      vertx.setPeriodic(heartbeatIntervalMs, id -> heartbeat());
    }
    connect(0);
  }

  private void connect(int attempt) {
    URI uri;
    try {
      uri = URI.create(url.get());
    } catch (RuntimeException e) {
      LOG.errorf(e, "Malformed qits.workspace-daemon.url '%s' — workspace-daemon idle.", url.get());
      return; // an unparseable URL won't become parseable on retry; stay alive, stay idle
    }
    int port = uri.getPort() != -1 ? uri.getPort() : 80;
    WebSocketConnectOptions options =
        new WebSocketConnectOptions().setHost(uri.getHost()).setPort(port).setURI(uri.getRawPath());
    client
        .connect(options)
        .onSuccess(this::onConnected)
        .onFailure(
            t -> {
              LOG.debugf(
                  "workspace-daemon dial-home failed (attempt %d): %s", attempt, t.getMessage());
              reconnect(attempt);
            });
  }

  private void onConnected(WebSocket ws) {
    socket = ws;
    socketContext = vertx.getOrCreateContext();
    ws.textMessageHandler(this::onFrame);
    ws.closeHandler(
        v -> {
          LOG.debug("workspace-daemon control socket closed — reconnecting.");
          socket = null;
          reconnect(0);
        });
    ws.exceptionHandler(
        t -> LOG.debugf("workspace-daemon control socket error: %s", t.getMessage()));
    send(
        new Hello(workspaceId, repositoryId, branch, parent, DaemonProtocol.CAPABILITY_VERSION),
        ws);
    // Prove the thin-client log direction: workspace-daemon's own events reach qits over the
    // socket, so a
    // crashing/misbehaving client is visible without `docker logs`. Later parts reuse this to relay
    // daemon/command output.
    send(new DaemonLog("INFO", "workspace-daemon online for workspace " + workspaceId), ws);
    LOG.infof("workspace-daemon control socket established for workspace %s", workspaceId);
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
              () -> send(WorkspaceDescriber.describe(workspaceId, repositoryId, branch, parent)));
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

  /** Emit a message on the current socket, marshalling the write onto its event loop. */
  private void send(DaemonMessage message) {
    WebSocket ws = socket;
    if (ws != null) {
      send(message, ws);
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
}
