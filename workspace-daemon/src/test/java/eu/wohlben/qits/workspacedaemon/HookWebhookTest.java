package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentEvent;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentState;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Locks in {@link HookWebhook}'s decide-and-relay logic — the HTTP-free half that turns one hook
 * payload into at-most-one {@link AgentActivity} frame. Drives the package-private {@code handle}
 * seam directly (mirroring {@link GitStatusMonitorTest}'s {@code settle} seam) so no real port is
 * bound.
 */
class HookWebhookTest {

  private final List<DaemonMessage> sent = new ArrayList<>();

  /** What the activity listener ({@code AgentLaunchService.onActivity} in production) was told. */
  private final List<String> forwarded = new ArrayList<>();

  /**
   * A real Vertx even for the ungraced tests: since qits-1460 every Stop that leaves background
   * work in flight arms a timer, and the production graces are long enough never to fire here.
   */
  private final Vertx vertx = Vertx.vertx();

  private final HookWebhook webhook =
      new HookWebhook(vertx, 13337, sent::add, (id, state) -> forwarded.add(id + "=" + state));

  private AgentActivity lastSent() {
    return (AgentActivity) sent.get(sent.size() - 1);
  }

  private JsonObject payload(String event) {
    return new JsonObject()
        .put("hook_event_name", event)
        .put("session_id", "11111111-1111-1111-1111-111111111111")
        .put("transcript_path", "projects/-workspace/s.jsonl")
        .put("source", "startup");
  }

  @Test
  void mapsEachEventToItsState() {
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    assertEquals(AgentState.IDLE, lastSent().state());
    assertEquals("SessionStart", lastSent().hookEvent());
    assertEquals("cmd-1", lastSent().commandId());

    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    assertEquals(AgentState.BUSY, lastSent().state());

    webhook.handle("Stop", payload("Stop"), "cmd-1");
    assertEquals(AgentState.IDLE, lastSent().state());

    webhook.handle("Notification", payload("Notification"), "cmd-1");
    assertEquals(AgentState.WAITING, lastSent().state());

    webhook.handle("SessionEnd", payload("SessionEnd"), "cmd-1");
    assertEquals(AgentState.ENDED, lastSent().state());
  }

  @Test
  void forwardsSessionIdentityFields() {
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    assertEquals("11111111-1111-1111-1111-111111111111", lastSent().sessionId());
    assertEquals("projects/-workspace/s.jsonl", lastSent().transcriptPath());
    assertEquals("startup", lastSent().source());
  }

  @Test
  void dropsUninterestingEvents() {
    webhook.handle("SubagentStop", payload("SubagentStop"), "cmd-1");
    webhook.handle("PreToolUse", payload("PreToolUse"), "cmd-1");
    assertTrue(sent.isEmpty());
  }

  @Test
  void dropsPayloadWithNoCommandCorrelation() {
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), null);
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "  ");
    assertTrue(sent.isEmpty());
  }

  @Test
  void stopAfterAPermissionPromptKeepsWaiting() {
    webhook.handle(
        "Notification",
        payload("Notification").put("notification_type", "permission_prompt"),
        "cmd-1");
    assertEquals(AgentState.WAITING, lastSent().state());
    int before = sent.size();
    // The turn-end Stop must not downgrade the pending permission prompt.
    webhook.handle("Stop", payload("Stop"), "cmd-1");
    assertEquals(before, sent.size());
    assertEquals(AgentState.WAITING, lastSent().state());
  }

  @Test
  void reportCurrentReplaysLastStatePerCommand() {
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    webhook.handle("Notification", payload("Notification"), "cmd-2");
    sent.clear();
    webhook.reportCurrent();
    assertEquals(2, sent.size());
  }

  @Test
  void sessionEndEvictsFromReplay() {
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    webhook.handle("SessionEnd", payload("SessionEnd"), "cmd-1");
    sent.clear();
    webhook.reportCurrent();
    assertTrue(sent.isEmpty());
  }

  @Test
  void sessionStartAndStopForwardIdleToTheActivityListener() {
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    webhook.handle("Stop", payload("Stop"), "cmd-1");
    webhook.handle("SessionEnd", payload("SessionEnd"), "cmd-1");

    assertEquals(List.of("cmd-1=IDLE", "cmd-1=BUSY", "cmd-1=IDLE", "cmd-1=ENDED"), forwarded);
  }

  @Test
  void aStopWhileWaitingForwardsTheStoredWaitingNotTheEventsIdle() {
    // The whole point of forwarding the stored state: IDLE here would let a queued /rename be typed
    // into the open permission dialog, whose first keystroke answers it.
    webhook.handle(
        "Notification",
        payload("Notification").put("notification_type", "permission_prompt"),
        "cmd-1");
    webhook.handle("Stop", payload("Stop"), "cmd-1");

    assertEquals(List.of("cmd-1=WAITING", "cmd-1=WAITING"), forwarded);
  }

  @Test
  void droppedEventsForwardNothing() {
    webhook.handle("PreToolUse", payload("PreToolUse"), "cmd-1");
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), null);

    assertTrue(forwarded.isEmpty());
  }

  @Test
  void aKilledAgentIsRelayedAsEndedWithItsExitCodeAndLeavesTheReplay() {
    // A SIGKILLed agent fires no hook: without this frame the host keeps the IDLE it last heard,
    // and a reconnect would replay it for a process that is gone.
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");

    webhook.killed("cmd-1", AgentEvent.OOM_KILLED, 137, "killed by the out-of-memory killer");

    AgentActivity killed = lastSent();
    assertEquals(AgentState.ENDED, killed.state());
    assertEquals(AgentEvent.OOM_KILLED, killed.hookEvent());
    assertEquals(Integer.valueOf(137), killed.exitCode());
    assertEquals("killed by the out-of-memory killer", killed.message());
    assertEquals("11111111-1111-1111-1111-111111111111", killed.sessionId());
    assertEquals("cmd-1=ENDED", forwarded.get(forwarded.size() - 1));

    sent.clear();
    webhook.reportCurrent();
    assertTrue(sent.isEmpty(), "a killed agent is not replayed");
  }

  @Test
  void aThrowingListenerNeitherEscapesNorStopsTheRelayHome() {
    HookWebhook throwing =
        new HookWebhook(
            null,
            13337,
            sent::add,
            (id, state) -> {
              throw new IllegalStateException("boom");
            });

    throwing.handle("SessionStart", payload("SessionStart"), "cmd-1");

    assertEquals(AgentState.IDLE, lastSent().state());
  }

  // --- awaitingInput (qits-895) --------------------------------------------------------------
  // One test per row of the table in HookWebhook's class javadoc. The three Stop/UserPromptSubmit/
  // SessionEnd cases use the exact payloads captured on Claude Code 2.1.283 that the ticket shipped
  // with; the rest (no real capture to match) are built from the payload() helper.

  @Test
  void stopWithBothTaskArraysPresentAndEmptyIsAwaitingInput() {
    JsonObject body =
        new JsonObject(
            "{\"hook_event_name\":\"Stop\",\"stop_hook_active\":false,\"background_tasks\":[],"
                + "\"session_crons\":[]}");
    webhook.handle("Stop", body, "cmd-1");
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
  }

  @Test
  void stopWithARunningBackgroundShellIsNotAwaitingInputYet() {
    // A shell left running is as likely a build about to wake the agent as a dev server that never
    // will, so the verdict waits out the grace — see the grace tests below for the other half.
    HookWebhook graced = graced(Duration.ofMinutes(25));
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    assertEquals(Boolean.FALSE, lastGraced().awaitingInput());
    graced.close();
  }

  @Test
  void stopWithARunningBackgroundSubagentIsNotAwaitingInputYet() {
    JsonObject body =
        new JsonObject(
            "{\"hook_event_name\":\"Stop\",\"background_tasks\":[{\"id\":\"a94d\","
                + "\"type\":\"subagent\",\"status\":\"running\","
                + "\"description\":\"Reply with pong\",\"agent_type\":\"general-purpose\"}],"
                + "\"session_crons\":[]}");
    webhook.handle("Stop", body, "cmd-1");
    assertEquals(Boolean.FALSE, lastSent().awaitingInput());
  }

  @Test
  void stopWithAMixOfShellAndSubagentBackgroundTasksIsNotAwaitingInputYet() {
    JsonObject body =
        new JsonObject(
            "{\"hook_event_name\":\"Stop\",\"background_tasks\":["
                + "{\"id\":\"bmxnunzfz\",\"type\":\"shell\",\"status\":\"running\","
                + "\"description\":\"Sleep\",\"command\":\"sleep 25\"},"
                + "{\"id\":\"a94d\",\"type\":\"subagent\",\"status\":\"running\","
                + "\"description\":\"Reply with pong\",\"agent_type\":\"general-purpose\"}],"
                + "\"session_crons\":[]}");
    webhook.handle("Stop", body, "cmd-1");
    assertEquals(Boolean.FALSE, lastSent().awaitingInput());
  }

  @Test
  void stopWithABackgroundTaskMissingATypeIsNotAwaitingInputYet() {
    JsonObject body =
        new JsonObject(
            "{\"hook_event_name\":\"Stop\",\"background_tasks\":[{\"id\":\"bmxnunzfz\","
                + "\"status\":\"running\"}],\"session_crons\":[]}");
    webhook.handle("Stop", body, "cmd-1");
    assertEquals(Boolean.FALSE, lastSent().awaitingInput());
  }

  @Test
  void stopWithAPendingSessionCronIsNotAwaitingInputYet() {
    JsonObject body =
        new JsonObject(
            "{\"hook_event_name\":\"Stop\",\"background_tasks\":[],\"session_crons\":"
                + "[{\"id\":\"b8d1f60e\",\"schedule\":\"41 06 09 10 *\",\"recurring\":false,"
                + "\"prompt\":\"say tick\"}]}");
    webhook.handle("Stop", body, "cmd-1");
    assertEquals(Boolean.FALSE, lastSent().awaitingInput());
  }

  @Test
  void stopWithAPendingSessionCronAndOnlyShellBackgroundTasksIsNotAwaitingInputYet() {
    JsonObject body =
        new JsonObject(
            "{\"hook_event_name\":\"Stop\",\"background_tasks\":[{\"id\":\"bmxnunzfz\","
                + "\"type\":\"shell\",\"status\":\"running\",\"description\":\"Sleep\","
                + "\"command\":\"sleep 25\"}],\"session_crons\":"
                + "[{\"id\":\"b8d1f60e\",\"schedule\":\"41 06 09 10 *\",\"recurring\":false,"
                + "\"prompt\":\"say tick\"}]}");
    webhook.handle("Stop", body, "cmd-1");
    assertEquals(Boolean.FALSE, lastSent().awaitingInput());
  }

  @Test
  void stopWithNeitherTaskArrayReportedIsAwaitingInputUnknown() {
    // payload() carries no background_tasks/session_crons at all — an older harness's shape.
    webhook.handle("Stop", payload("Stop"), "cmd-1");
    assertNull(lastSent().awaitingInput());
  }

  @Test
  void stopWithATaskArrayThatIsNotAJsonArrayIsAwaitingInputUnknown() {
    JsonObject body =
        payload("Stop")
            .put("background_tasks", "not-an-array")
            .put("session_crons", new JsonArray());
    webhook.handle("Stop", body, "cmd-1");
    assertNull(lastSent().awaitingInput());
  }

  @Test
  void notificationPermissionPromptIsAwaitingInput() {
    JsonObject body = payload("Notification").put("notification_type", "permission_prompt");
    webhook.handle("Notification", body, "cmd-1");
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
  }

  @Test
  void notificationElicitationDialogIsAwaitingInput() {
    JsonObject body = payload("Notification").put("notification_type", "elicitation_dialog");
    webhook.handle("Notification", body, "cmd-1");
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
  }

  @Test
  void notificationOfAnyOtherTypeIsAwaitingInputUnknown() {
    // Not false: a notification type this daemon has never seen might still be a blocked prompt.
    JsonObject body = payload("Notification").put("notification_type", "idle_prompt");
    webhook.handle("Notification", body, "cmd-1");
    assertNull(lastSent().awaitingInput());
  }

  @Test
  void sessionStartIsAwaitingInputUnknown() {
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    assertNull(lastSent().awaitingInput());
  }

  @Test
  void userPromptSubmitIsNotAwaitingInput() {
    JsonObject body =
        new JsonObject("{\"hook_event_name\":\"UserPromptSubmit\",\"prompt\":\"hi\"}");
    webhook.handle("UserPromptSubmit", body, "cmd-1");
    assertEquals(Boolean.FALSE, lastSent().awaitingInput());
  }

  @Test
  void sessionEndIsAwaitingInput() {
    JsonObject body = new JsonObject("{\"hook_event_name\":\"SessionEnd\",\"reason\":\"other\"}");
    webhook.handle("SessionEnd", body, "cmd-1");
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
  }

  @Test
  void aKilledFrameIsAwaitingInput() {
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    webhook.killed("cmd-1", AgentEvent.KILLED, 137, "killed by SIGKILL");
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
  }

  @Test
  void stopWhileWaitingSendsNoFrameSoTheStandingNotificationsVerdictStillStands() {
    // No new wire frame goes out on this path (see stopAfterAPermissionPromptKeepsWaiting above and
    // the class javadoc), so there is nothing to carry a fresh verdict — the Notification frame
    // already sent, which carried true, is still the latest the backend or a replay has seen.
    JsonObject notification =
        payload("Notification").put("notification_type", "permission_prompt");
    webhook.handle("Notification", notification, "cmd-1");
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());

    int before = sent.size();
    JsonObject stop =
        payload("Stop")
            .put("background_tasks", new JsonArray())
            .put("session_crons", new JsonArray());
    webhook.handle("Stop", stop, "cmd-1");
    assertEquals(before, sent.size(), "the hold path sends no frame");
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
  }

  // --- only a real prompt holds a Stop (qits-1511) -------------------------------------------
  // Every Notification maps to WAITING, but only a permission or elicitation prompt says
  // awaitingInput true. Holding a Stop under any other WAITING — above all the idle_prompt Claude
  // Code sends a minute after every turn — swallowed the frame that would have said the agent is
  // waiting, and it never read as waiting again.

  @Test
  void aStopAfterAnIdlePromptOnASelfResumedTurnIsSentAndReadsAsWaiting()
      throws InterruptedException {
    // The ticket's reproduction: a background shell, the idle_prompt a minute later, the shell
    // finishing and the harness continuing by itself — no UserPromptSubmit — then the Stop.
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    graced.handle("Notification", idlePrompt(), "cmd-1");
    assertEquals(2, gracedSent.size());

    graced.handle("Stop", nothingRunningStop(), "cmd-1");
    assertEquals(3, gracedSent.size(), "the Stop is not held under an idle_prompt");
    AgentActivity stop = lastGraced();
    assertEquals("Stop", stop.hookEvent());
    assertEquals(AgentState.IDLE, stop.state());
    assertEquals(Boolean.TRUE, stop.awaitingInput());
    assertEquals(List.of("cmd-1=IDLE", "cmd-1=WAITING", "cmd-1=IDLE"), gracedForwarded);
    assertFalse(graced.inFlight("cmd-1"));

    outlastTheGrace();
    assertEquals(3, gracedSent.size(), "the cancelled shell grace sends nothing later");
    gracedSent.clear();
    graced.reportCurrent();
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput(), "and a reconnect replays it");
    graced.close();
  }

  @Test
  void aStopAfterAnIdlePromptFollowingAnEmptyStopSendsItsOwnFrame() {
    webhook.handle("Stop", nothingRunningStop(), "cmd-1");
    webhook.handle("Notification", idlePrompt(), "cmd-1");
    assertNull(lastSent().awaitingInput());

    webhook.handle("Stop", nothingRunningStop(), "cmd-1");
    assertEquals(3, sent.size());
    assertEquals("Stop", lastSent().hookEvent());
    assertEquals(AgentState.IDLE, lastSent().state());
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
  }

  @Test
  void aStopAfterAPermissionPromptFollowingAStopIsStillHeld() {
    webhook.handle("Stop", nothingRunningStop(), "cmd-1");
    webhook.handle(
        "Notification",
        payload("Notification").put("notification_type", "permission_prompt"),
        "cmd-1");
    int before = sent.size();

    webhook.handle("Stop", nothingRunningStop(), "cmd-1");
    assertEquals(before, sent.size(), "a real prompt still holds the Stop");
    assertEquals(AgentState.WAITING, lastSent().state());
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
    assertEquals("cmd-1=WAITING", forwarded.get(forwarded.size() - 1));
  }

  // --- inFlight (qits-1233) -------------------------------------------------------------------

  @Test
  void aStopThatLeftABackgroundSubagentRunningIsInFlight() {
    webhook.handle(
        "Stop",
        new JsonObject(
            "{\"background_tasks\":[{\"id\":\"999e3548\",\"type\":\"subagent\","
                + "\"status\":\"running\"}],\"session_crons\":[]}"),
        "cmd-1");
    assertEquals(AgentState.IDLE, lastSent().state(), "still IDLE on the wire");
    assertTrue(webhook.inFlight("cmd-1"));
  }

  @Test
  void aRunningTurnIsInFlight() {
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    assertTrue(webhook.inFlight("cmd-1"));
  }

  @Test
  void anIdleWaitingUnknownEndedOrUnheardHarnessIsNotInFlight() {
    assertFalse(webhook.inFlight("cmd-1"), "no hook heard");
    assertFalse(webhook.inFlight(null));

    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    assertFalse(webhook.inFlight("cmd-1"), "awaitingInput unknown");

    JsonObject nothingRunning =
        payload("Stop")
            .put("background_tasks", new JsonArray())
            .put("session_crons", new JsonArray());
    webhook.handle("Stop", nothingRunning, "cmd-1");
    assertFalse(webhook.inFlight("cmd-1"), "nothing left running");

    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    JsonObject prompt = payload("Notification").put("notification_type", "permission_prompt");
    webhook.handle("Notification", prompt, "cmd-1");
    assertFalse(webhook.inFlight("cmd-1"), "blocked on the user");

    webhook.handle("SessionEnd", payload("SessionEnd"), "cmd-1");
    assertFalse(webhook.inFlight("cmd-1"), "ended");

    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-2");
    webhook.killed("cmd-2", AgentEvent.KILLED, 137, "killed by SIGKILL");
    assertFalse(webhook.inFlight("cmd-2"), "killed");
  }

  // --- the background-work verdict survives a Notification (qits-1238) ------------------------

  private static JsonObject subagentStop() {
    return new JsonObject(
        "{\"hook_event_name\":\"Stop\",\"background_tasks\":[{\"id\":\"x\","
            + "\"type\":\"subagent\",\"status\":\"running\"}],\"session_crons\":[]}");
  }

  private static JsonObject nothingRunningStop() {
    return new JsonObject(
        "{\"hook_event_name\":\"Stop\",\"background_tasks\":[],\"session_crons\":[]}");
  }

  private JsonObject idlePrompt() {
    return payload("Notification").put("notification_type", "idle_prompt");
  }

  @Test
  void anIdlePromptAfterAStopWithARunningSubagentLeavesItInFlight() {
    webhook.handle("Stop", subagentStop(), "cmd-1");
    webhook.handle("Notification", idlePrompt(), "cmd-1");

    assertNull(lastSent().awaitingInput(), "the Notification's own frame is unchanged");
    assertTrue(webhook.inFlight("cmd-1"), "the subagent is still running");
  }

  @Test
  void aStopHeldUnderAPermissionPromptStillRecordsItsRunningSubagent() {
    webhook.handle(
        "Notification",
        payload("Notification").put("notification_type", "permission_prompt"),
        "cmd-1");
    int before = sent.size();
    webhook.handle("Stop", subagentStop(), "cmd-1");
    assertEquals(before, sent.size(), "the hold path still sends no frame");
    webhook.handle("Notification", idlePrompt(), "cmd-1");

    assertTrue(webhook.inFlight("cmd-1"));
  }

  @Test
  void aNewTurnThenAStopWithNothingRunningClearsTheBackgroundVerdict() {
    webhook.handle("Stop", subagentStop(), "cmd-1");
    webhook.handle("Notification", idlePrompt(), "cmd-1");
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    webhook.handle("Stop", nothingRunningStop(), "cmd-1");

    assertFalse(webhook.inFlight("cmd-1"));
  }

  @Test
  void aNewTurnClearsTheBackgroundVerdictBeforeItsOwnStopArrives() {
    // The prompt is the end of the previous turn's verdict, not the next Stop: a Notification
    // landing mid-turn replaces the BUSY frame, and must not let the old verdict show through.
    webhook.handle("Stop", subagentStop(), "cmd-1");
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    webhook.handle("Notification", idlePrompt(), "cmd-1");

    assertFalse(webhook.inFlight("cmd-1"));
  }

  @Test
  void aSessionEndClearsTheBackgroundVerdict() {
    webhook.handle("Stop", subagentStop(), "cmd-1");
    webhook.handle("Notification", idlePrompt(), "cmd-1");
    webhook.handle("SessionEnd", payload("SessionEnd"), "cmd-1");

    assertFalse(webhook.inFlight("cmd-1"));
  }

  @Test
  void aKilledFrameClearsTheBackgroundVerdict() {
    webhook.handle("Stop", subagentStop(), "cmd-1");
    webhook.handle("Notification", idlePrompt(), "cmd-1");
    webhook.killed("cmd-1", AgentEvent.KILLED, 137, "killed by SIGKILL");

    assertFalse(webhook.inFlight("cmd-1"));
  }

  @Test
  void aLaterStopWithNothingRunningClearsTheBackgroundVerdict() {
    webhook.handle("Stop", subagentStop(), "cmd-1");
    webhook.handle("Notification", idlePrompt(), "cmd-1");
    webhook.handle("Stop", nothingRunningStop(), "cmd-1");

    assertFalse(webhook.inFlight("cmd-1"));
  }

  @Test
  void aSessionStartDoesNotClearTheBackgroundVerdict() {
    webhook.handle("Stop", subagentStop(), "cmd-1");
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");

    assertTrue(webhook.inFlight("cmd-1"));
  }

  @Test
  void reportCurrentReplaysAwaitingInputToo() {
    JsonObject body =
        payload("Stop")
            .put("background_tasks", new JsonArray())
            .put("session_crons", new JsonArray());
    webhook.handle("Stop", body, "cmd-1");
    sent.clear();
    webhook.reportCurrent();
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
  }

  // --- the background-shell grace (qits-895) -------------------------------------------------
  // The timer fires on an event-loop thread, hence the thread-safe list and the polling await.

  private final List<DaemonMessage> gracedSent = new CopyOnWriteArrayList<>();

  private final List<String> gracedForwarded = new CopyOnWriteArrayList<>();

  private static final Duration SHORT_GRACE = Duration.ofMillis(200);

  @AfterEach
  void closeVertx() {
    webhook.close();
    vertx.close().toCompletionStage().toCompletableFuture().join();
  }

  /** A short shell grace and a background-work grace too long to fire within a test. */
  private HookWebhook graced(Duration shellGrace) {
    return graced(shellGrace, Duration.ofMinutes(60));
  }

  private HookWebhook graced(Duration shellGrace, Duration workGrace) {
    return new HookWebhook(
        vertx,
        13337,
        gracedSent::add,
        (id, state) -> gracedForwarded.add(id + "=" + state),
        shellGrace,
        workGrace);
  }

  private AgentActivity lastGraced() {
    return (AgentActivity) gracedSent.get(gracedSent.size() - 1);
  }

  private JsonObject shellOnlyStop() {
    return payload("Stop")
        .put(
            "background_tasks",
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("id", "bmxnunzfz")
                        .put("type", "shell")
                        .put("status", "running")
                        .put("command", "npm run dev")))
        .put("session_crons", new JsonArray());
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("condition not met within 10s");
      }
      Thread.sleep(20);
    }
  }

  /** Waits several graces, so a timer that should have been cancelled has had time to fire. */
  private static void outlastTheGrace() throws InterruptedException {
    Thread.sleep(SHORT_GRACE.toMillis() * 4);
  }

  @Test
  void aShellOnlyStopReadsAsWaitingOnceTheGraceExpires() throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    assertEquals(1, gracedSent.size());
    assertEquals(Boolean.FALSE, lastGraced().awaitingInput());

    await(() -> gracedSent.size() == 2);
    AgentActivity waiting = lastGraced();
    AgentActivity stop = (AgentActivity) gracedSent.get(0);
    assertEquals(Boolean.TRUE, waiting.awaitingInput());
    assertEquals("cmd-1", waiting.commandId());
    assertEquals(stop.sessionId(), waiting.sessionId());
    assertEquals(AgentState.IDLE, waiting.state());
    assertEquals("Stop", waiting.hookEvent());
    assertEquals(stop.source(), waiting.source());
    assertEquals(stop.transcriptPath(), waiting.transcriptPath());
    assertTrue(waiting.at() >= stop.at());
    // The state did not change, so the listener is not told a second IDLE.
    assertEquals(List.of("cmd-1=IDLE"), gracedForwarded);
    graced.close();
  }

  @Test
  void aShellOnlyStopIsInFlightUntilTheGraceExpires() throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    assertTrue(graced.inFlight("cmd-1"));

    await(() -> gracedSent.size() == 2);
    assertFalse(graced.inFlight("cmd-1"), "the grace flipped it to waiting");
    graced.close();
  }

  @Test
  void aUserPromptSubmitBeforeTheGraceCancelsIt() throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    graced.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");

    outlastTheGrace();
    assertEquals(2, gracedSent.size());
    assertFalse(
        gracedSent.stream().anyMatch(m -> Boolean.TRUE.equals(((AgentActivity) m).awaitingInput())));
    graced.close();
  }

  @Test
  void aSecondStopWithNothingRunningIsWaitingAtOnceAndLeavesNoLateDuplicate()
      throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    graced.handle(
        "Stop",
        payload("Stop")
            .put("background_tasks", new JsonArray())
            .put("session_crons", new JsonArray()),
        "cmd-1");
    assertEquals(2, gracedSent.size());
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput());

    outlastTheGrace();
    assertEquals(2, gracedSent.size(), "the cancelled grace sends nothing later");
    graced.close();
  }

  @Test
  void aSessionEndBeforeTheGraceCancelsIt() throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    graced.handle("SessionEnd", payload("SessionEnd"), "cmd-1");

    outlastTheGrace();
    assertEquals(2, gracedSent.size());
    assertEquals(AgentState.ENDED, lastGraced().state());
    graced.close();
  }

  @Test
  void reportCurrentAfterTheGraceReplaysAwaitingInput() throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    await(() -> gracedSent.size() == 2);

    gracedSent.clear();
    graced.reportCurrent();
    assertEquals(1, gracedSent.size());
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput());
    graced.close();
  }

  @Test
  void anIdlePromptNeitherCancelsTheShellGraceNorEndsInFlightBeforeIt()
      throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    graced.handle("Notification", idlePrompt(), "cmd-1");
    assertEquals(2, gracedSent.size());
    assertNull(lastGraced().awaitingInput(), "the Notification's own frame is unchanged");
    assertTrue(graced.inFlight("cmd-1"), "the shell's grace is still running");

    await(() -> gracedSent.size() == 3);
    AgentActivity waiting = lastGraced();
    assertEquals(Boolean.TRUE, waiting.awaitingInput());
    assertEquals("cmd-1", waiting.commandId());
    assertFalse(graced.inFlight("cmd-1"), "the grace expired");

    gracedSent.clear();
    graced.reportCurrent();
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput(), "and a reconnect replays it");
    graced.close();
  }

  @Test
  void aShellOnlyStopHeldUnderAPermissionPromptClearsWithItsGraceAndSendsNothing()
      throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle(
        "Notification",
        payload("Notification").put("notification_type", "permission_prompt"),
        "cmd-1");
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    assertTrue(graced.inFlight("cmd-1"));

    await(() -> !graced.inFlight("cmd-1"));
    outlastTheGrace();
    assertEquals(1, gracedSent.size(), "the standing prompt already reads as waiting");
    graced.close();
  }

  @Test
  void closeCancelsAPendingGrace() throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    graced.close();

    outlastTheGrace();
    assertEquals(1, gracedSent.size());
  }

  // --- the background-work grace (qits-1460) -------------------------------------------------
  // A subagent, a monitor or a session cron never produces the hook that would end a "not yet"
  // verdict if it outlives the turn, so without its own grace the agent would never read as
  // waiting.

  /** A shell grace too long to fire within a test, and a short background-work grace. */
  private HookWebhook workGraced() {
    return graced(Duration.ofMinutes(25), SHORT_GRACE);
  }

  private JsonObject stopWith(JsonArray backgroundTasks, JsonArray sessionCrons) {
    return payload("Stop")
        .put("background_tasks", backgroundTasks)
        .put("session_crons", sessionCrons);
  }

  private JsonObject backgroundSubagentStop() {
    return stopWith(
        new JsonArray()
            .add(
                new JsonObject()
                    .put("id", "a94d")
                    .put("type", "subagent")
                    .put("status", "running")
                    .put("agent_type", "general-purpose")),
        new JsonArray());
  }

  private JsonObject backgroundMonitorStop() {
    return stopWith(
        new JsonArray()
            .add(
                new JsonObject()
                    .put("id", "m1")
                    .put("type", "monitor")
                    .put("status", "running")
                    .put("description", "tail the CI log")),
        new JsonArray());
  }

  private JsonObject recurringSessionCronStop() {
    return stopWith(
        new JsonArray(),
        new JsonArray()
            .add(
                new JsonObject()
                    .put("id", "b8d1f60e")
                    .put("schedule", "*/5 * * * *")
                    .put("recurring", true)
                    .put("prompt", "check the build")));
  }

  private void assertFalseThenWaitingAfterTheWorkGrace(JsonObject stop)
      throws InterruptedException {
    HookWebhook graced = workGraced();
    graced.handle("Stop", stop, "cmd-1");
    assertEquals(1, gracedSent.size());
    assertEquals(Boolean.FALSE, lastGraced().awaitingInput());
    assertTrue(graced.inFlight("cmd-1"));

    await(() -> gracedSent.size() == 2);
    AgentActivity waiting = lastGraced();
    AgentActivity first = (AgentActivity) gracedSent.get(0);
    assertEquals(Boolean.TRUE, waiting.awaitingInput());
    assertEquals("cmd-1", waiting.commandId());
    assertEquals(first.sessionId(), waiting.sessionId());
    assertEquals(AgentState.IDLE, waiting.state());
    assertEquals("Stop", waiting.hookEvent());
    assertTrue(waiting.at() >= first.at());
    assertTrue(
        graced.inFlight("cmd-1"), "reads as waiting, but the reported work is still running");
    assertEquals(List.of("cmd-1=IDLE"), gracedForwarded, "the listener is not told again");

    gracedSent.clear();
    graced.reportCurrent();
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput(), "and a reconnect replays it");
    graced.close();
  }

  @Test
  void aStopWithARunningSubagentReadsAsWaitingOnceTheBackgroundWorkGraceExpires()
      throws InterruptedException {
    assertFalseThenWaitingAfterTheWorkGrace(backgroundSubagentStop());
  }

  @Test
  void aStopWithARunningMonitorReadsAsWaitingOnceTheBackgroundWorkGraceExpires()
      throws InterruptedException {
    assertFalseThenWaitingAfterTheWorkGrace(backgroundMonitorStop());
  }

  @Test
  void aStopWithARecurringSessionCronReadsAsWaitingOnceTheBackgroundWorkGraceExpires()
      throws InterruptedException {
    assertFalseThenWaitingAfterTheWorkGrace(recurringSessionCronStop());
  }

  @Test
  void aStopWithAnUntypedBackgroundTaskReadsAsWaitingOnceTheBackgroundWorkGraceExpires()
      throws InterruptedException {
    assertFalseThenWaitingAfterTheWorkGrace(
        stopWith(new JsonArray().add(new JsonObject().put("id", "x")), new JsonArray()));
  }

  @Test
  void aUserPromptSubmitBeforeTheBackgroundWorkGraceCancelsIt() throws InterruptedException {
    HookWebhook graced = workGraced();
    graced.handle("Stop", backgroundSubagentStop(), "cmd-1");
    graced.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");

    outlastTheGrace();
    assertEquals(2, gracedSent.size());
    assertFalse(
        gracedSent.stream().anyMatch(m -> Boolean.TRUE.equals(((AgentActivity) m).awaitingInput())));
    graced.close();
  }

  @Test
  void anIdlePromptDoesNotCancelTheBackgroundWorkGrace() throws InterruptedException {
    HookWebhook graced = workGraced();
    graced.handle("Stop", recurringSessionCronStop(), "cmd-1");
    graced.handle("Notification", idlePrompt(), "cmd-1");
    assertEquals(2, gracedSent.size());
    assertTrue(graced.inFlight("cmd-1"), "the cron's grace is still running");

    await(() -> gracedSent.size() == 3);
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput());
    assertEquals("Stop", lastGraced().hookEvent());
    assertTrue(graced.inFlight("cmd-1"), "the cron is still scheduled");
    graced.close();
  }

  @Test
  void aStopWithShellsBesideASubagentWaitsOutTheLongerBackgroundWorkGrace()
      throws InterruptedException {
    // Shell grace short, background-work grace long: the mix must not fire on the shell's.
    HookWebhook graced = graced(SHORT_GRACE, Duration.ofMinutes(60));
    JsonArray tasks =
        new JsonArray()
            .add(new JsonObject().put("id", "b1").put("type", "shell").put("status", "running"))
            .add(new JsonObject().put("id", "a1").put("type", "subagent").put("status", "running"));
    graced.handle("Stop", stopWith(tasks, new JsonArray()), "cmd-1");
    assertEquals(Boolean.FALSE, lastGraced().awaitingInput());

    outlastTheGrace();
    assertEquals(1, gracedSent.size(), "the shell grace does not apply to the mix");
    assertTrue(graced.inFlight("cmd-1"));
    graced.close();
  }

  @Test
  void aShellOnlyStopKeepsTheShellGraceAndNotTheBackgroundWorkOne() throws InterruptedException {
    // Background-work grace short, shell grace long: a shell-only Stop must not fire on it.
    HookWebhook graced = workGraced();
    graced.handle("Stop", shellOnlyStop(), "cmd-1");

    outlastTheGrace();
    assertEquals(1, gracedSent.size());
    assertTrue(graced.inFlight("cmd-1"));
    graced.close();
  }

  @Test
  void aStopWithASubagentHeldUnderAPermissionPromptSendsNothingAndStaysInFlightPastItsGrace()
      throws InterruptedException {
    HookWebhook graced = workGraced();
    graced.handle(
        "Notification",
        payload("Notification").put("notification_type", "permission_prompt"),
        "cmd-1");
    graced.handle("Stop", backgroundSubagentStop(), "cmd-1");
    assertTrue(graced.inFlight("cmd-1"));

    outlastTheGrace();
    assertEquals(1, gracedSent.size(), "the standing prompt already reads as waiting");
    assertTrue(graced.inFlight("cmd-1"), "the subagent is still running");
    graced.close();
  }

  /** A work-graced Stop with a running subagent, waited past its grace. */
  private HookWebhook subagentStopPastItsGrace() throws InterruptedException {
    HookWebhook graced = workGraced();
    graced.handle("Stop", backgroundSubagentStop(), "cmd-1");
    await(() -> gracedSent.size() == 2);
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput());
    assertTrue(graced.inFlight("cmd-1"));
    return graced;
  }

  @Test
  void aUserPromptSubmitAfterTheBackgroundWorkGraceEndsInFlight() throws InterruptedException {
    HookWebhook graced = subagentStopPastItsGrace();
    graced.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    graced.handle("Notification", idlePrompt(), "cmd-1");
    assertFalse(graced.inFlight("cmd-1"));
    graced.close();
  }

  @Test
  void aSessionEndAfterTheBackgroundWorkGraceEndsInFlight() throws InterruptedException {
    HookWebhook graced = subagentStopPastItsGrace();
    graced.handle("SessionEnd", payload("SessionEnd"), "cmd-1");
    assertFalse(graced.inFlight("cmd-1"));
    graced.close();
  }

  @Test
  void aStopWithNothingRunningAfterTheBackgroundWorkGraceEndsInFlight()
      throws InterruptedException {
    HookWebhook graced = subagentStopPastItsGrace();
    graced.handle("Stop", stopWith(new JsonArray(), new JsonArray()), "cmd-1");
    assertFalse(graced.inFlight("cmd-1"));
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput());
    graced.close();
  }

  // --- the background-work verdict on the wire (qits-1601) -----------------------------------
  // The verdict above used to stay inside the daemon, so the host read an agent busy with its own
  // subagent as IDLE, then WAITING. Every frame now carries it; state and awaitingInput stay put.

  private static JsonObject reproducedSubagentStop() {
    return new JsonObject(
        "{\"hook_event_name\":\"Stop\",\"background_tasks\":[{\"id\":\"a1\","
            + "\"type\":\"subagent\",\"status\":\"running\"}],\"session_crons\":[]}");
  }

  @Test
  void aTurnThatLeftASubagentRunningSaysSoOnItsStopAndOnTheIdlePromptAfterIt() {
    webhook.handle("UserPromptSubmit", payload("UserPromptSubmit"), "cmd-1");
    AgentActivity submitted = lastSent();
    assertEquals(AgentState.BUSY, submitted.state());
    assertEquals(Boolean.FALSE, submitted.awaitingInput());
    assertEquals(Boolean.FALSE, submitted.backgroundWork());

    webhook.handle("Stop", reproducedSubagentStop(), "cmd-1");
    AgentActivity stop = lastSent();
    assertEquals(AgentState.IDLE, stop.state());
    assertEquals(Boolean.FALSE, stop.awaitingInput());
    assertEquals(Boolean.TRUE, stop.backgroundWork());

    webhook.handle("Notification", idlePrompt(), "cmd-1");
    AgentActivity idlePrompt = lastSent();
    assertEquals(AgentState.WAITING, idlePrompt.state());
    assertNull(idlePrompt.awaitingInput());
    assertEquals(
        Boolean.TRUE, idlePrompt.backgroundWork(), "a Notification does not clear the verdict");
    assertEquals(3, sent.size());
  }

  @Test
  void aLaterStopWithNothingRunningSaysNoBackgroundWork() {
    webhook.handle("Stop", reproducedSubagentStop(), "cmd-1");
    webhook.handle("Notification", idlePrompt(), "cmd-1");

    webhook.handle("Stop", nothingRunningStop(), "cmd-1");

    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
    assertEquals(Boolean.FALSE, lastSent().backgroundWork());
  }

  @Test
  void aStopFromAHarnessThatDoesNotReportTheArraysSaysNoBackgroundWork() {
    webhook.handle("Stop", reproducedSubagentStop(), "cmd-1");

    webhook.handle("Stop", payload("Stop"), "cmd-1");

    assertNull(lastSent().awaitingInput());
    assertEquals(Boolean.FALSE, lastSent().backgroundWork());
  }

  @Test
  void aSessionStartCarriesTheVerdictAsItStands() {
    webhook.handle("SessionStart", payload("SessionStart"), "cmd-1");
    assertEquals(Boolean.FALSE, lastSent().backgroundWork());
  }

  @Test
  void aKilledFrameSaysNoBackgroundWork() {
    webhook.handle("Stop", reproducedSubagentStop(), "cmd-1");

    webhook.killed("cmd-1", AgentEvent.OOM_KILLED, 137, "killed by the out-of-memory killer");

    assertEquals(AgentState.ENDED, lastSent().state());
    assertEquals(Boolean.FALSE, lastSent().backgroundWork());
  }

  @Test
  void aSessionEndSaysNoBackgroundWork() {
    webhook.handle("Stop", reproducedSubagentStop(), "cmd-1");

    webhook.handle("SessionEnd", payload("SessionEnd"), "cmd-1");

    assertEquals(AgentState.ENDED, lastSent().state());
    assertEquals(Boolean.FALSE, lastSent().backgroundWork());
  }

  @Test
  void aShellGracesExpiryFrameSaysNoBackgroundWork() throws InterruptedException {
    HookWebhook graced = graced(SHORT_GRACE);
    graced.handle("Stop", shellOnlyStop(), "cmd-1");
    assertEquals(Boolean.TRUE, lastGraced().backgroundWork());

    await(() -> gracedSent.size() == 2);
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput());
    assertEquals(
        Boolean.FALSE, lastGraced().backgroundWork(), "the shell grace clears it before the frame");
    graced.close();
  }

  @Test
  void aBackgroundWorkGracesExpiryFrameStillSaysBackgroundWork() throws InterruptedException {
    HookWebhook graced = workGraced();
    graced.handle("Stop", backgroundSubagentStop(), "cmd-1");
    assertEquals(Boolean.TRUE, lastGraced().backgroundWork());

    await(() -> gracedSent.size() == 2);
    assertEquals(Boolean.TRUE, lastGraced().awaitingInput());
    assertEquals(Boolean.TRUE, lastGraced().backgroundWork(), "the subagent is still running");

    gracedSent.clear();
    graced.reportCurrent();
    assertEquals(Boolean.TRUE, lastGraced().backgroundWork(), "and a reconnect replays it");
    graced.close();
  }
}
