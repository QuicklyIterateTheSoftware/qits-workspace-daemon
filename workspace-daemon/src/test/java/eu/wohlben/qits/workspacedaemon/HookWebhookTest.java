package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentEvent;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentState;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
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

  private final HookWebhook webhook =
      new HookWebhook(null, 13337, sent::add, (id, state) -> forwarded.add(id + "=" + state));

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
  void stopAfterNotificationKeepsWaiting() {
    webhook.handle("Notification", payload("Notification"), "cmd-1");
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
    webhook.handle("Notification", payload("Notification"), "cmd-1");
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
  void stopWithARunningBackgroundShellIsAwaitingInput() {
    // A long-lived background shell (dev server, poller, tail -f) must not keep the agent from
    // being read as waiting — its own completion re-invokes the agent regardless.
    JsonObject body =
        new JsonObject(
            "{\"hook_event_name\":\"Stop\",\"background_tasks\":[{\"id\":\"bmxnunzfz\","
                + "\"type\":\"shell\",\"status\":\"running\",\"description\":\"Sleep\","
                + "\"command\":\"sleep 25\"}],\"session_crons\":[]}");
    webhook.handle("Stop", body, "cmd-1");
    assertEquals(Boolean.TRUE, lastSent().awaitingInput());
  }

  @Test
  void stopWithARunningBackgroundSubagentIsNotAwaitingInput() {
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
  void stopWithAMixOfShellAndSubagentBackgroundTasksIsNotAwaitingInput() {
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
  void stopWithABackgroundTaskMissingATypeIsNotAwaitingInput() {
    JsonObject body =
        new JsonObject(
            "{\"hook_event_name\":\"Stop\",\"background_tasks\":[{\"id\":\"bmxnunzfz\","
                + "\"status\":\"running\"}],\"session_crons\":[]}");
    webhook.handle("Stop", body, "cmd-1");
    assertEquals(Boolean.FALSE, lastSent().awaitingInput());
  }

  @Test
  void stopWithAPendingSessionCronIsNotAwaitingInput() {
    JsonObject body =
        new JsonObject(
            "{\"hook_event_name\":\"Stop\",\"background_tasks\":[],\"session_crons\":"
                + "[{\"id\":\"b8d1f60e\",\"schedule\":\"41 06 09 10 *\",\"recurring\":false,"
                + "\"prompt\":\"say tick\"}]}");
    webhook.handle("Stop", body, "cmd-1");
    assertEquals(Boolean.FALSE, lastSent().awaitingInput());
  }

  @Test
  void stopWithAPendingSessionCronAndOnlyShellBackgroundTasksIsNotAwaitingInput() {
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
    // No new wire frame goes out on this path (see stopAfterNotificationKeepsWaiting above and
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
}
