package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentState;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * The daemon's one <em>inbound</em> listener: a loopback HTTP server the in-container coding
 * agent's lifecycle hooks POST to. A qits-launched {@code claude}/{@code kimi} carries a hook that
 * {@code curl}s {@code http://127.0.0.1:<port>/hooks/claude-code?commandId=<id>} with the hook's
 * stdin JSON ({@code hook_event_name}, {@code session_id}, {@code transcript_path}, {@code
 * source}); this maps the event to an {@link AgentState} and relays an unsolicited {@link
 * AgentActivity} home over the control socket — the agent-activity analogue of {@link
 * GitStatusMonitor}'s working-tree reports.
 *
 * <p>Bound to {@code 127.0.0.1} only (the hook and the daemon share the container's network
 * namespace, so loopback reaches it and nothing outside the container can). The response is a bare
 * {@code 200} returned as soon as the body is read — a hook must never add latency to a turn.
 *
 * <p>The last state per {@code commandId} is retained so {@link #reportCurrent()} can replay it on
 * a socket reconnect (a qits restart that lost its in-memory projection rebuilds it), mirroring
 * {@link GitStatusMonitor#reportCurrent()}. {@code Stop} maps to {@code IDLE} (turn finished) but
 * also fires when Claude pauses to ask the user — so a {@code Stop} arriving while the stored state
 * is {@code WAITING} (a preceding {@code Notification}) is dropped, keeping the permission-prompt
 * signal.
 */
final class HookWebhook {

  private static final Logger LOG = Logger.getLogger(HookWebhook.class);

  static final String PATH = "/hooks/claude-code";

  private final Vertx vertx;
  private final int port;
  private final Consumer<DaemonMessage> send;

  /** Last activity per qits command id; replayed by {@link #reportCurrent()}, evicted on end. */
  private final Map<String, AgentActivity> lastByCommand = new ConcurrentHashMap<>();

  private volatile HttpServer server;

  HookWebhook(Vertx vertx, int port, Consumer<DaemonMessage> send) {
    this.vertx = vertx;
    this.port = port;
    this.send = send;
  }

  void start() {
    server = vertx.createHttpServer();
    server
        .requestHandler(this::onRequest)
        .listen(port, "127.0.0.1")
        .onSuccess(s -> LOG.infof("workspace-daemon hook webhook listening on 127.0.0.1:%d", port))
        .onFailure(t -> LOG.errorf(t, "workspace-daemon hook webhook failed to bind :%d", port));
  }

  private void onRequest(HttpServerRequest request) {
    if (request.method() != HttpMethod.POST || !PATH.equals(request.path())) {
      request.response().setStatusCode(404).end();
      return;
    }
    String commandId = request.getParam("commandId");
    request.bodyHandler(
        body -> {
          try {
            JsonObject json = body.length() == 0 ? new JsonObject() : new JsonObject(body);
            handle(json.getString("hook_event_name"), json, commandId);
          } catch (RuntimeException e) {
            LOG.debugf("workspace-daemon dropped an undecodable hook payload: %s", e.getMessage());
          }
          request.response().setStatusCode(200).end();
        });
  }

  /**
   * Maps one hook payload to an {@link AgentActivity} and relays it. Package-private so a test can
   * drive the event→state mapping, the Notification override, and the reconnect replay without a
   * real HTTP fork (mirrors {@link GitStatusMonitor}'s {@code settle} seam). Uninteresting events
   * ({@code SubagentStop}, {@code PreToolUse}, …) and payloads with no command correlation are
   * dropped.
   */
  void handle(String hookEvent, JsonObject body, String commandId) {
    String state = mapState(hookEvent);
    if (state == null || commandId == null || commandId.isBlank()) {
      return;
    }
    // A turn-finished Stop must not downgrade a pending permission prompt (WAITING wins).
    AgentActivity current = lastByCommand.get(commandId);
    if ("Stop".equals(hookEvent) && current != null && AgentState.WAITING.equals(current.state())) {
      return;
    }
    AgentActivity activity =
        new AgentActivity(
            commandId,
            body.getString("session_id"),
            state,
            hookEvent,
            body.getString("source"),
            body.getString("transcript_path"),
            System.currentTimeMillis());
    if (AgentState.ENDED.equals(state)) {
      lastByCommand.remove(commandId);
    } else {
      lastByCommand.put(commandId, activity);
    }
    send.accept(activity);
  }

  /** Re-send the last known activity for every still-tracked command (reconnect adoption). */
  void reportCurrent() {
    for (AgentActivity activity : lastByCommand.values()) {
      send.accept(activity);
    }
  }

  void close() {
    HttpServer s = server;
    if (s != null) {
      s.close();
    }
  }

  private static String mapState(String hookEvent) {
    if (hookEvent == null) {
      return null;
    }
    return switch (hookEvent) {
      case "SessionStart" -> AgentState.IDLE;
      case "UserPromptSubmit" -> AgentState.BUSY;
      case "Stop" -> AgentState.IDLE;
      case "Notification" -> AgentState.WAITING;
      case "SessionEnd" -> AgentState.ENDED;
      default -> null; // SubagentStop / PreToolUse / … — not a main-agent state transition
    };
  }
}
