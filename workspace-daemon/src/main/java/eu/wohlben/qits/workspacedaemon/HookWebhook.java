package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentState;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
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
 *
 * <p>Every hook that maps to a state also hands the state this <em>stores</em> — not the one the
 * event maps to — to the activity listener ({@code AgentLaunchService.onActivity}), which types a
 * queued interactive rename only on {@code IDLE}. Stored, because a dropped {@code Stop} during a
 * permission prompt forwarded as {@code IDLE} would type {@code /rename} into the dialog: that Stop
 * forwards {@code WAITING} again. {@code SessionEnd} forwards {@code ENDED}. A listener that throws
 * is logged and swallowed — the hook still gets its {@code 200}, and the relay home still happens.
 *
 * <p><b>{@code awaitingInput} (qits-895)</b> is a second, independent verdict every frame carries
 * alongside {@code state}: whether the agent is blocked on the user rather than merely between
 * turns. It is computed from the hook payload, never from {@code state}/{@code hookEvent} alone,
 * because {@link #mapState} already collapses several payload shapes onto one state and must not
 * change to answer this — {@code Notification} maps to {@code WAITING} whether or not it is a
 * permission prompt, and {@code Stop} maps to {@code IDLE} whether or not a background task is
 * still running. The table: a {@code Stop} whose {@code background_tasks} and {@code
 * session_crons} are both present and both empty is {@code true} (nothing left to run, so idle
 * really means waiting-for-the-user); either array non-empty is {@code false} (more output is
 * still coming, unprompted); either array absent or not a JSON array is {@code null} — an older
 * harness that does not report them, read as "unknown" rather than guessed. A {@code Notification}
 * is {@code true} only for a {@code permission_prompt} or {@code elicitation_dialog} {@code
 * notification_type}; any other type is {@code null}, not {@code false} — a notification this
 * daemon does not recognise might still be one the user has to answer. {@code UserPromptSubmit} is
 * always {@code false} (a turn was just handed to the agent). {@code SessionStart} is always
 * {@code null} (nothing has happened yet to have an opinion about). {@code SessionEnd}, and a
 * {@link #killed} frame, are always {@code true} — there is no next turn to wait out, so "blocked
 * on the user" and "session over" coincide.
 *
 * <p>The Stop-during-{@code WAITING} hold path below sends no wire frame at all — see its own
 * comment — so {@code awaitingInput} is never computed for it: the frame that already went out
 * was the preceding {@code Notification}'s, which carried {@code true}, and that is still the
 * latest thing the backend or a reconnect replay has seen.
 */
final class HookWebhook {

  private static final Logger LOG = Logger.getLogger(HookWebhook.class);

  static final String PATH = "/hooks/claude-code";

  private final Vertx vertx;
  private final int port;
  private final Consumer<DaemonMessage> send;
  private final BiConsumer<String, String> activity;

  /** Last activity per qits command id; replayed by {@link #reportCurrent()}, evicted on end. */
  private final Map<String, AgentActivity> lastByCommand = new ConcurrentHashMap<>();

  private volatile HttpServer server;

  HookWebhook(Vertx vertx, int port, Consumer<DaemonMessage> send) {
    this(vertx, port, send, null);
  }

  /**
   * @param activity told {@code (commandId, storedState)} after every stored state change, or null
   *     for none
   */
  HookWebhook(
      Vertx vertx, int port, Consumer<DaemonMessage> send, BiConsumer<String, String> activity) {
    this.vertx = vertx;
    this.port = port;
    this.send = send;
    this.activity = activity;
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
    // A turn-finished Stop must not downgrade a pending permission prompt (WAITING wins). No
    // frame goes out on this path — see the class javadoc's "hold path" paragraph — so
    // awaitingInput is never computed for it; the Notification frame already sent carried true.
    AgentActivity current = lastByCommand.get(commandId);
    if ("Stop".equals(hookEvent) && current != null && AgentState.WAITING.equals(current.state())) {
      forward(commandId, current.state());
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
            System.currentTimeMillis(),
            awaitingInput(hookEvent, body));
    if (AgentState.ENDED.equals(state)) {
      lastByCommand.remove(commandId);
    } else {
      lastByCommand.put(commandId, activity);
    }
    send.accept(activity);
    forward(commandId, state);
  }

  /**
   * Relays the end of an agent that was killed and so fired no hook ({@link AgentKillWatch}): an
   * {@code ENDED} frame carrying {@code hookEvent} (an {@code AgentEvent}), the exit code and the
   * sentence saying what killed it. It goes through here rather than straight to the socket because
   * this class owns the per-command replay: the killed command's last state is evicted exactly as a
   * {@code SessionEnd} evicts it, so a reconnect cannot replay a dead agent's {@code BUSY} or {@code
   * IDLE} over the truth. The session identity is carried from the last frame, when there was one.
   */
  void killed(String commandId, String hookEvent, int exitCode, String message) {
    if (commandId == null || commandId.isBlank()) {
      return;
    }
    AgentActivity last = lastByCommand.remove(commandId);
    AgentActivity activity =
        new AgentActivity(
            commandId,
            last == null ? null : last.sessionId(),
            AgentState.ENDED,
            hookEvent,
            null,
            last == null ? null : last.transcriptPath(),
            System.currentTimeMillis(),
            exitCode,
            message,
            // no next turn to wait out — see the class javadoc's awaitingInput table
            Boolean.TRUE);
    send.accept(activity);
    forward(commandId, AgentState.ENDED);
  }

  /** Tells the activity listener the stored state, never letting it fail the hook. */
  private void forward(String commandId, String state) {
    if (activity == null) {
      return;
    }
    try {
      activity.accept(commandId, state);
    } catch (RuntimeException e) {
      LOG.warnf(e, "workspace-daemon activity listener failed for command %s", commandId);
    }
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

  /**
   * The {@code awaitingInput} verdict (qits-895) for a hook-driven frame — see the class
   * javadoc's table for the full reasoning behind each arm. Deliberately independent of {@link
   * #mapState}: that method's job is the coarse {@code IDLE}/{@code BUSY}/{@code WAITING}/{@code
   * ENDED} state and must not change, while this one reads the payload fields that state
   * collapses away.
   */
  private static Boolean awaitingInput(String hookEvent, JsonObject body) {
    return switch (hookEvent) {
      case "Stop" -> awaitingInputForStop(body);
      case "Notification" -> awaitingInputForNotification(body);
      case "UserPromptSubmit" -> Boolean.FALSE;
      case "SessionEnd" -> Boolean.TRUE;
      case "SessionStart" -> null; // nothing has happened yet to have an opinion about
      default -> null; // not reached: mapState already dropped every other event
    };
  }

  /**
   * {@code true} when a {@code Stop}'s {@code background_tasks} and {@code session_crons} are
   * both present <em>and</em> both empty — nothing left running, so the turn ending really does
   * mean the agent is now waiting on the user. {@code false} when either array has an element:
   * more output is still coming on its own, unprompted. {@code null} when either key is absent or
   * is not a JSON array — an older harness that does not report them — read as "unknown" rather
   * than guessed.
   */
  private static Boolean awaitingInputForStop(JsonObject body) {
    Object backgroundTasks = body.getValue("background_tasks");
    Object sessionCrons = body.getValue("session_crons");
    if (!(backgroundTasks instanceof JsonArray tasks)
        || !(sessionCrons instanceof JsonArray crons)) {
      return null;
    }
    return tasks.isEmpty() && crons.isEmpty();
  }

  /**
   * {@code true} only for the two {@code notification_type}s that are genuinely a blocked prompt;
   * any other type — including one this daemon has never seen — is {@code null}, not {@code
   * false}: an unrecognised notification might still be one the user has to answer, and guessing
   * "no" would be worse than saying nothing.
   */
  private static Boolean awaitingInputForNotification(JsonObject body) {
    String type = body.getString("notification_type");
    return "permission_prompt".equals(type) || "elicitation_dialog".equals(type)
        ? Boolean.TRUE
        : null;
  }
}
