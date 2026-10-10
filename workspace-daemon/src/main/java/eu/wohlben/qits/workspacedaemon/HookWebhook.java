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
import java.time.Duration;
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
 * AgentActivity} home over the control socket. {@code ControlSocket} names the agent on each frame
 * on its way out ({@code AgentRuntime.tag}), since a workspace hosts several.
 *
 * <p>Bound to {@code 127.0.0.1} only (the hook and the daemon share the container's network
 * namespace, so loopback reaches it and nothing outside the container can). The response is a bare
 * {@code 200} returned as soon as the body is read — a hook must never add latency to a turn.
 *
 * <p>The last state per {@code commandId} is retained so {@link #reportCurrent()} can replay it on
 * a socket reconnect (a qits restart that lost its in-memory projection rebuilds it). {@code Stop} maps to {@code IDLE} (turn finished) but
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
 * session_crons} are both present, both JSON arrays and both empty is {@code true}. One whose
 * {@code session_crons} is empty and whose {@code background_tasks} holds only plain {@code
 * "type": "shell"} objects is {@code false} <em>now</em> and {@code true} after a grace period
 * ({@code qits.workspace-daemon.agent-waiting.background-shell-grace}, 25 minutes by default) in
 * which no further hook arrives for that command — by the owner's decision. Read as waiting at
 * once, a {@code Stop} that left a build or a test run going in the background would show the
 * agent blocked on the user while it is about to be re-invoked by the shell's completion; never
 * read as waiting, a long-lived shell (a dev server, a poller, {@code tail -f}) would hide a
 * genuinely idle agent for as long as the shell lives. The grace is the line between the two:
 * long enough for an ordinary build to finish and wake the agent, short enough that a forgotten
 * dev server does not mask the wait for good. When it expires, a synthesized copy of the stored
 * frame goes out with {@code awaitingInput} {@code true} and a fresh {@code at}, and replaces the
 * stored one so a reconnect replays the verdict; the activity listener is not told again, because
 * the state did not change. Any hook for the command — or a {@link #killed} frame — cancels the
 * pending grace before doing its own work. A subagent, a monitor, an unknown or missing type, a
 * non-object element, or a non-empty {@code session_crons} all count as in flight and give {@code
 * false} with no grace. Either array absent or not a JSON array is {@code null} — an older
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

  /** The default for {@code qits.workspace-daemon.agent-waiting.background-shell-grace}. */
  static final Duration DEFAULT_BACKGROUND_SHELL_GRACE = Duration.ofMinutes(25);

  private final Vertx vertx;
  private final int port;
  private final Consumer<DaemonMessage> send;
  private final BiConsumer<String, String> activity;
  private final Duration backgroundShellGrace;

  /** Last activity per qits command id; replayed by {@link #reportCurrent()}, evicted on end. */
  private final Map<String, AgentActivity> lastByCommand = new ConcurrentHashMap<>();

  /**
   * The pending background-shell grace timer per qits command id. A map rather than a field on the
   * stored frame because the callback has to prove it is still the <em>current</em> timer for its
   * command — a newer hook may have cancelled it and armed another between scheduling and firing.
   */
  private final Map<String, Long> graceTimers = new ConcurrentHashMap<>();

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
    this(vertx, port, send, activity, DEFAULT_BACKGROUND_SHELL_GRACE);
  }

  /**
   * @param backgroundShellGrace how long a {@code Stop} whose only in-flight work is background
   *     shells waits, with no further hook for its command, before it reads as awaiting input — a
   *     seam so a test can wait milliseconds rather than the production default
   */
  HookWebhook(
      Vertx vertx,
      int port,
      Consumer<DaemonMessage> send,
      BiConsumer<String, String> activity,
      Duration backgroundShellGrace) {
    this.vertx = vertx;
    this.port = port;
    this.send = send;
    this.activity = activity;
    this.backgroundShellGrace = backgroundShellGrace;
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
   * real HTTP fork. Uninteresting events
   * ({@code SubagentStop}, {@code PreToolUse}, …) and payloads with no command correlation are
   * dropped. Every event that is not dropped first cancels the command's pending background-shell
   * grace: it is newer news than the {@code Stop} that armed it.
   */
  void handle(String hookEvent, JsonObject body, String commandId) {
    String state = mapState(hookEvent);
    if (state == null || commandId == null || commandId.isBlank()) {
      return;
    }
    cancelGrace(commandId);
    // A turn-finished Stop must not downgrade a pending permission prompt (WAITING wins). No
    // frame goes out on this path — see the class javadoc's "hold path" paragraph — so
    // awaitingInput is never computed for it; the Notification frame already sent carried true.
    AgentActivity current = lastByCommand.get(commandId);
    if ("Stop".equals(hookEvent) && current != null && AgentState.WAITING.equals(current.state())) {
      forward(commandId, current.state());
      return;
    }
    boolean onlyBackgroundShells = "Stop".equals(hookEvent) && onlyBackgroundShells(body);
    AgentActivity activity =
        new AgentActivity(
            commandId,
            body.getString("session_id"),
            state,
            hookEvent,
            body.getString("source"),
            body.getString("transcript_path"),
            System.currentTimeMillis(),
            onlyBackgroundShells ? Boolean.FALSE : awaitingInput(hookEvent, body));
    if (AgentState.ENDED.equals(state)) {
      lastByCommand.remove(commandId);
    } else {
      lastByCommand.put(commandId, activity);
    }
    send.accept(activity);
    forward(commandId, state);
    if (onlyBackgroundShells) {
      armGrace(commandId);
    }
  }

  /**
   * Schedules the background-shell grace for {@code commandId}. The callback re-checks that its
   * timer is still the command's current one ({@code remove(key, value)} is the atomic form of that
   * check), so a timer a newer hook cancelled too late to stop it firing sends nothing. The id is
   * recorded after {@code setTimer} returns, which is safe because hooks arrive on the event loop
   * the timer fires on, so the callback cannot run in between.
   */
  private void armGrace(String commandId) {
    long timer =
        vertx.setTimer(
            Math.max(1, backgroundShellGrace.toMillis()),
            id -> {
              if (graceTimers.remove(commandId, id)) {
                graceExpired(commandId);
              }
            });
    graceTimers.put(commandId, timer);
  }

  /**
   * Relays the stored frame again with {@code awaitingInput} {@code true} and a fresh {@code at},
   * and stores the copy so {@link #reportCurrent()} replays the verdict. The activity listener is
   * deliberately not told: the state is the stored {@code IDLE} it already heard, and a second
   * {@code IDLE} would re-trigger whatever it does on one.
   */
  private void graceExpired(String commandId) {
    AgentActivity last = lastByCommand.get(commandId);
    if (last == null) {
      return; // evicted since the Stop — nothing left to call waiting
    }
    AgentActivity waiting =
        new AgentActivity(
            last.commandId(),
            last.sessionId(),
            last.state(),
            "Stop",
            last.source(),
            last.transcriptPath(),
            System.currentTimeMillis(),
            Boolean.TRUE);
    lastByCommand.put(commandId, waiting);
    send.accept(waiting);
  }

  /** Cancels {@code commandId}'s pending background-shell grace, if it has one. */
  private void cancelGrace(String commandId) {
    Long timer = graceTimers.remove(commandId);
    if (timer != null) {
      vertx.cancelTimer(timer);
    }
  }

  /**
   * Relays the end of an agent that was killed and so fired no hook ({@link AgentKillWatch}): an
   * {@code ENDED} frame carrying {@code hookEvent} (an {@code AgentEvent}), the exit code and the
   * sentence saying what killed it. It goes through here rather than straight to the socket because
   * this class owns the per-command replay: the killed command's last state is evicted exactly as a
   * {@code SessionEnd} evicts it, so a reconnect cannot replay a dead agent's {@code BUSY} or {@code
   * IDLE} over the truth. The session identity is carried from the last frame, when there was one.
   * A pending background-shell grace is cancelled first, as any hook cancels it: a dead agent is
   * not one to call waiting twenty-five minutes later.
   */
  void killed(String commandId, String hookEvent, int exitCode, String message) {
    if (commandId == null || commandId.isBlank()) {
      return;
    }
    cancelGrace(commandId);
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

  /** Stops the listener and cancels every pending background-shell grace. */
  void close() {
    for (String commandId : graceTimers.keySet()) {
      cancelGrace(commandId);
    }
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
   * both present and both JSON arrays, {@code session_crons} is empty, and every {@code
   * background_tasks} element is a JSON object with {@code "type": "shell"}. That includes the
   * only-shells case, which {@link #handle} overrides to {@code false} and arms the grace for —
   * see {@link #onlyBackgroundShells} — so this stays the verdict the payload alone supports, and
   * the timing lives in one place. {@code false} when {@code session_crons} holds anything, or
   * when any {@code background_tasks} element is not such a shell object — a subagent, a monitor,
   * an unknown or missing type, or a non-object element all still mean more output is coming on
   * its own, unprompted. {@code null} when either key is absent or is not a JSON array — an older
   * harness that does not report them — read as "unknown" rather than guessed.
   */
  private static Boolean awaitingInputForStop(JsonObject body) {
    Object backgroundTasks = body.getValue("background_tasks");
    Object sessionCrons = body.getValue("session_crons");
    if (!(backgroundTasks instanceof JsonArray tasks)
        || !(sessionCrons instanceof JsonArray crons)) {
      return null;
    }
    if (!crons.isEmpty()) {
      return Boolean.FALSE;
    }
    for (Object task : tasks) {
      if (!(task instanceof JsonObject taskObject)
          || !"shell".equals(taskObject.getString("type"))) {
        return Boolean.FALSE;
      }
    }
    return Boolean.TRUE;
  }

  /**
   * Whether a {@code Stop}'s only in-flight work is background shells: the payload alone says
   * waiting, but a shell left running is as likely a build about to wake the agent as a dev server
   * that never will, so the verdict waits out the grace (see the class javadoc). Empty {@code
   * background_tasks} is not this case — nothing is running, so the agent is waiting at once.
   */
  private static boolean onlyBackgroundShells(JsonObject body) {
    return Boolean.TRUE.equals(awaitingInputForStop(body))
        && !body.getJsonArray("background_tasks").isEmpty();
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
