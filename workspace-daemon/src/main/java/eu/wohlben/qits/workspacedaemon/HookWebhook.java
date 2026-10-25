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
import java.util.Set;
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
 * a socket reconnect (a qits restart that lost its in-memory projection rebuilds it). {@code Stop}
 * maps to {@code IDLE} (turn finished) but also fires when Claude pauses to ask the user — so a
 * {@code Stop} arriving while the stored frame is a {@code WAITING} one that says {@code
 * awaitingInput} {@code true} (a preceding permission or elicitation {@code Notification}) is
 * held, keeping the permission-prompt signal. Any other stored {@code WAITING} does not hold it
 * (qits-1511).
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
 * still running. The table: a {@code Stop} whose {@code background_tasks} and {@code session_crons}
 * are both present, both JSON arrays and both empty is {@code true}. One that reports background
 * work still in flight is {@code false} <em>now</em> and {@code true} after a grace period in which
 * no further hook arrives for that command — by the owner's decision. Which grace depends on what
 * is in flight: when {@code session_crons} is empty and {@code background_tasks} holds only plain
 * {@code "type": "shell"} objects, the background-shell grace ({@code
 * qits.workspace-daemon.agent-waiting.background-shell-grace}, 25 minutes by default); when it
 * holds anything else — a subagent, a monitor, an unknown or missing type, a non-object element, or
 * a non-empty {@code session_crons}, alone or beside shells — the background-work grace ({@code
 * qits.workspace-daemon.agent-waiting.background-work-grace}, 60 minutes by default, qits-1460).
 * Read as waiting at once, a {@code Stop} that left a build or a subagent going in the background
 * would show the agent blocked on the user while it is about to be re-invoked by that work's
 * completion; never read as waiting, a long-lived shell (a dev server, a poller, {@code tail -f}),
 * a recurring session cron ({@code /loop}) or an endless monitor would hide a genuinely idle agent
 * for as long as it lives — a cron or a monitor never produces the next hook that would change the
 * verdict, so until qits-1460 such an agent never read as waiting at all. The grace is the line
 * between the two: long enough for an ordinary build or subagent to finish and wake the agent,
 * short enough that a forgotten dev server or cron does not mask the wait for good. The
 * kind-to-grace mapping lives in one place, {@link #stopVerdict}. When the grace expires, a
 * synthesized copy of the stored frame goes out with {@code awaitingInput} {@code true} and a fresh
 * {@code at}, and replaces the stored one so a reconnect replays the verdict; the activity listener
 * is not told again, because the state did not change. The grace answers whether the agent reads as
 * waiting for input, not whether work is in flight: the shell grace's expiry also clears the
 * background-work verdict below (qits-1238 — a shell left running that long is read as a forgotten
 * dev server), but the background-work grace's does not (qits-1460), because a subagent, monitor or
 * cron the harness reported is still truthfully running. Any hook for the command except a {@code
 * Notification} — or a {@link #killed} frame — cancels the pending grace before doing its own work.
 * A {@code Notification} does not, because it says nothing about the background work: Claude Code
 * sends an {@code idle_prompt} one about a minute after every {@code Stop}, so letting it cancel
 * would mean a {@code Stop}'s grace never fires at all (qits-1238). Either array absent or not a
 * JSON array is {@code null} — an older harness that does not report them, read as "unknown" rather
 * than guessed. A {@code Notification} is {@code true} only for a {@code permission_prompt} or
 * {@code elicitation_dialog} {@code notification_type}; any other type is {@code null}, not {@code
 * false} — a notification this daemon does not recognise might still be one the user has to answer.
 * {@code UserPromptSubmit} is always {@code false} (a turn was just handed to the agent). {@code
 * SessionStart} is always {@code null} (nothing has happened yet to have an opinion about). {@code
 * SessionEnd}, and a {@link #killed} frame, are always {@code true} — there is no next turn to wait
 * out, so "blocked on the user" and "session over" coincide.
 *
 * <p>The hold path below takes a {@code Stop} only when the stored frame is {@code WAITING}
 * <em>and</em> carries {@code awaitingInput} {@code true} — a real {@code permission_prompt} or
 * {@code elicitation_dialog} still pending. It sends no wire frame at all, so {@code
 * awaitingInput} is never computed for it: the frame that already went out was that {@code
 * Notification}'s, which carried {@code true}, and that is still the latest thing the backend or a
 * reconnect replay has seen. The state alone is not enough to hold on, because every {@code
 * Notification} maps to {@code WAITING} while only those two types carry {@code true}; the rest —
 * above all the {@code idle_prompt} Claude Code sends about a minute after every turn — carry
 * {@code null}, which qits-workspaces-service ignores. Holding under one of those swallowed the
 * {@code Stop} that ends a turn the harness resumed by itself (a background shell finished, no
 * {@code UserPromptSubmit} in between): nothing went out, the stored {@code null} stood, and the
 * agent never read as waiting, so it was never nudged or yielded (qits-1511). Such a {@code Stop}
 * now takes the ordinary path — its own frame and verdict go out, are stored and forwarded, and it
 * arms its own grace. A held {@code Stop}'s {@code background_tasks} are still read into the
 * background-work verdict below, and one that left work in flight still arms its grace —
 * otherwise a shell-only verdict it records would have nothing to clear it until the next turn.
 * Should that grace expire under a standing permission prompt, the stored frame already says
 * {@code true} and nothing goes out; a shell grace still clears the verdict, a background-work
 * grace does nothing at all.
 *
 * <p><b>The background-work verdict (qits-1238)</b> is kept per command beside the stored frame,
 * not in it, because the stored frame is replaced by every hook that is not dropped — and the
 * {@code idle_prompt} {@code Notification} Claude Code sends about a minute after a {@code Stop}
 * carries {@code awaitingInput} {@code null}, which would otherwise erase the {@code Stop}'s word
 * that a subagent is still running. A {@code Stop} sets it from its own body, on the hold path too:
 * present when it reports any background work in flight, absent when nothing is running or the
 * harness does not report the arrays. It is cleared by {@code UserPromptSubmit} (a new turn; its
 * own {@code BUSY} and the {@code Stop} that ends it take over), a later {@code Stop} with nothing
 * running, {@code SessionEnd}, a {@link #killed} frame and the expiry of a shell-only {@code
 * Stop}'s grace — never by the expiry of the background-work grace (qits-1460), whose work is still
 * running, and never by a {@code Notification} or a {@code SessionStart}, neither of which knows
 * anything about the background work. {@link #inFlight} reads it.
 *
 * <p><b>Every frame carries that verdict as {@code backgroundWork} (qits-1601)</b>, read after the
 * hook has set or cleared it: {@code true} while it is recorded for the command, {@code false}
 * otherwise. Kept to itself, it left the host reading a {@code Stop} that left a subagent running
 * as {@code IDLE} and the {@code idle_prompt} {@code Notification} after it as {@code WAITING} —
 * an agent busy with its own subagent shown as one waiting on the user. So the {@code Stop}
 * reporting in-flight work says {@code true}, and so does that {@code Notification}, which does
 * not clear the verdict; {@code UserPromptSubmit}, a {@code Stop} with nothing running or without
 * the arrays, {@code SessionEnd} and a {@link #killed} frame say {@code false}. A grace's
 * synthesized frame carries the verdict at expiry: {@code false} after the shell grace, which
 * clears it first, {@code true} after the background-work grace, which does not. {@link
 * #reportCurrent()} replays each stored frame as it was sent, verdict included. Nothing else
 * reads the wire field here — {@code state}, {@code awaitingInput}, the hold path, the graces and
 * the activity listener are exactly what they were without it.
 */
final class HookWebhook {

  private static final Logger LOG = Logger.getLogger(HookWebhook.class);

  static final String PATH = "/hooks/claude-code";

  /** The default for {@code qits.workspace-daemon.agent-waiting.background-shell-grace}. */
  static final Duration DEFAULT_BACKGROUND_SHELL_GRACE = Duration.ofMinutes(25);

  /** The default for {@code qits.workspace-daemon.agent-waiting.background-work-grace}. */
  static final Duration DEFAULT_BACKGROUND_WORK_GRACE = Duration.ofMinutes(60);

  private final Vertx vertx;
  private final int port;
  private final Consumer<DaemonMessage> send;
  private final BiConsumer<String, String> activity;
  private final Duration backgroundShellGrace;
  private final Duration backgroundWorkGrace;

  /** Last activity per qits command id; replayed by {@link #reportCurrent()}, evicted on end. */
  private final Map<String, AgentActivity> lastByCommand = new ConcurrentHashMap<>();

  /**
   * The pending grace timer (shell or background-work) per qits command id. A map rather than a
   * field on the
   * stored frame because the callback has to prove it is still the <em>current</em> timer for its
   * command — a newer hook may have cancelled it and armed another between scheduling and firing.
   */
  private final Map<String, Long> graceTimers = new ConcurrentHashMap<>();

  /**
   * The command ids whose latest {@code Stop} left background work running — the background-work
   * verdict (qits-1238), kept apart from {@link #lastByCommand} so a later {@code Notification}
   * cannot overwrite it; see the class javadoc for where it is set and cleared.
   */
  private final Set<String> backgroundByCommand = ConcurrentHashMap.newKeySet();

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
    this(
        vertx, port, send, activity, DEFAULT_BACKGROUND_SHELL_GRACE, DEFAULT_BACKGROUND_WORK_GRACE);
  }

  /**
   * @param backgroundShellGrace how long a {@code Stop} whose only in-flight work is background
   *     shells waits, with no further hook for its command, before it reads as awaiting input — a
   *     seam so a test can wait milliseconds rather than the production default
   * @param backgroundWorkGrace the same for a {@code Stop} that left any other background work in
   *     flight: a subagent, a monitor, an unknown type, or a session cron (qits-1460)
   */
  HookWebhook(
      Vertx vertx,
      int port,
      Consumer<DaemonMessage> send,
      BiConsumer<String, String> activity,
      Duration backgroundShellGrace,
      Duration backgroundWorkGrace) {
    this.vertx = vertx;
    this.port = port;
    this.send = send;
    this.activity = activity;
    this.backgroundShellGrace = backgroundShellGrace;
    this.backgroundWorkGrace = backgroundWorkGrace;
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
   * dropped. Every event that is not dropped, except a {@code Notification}, first cancels the
   * command's pending grace: it is newer news than the {@code Stop} that armed it. A {@code
   * Notification} is not news about the background work (see the class javadoc).
   */
  void handle(String hookEvent, JsonObject body, String commandId) {
    String state = mapState(hookEvent);
    if (state == null || commandId == null || commandId.isBlank()) {
      return;
    }
    if (!"Notification".equals(hookEvent)) {
      cancelGrace(commandId);
    }
    boolean stop = "Stop".equals(hookEvent);
    StopVerdict verdict = stop ? stopVerdict(body) : null;
    Duration grace = verdict == null ? null : verdict.grace();
    boolean clearOnExpiry = verdict != null && verdict.clearsInFlightOnExpiry();
    if (stop) {
      recordBackground(commandId, grace != null);
    } else if ("UserPromptSubmit".equals(hookEvent) || AgentState.ENDED.equals(state)) {
      backgroundByCommand.remove(commandId);
    }
    // A turn-finished Stop must not downgrade a pending permission or elicitation prompt: the
    // stored frame is WAITING *and* says awaitingInput true. WAITING alone is not enough — an
    // idle_prompt (or any other) Notification is WAITING with awaitingInput null, and holding
    // under it swallowed the Stop of a turn the harness resumed by itself, so the agent never
    // read as waiting (qits-1511); that Stop takes the ordinary path below. No frame goes out on
    // this path — see the class javadoc's "hold path" paragraph — so awaitingInput is never
    // computed for it; the Notification frame already sent carried true. Its background work was
    // recorded above, and still gets its grace, so the verdict it left clears in time rather than
    // at the next turn.
    AgentActivity current = lastByCommand.get(commandId);
    if (stop
        && current != null
        && AgentState.WAITING.equals(current.state())
        && Boolean.TRUE.equals(current.awaitingInput())) {
      forward(commandId, current.state());
      if (grace != null) {
        armGrace(commandId, grace, clearOnExpiry);
      }
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
            null,
            null,
            stop ? verdict.awaitingNow() : awaitingInput(hookEvent, body),
            null,
            backgroundWork(commandId));
    if (AgentState.ENDED.equals(state)) {
      lastByCommand.remove(commandId);
    } else {
      lastByCommand.put(commandId, activity);
    }
    send.accept(activity);
    forward(commandId, state);
    if (grace != null) {
      armGrace(commandId, grace, clearOnExpiry);
    }
  }

  /**
   * The background-work verdict as the wire carries it (qits-1601): {@code true} while one is
   * recorded for {@code commandId}, {@code false} otherwise. Read after the current hook has set or
   * cleared it.
   */
  private Boolean backgroundWork(String commandId) {
    return backgroundByCommand.contains(commandId);
  }

  /**
   * Sets or clears {@code commandId}'s background-work verdict from a {@code Stop}'s own body:
   * set when it reports work still in flight (the case that arms a grace), cleared otherwise —
   * nothing running, or a harness that does not report it.
   */
  private void recordBackground(String commandId, boolean inFlight) {
    if (inFlight) {
      backgroundByCommand.add(commandId);
    } else {
      backgroundByCommand.remove(commandId);
    }
  }

  /**
   * Schedules {@code grace} for {@code commandId} — the shell or the background-work one, as {@link
   * #stopVerdict} chose, and whether its expiry also clears the background-work verdict. The
   * callback re-checks that its timer is still the command's current one ({@code remove(key,
   * value)} is the atomic form of that check), so a timer a newer hook cancelled too late to stop
   * it firing sends nothing. The id is recorded after {@code setTimer} returns, which is safe
   * because hooks arrive on the event loop the timer fires on, so the callback cannot run in
   * between.
   */
  private void armGrace(String commandId, Duration grace, boolean clearOnExpiry) {
    long timer =
        vertx.setTimer(
            Math.max(1, grace.toMillis()),
            id -> {
              if (graceTimers.remove(commandId, id)) {
                graceExpired(commandId, clearOnExpiry);
              }
            });
    graceTimers.put(commandId, timer);
  }

  /**
   * Clears the command's background-work verdict when {@code clearOnExpiry} (the shell grace;
   * not the background-work grace, whose work is still running — see the class javadoc), then
   * relays the stored frame again with {@code
   * awaitingInput} {@code true} and a fresh {@code at}, and stores the copy so {@link
   * #reportCurrent()} replays the verdict. The stored frame is the arming {@code Stop}'s, or
   * a {@code Notification} that arrived after it (which did not cancel the grace); either way the
   * copy keeps its state and goes out as {@code hookEvent} {@code Stop}, since it is that {@code
   * Stop}'s delayed verdict. A stored frame already saying {@code true} — a permission prompt the
   * {@code Stop} was held under — is left alone and nothing is sent. The activity listener is
   * deliberately not told: the state is the stored one it already heard, and a second {@code IDLE}
   * would re-trigger whatever it does on one.
   */
  private void graceExpired(String commandId, boolean clearOnExpiry) {
    if (clearOnExpiry) {
      backgroundByCommand.remove(commandId);
    }
    AgentActivity last = lastByCommand.get(commandId);
    if (last == null) {
      return; // evicted since the Stop — nothing left to call waiting
    }
    if (Boolean.TRUE.equals(last.awaitingInput())) {
      return; // already reads as waiting — a held Stop under a permission prompt
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
            null,
            null,
            Boolean.TRUE,
            null,
            // the verdict at expiry: cleared above by the shell grace, kept by the background-work
            // grace (qits-1601)
            backgroundWork(commandId));
    lastByCommand.put(commandId, waiting);
    send.accept(waiting);
  }

  /** Cancels {@code commandId}'s pending grace, if it has one. */
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
   * A pending grace is cancelled first, as any hook cancels it: a dead agent is not one to call
   * waiting half an hour later, and its background-work verdict is cleared:
   * whatever it left running died with it.
   */
  void killed(String commandId, String hookEvent, int exitCode, String message) {
    if (commandId == null || commandId.isBlank()) {
      return;
    }
    cancelGrace(commandId);
    backgroundByCommand.remove(commandId);
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
            Boolean.TRUE,
            null,
            // cleared above: whatever it left running died with it (qits-1601)
            backgroundWork(commandId));
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

  /**
   * Whether {@code commandId}'s harness has work in flight (qits-1233): by its last stored frame, a
   * turn running ({@code BUSY}) or a frame that says outright it is <em>not</em> awaiting input; or
   * by its background-work verdict, a {@code Stop} that left a subagent, a monitor, a session cron
   * or a background shell running, which still maps to {@code IDLE}. The verdict is read separately
   * because the frame alone does not survive the {@code idle_prompt} {@code Notification} that
   * follows such a {@code Stop} (qits-1238). Once a shell-only {@code Stop}'s grace has expired it
   * no longer reads as in flight; the background-work grace's expiry (qits-1460) only makes the
   * agent read as waiting, and a subagent, monitor or cron stays in flight until a later hook says
   * otherwise. No frame at all, a {@code null} verdict (an older harness, an unrecognised
   * notification) with no background work recorded, and an ended command are not in flight: this is
   * asked before stopping a harness, and only a positive signal may hold that up.
   */
  boolean inFlight(String commandId) {
    if (commandId == null) {
      return false;
    }
    if (backgroundByCommand.contains(commandId)) {
      return true;
    }
    AgentActivity last = lastByCommand.get(commandId);
    return last != null
        && (AgentState.BUSY.equals(last.state()) || Boolean.FALSE.equals(last.awaitingInput()));
  }

  /** Re-send the last known activity for every still-tracked command (reconnect adoption). */
  void reportCurrent() {
    for (AgentActivity activity : lastByCommand.values()) {
      send.accept(activity);
    }
  }

  /** Stops the listener and cancels every pending grace. */
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
  private Boolean awaitingInput(String hookEvent, JsonObject body) {
    return switch (hookEvent) {
      case "Stop" -> stopVerdict(body).awaitingNow();
      case "Notification" -> awaitingInputForNotification(body);
      case "UserPromptSubmit" -> Boolean.FALSE;
      case "SessionEnd" -> Boolean.TRUE;
      case "SessionStart" -> null; // nothing has happened yet to have an opinion about
      default -> null; // not reached: mapState already dropped every other event
    };
  }

  /**
   * What a {@code Stop} says about the agent: {@code awaitingNow}, the {@code awaitingInput} its
   * own frame carries, {@code grace}, the grace to arm before it reads as waiting — null when there
   * is none to arm, non-null exactly when background work is in flight — and {@code
   * clearsInFlightOnExpiry}, whether that grace's expiry also ends the background-work verdict:
   * true for the shell grace only.
   */
  private record StopVerdict(
      Boolean awaitingNow, Duration grace, boolean clearsInFlightOnExpiry) {}

  /**
   * The {@code Stop} verdict, and the one place the kind of in-flight work maps to a grace. Either
   * key absent or not a JSON array is {@code (null, none)} — an older harness that does not report
   * them, read as "unknown" rather than guessed. Both arrays empty is {@code (true, none)}: nothing
   * is running, so the agent is waiting at once. Otherwise something is still running and the
   * answer is {@code false} now, with a grace: the background-shell one when {@code session_crons}
   * is empty and every {@code background_tasks} element is a JSON object with {@code "type":
   * "shell"}; the background-work one (qits-1460) when anything else is there — a subagent, a
   * monitor, an unknown or missing type, a non-object element, or a non-empty {@code
   * session_crons}, even beside shells, because the longer grace is the one that covers them all.
   * Only the shell grace's expiry ends the background-work verdict; see the class javadoc.
   */
  private StopVerdict stopVerdict(JsonObject body) {
    Object backgroundTasks = body.getValue("background_tasks");
    Object sessionCrons = body.getValue("session_crons");
    if (!(backgroundTasks instanceof JsonArray tasks)
        || !(sessionCrons instanceof JsonArray crons)) {
      return new StopVerdict(null, null, false);
    }
    if (tasks.isEmpty() && crons.isEmpty()) {
      return new StopVerdict(Boolean.TRUE, null, false);
    }
    boolean onlyShells = crons.isEmpty();
    for (Object task : tasks) {
      if (!(task instanceof JsonObject taskObject)
          || !"shell".equals(taskObject.getString("type"))) {
        onlyShells = false;
        break;
      }
    }
    return onlyShells
        ? new StopVerdict(Boolean.FALSE, backgroundShellGrace, true)
        : new StopVerdict(Boolean.FALSE, backgroundWorkGrace, false);
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
