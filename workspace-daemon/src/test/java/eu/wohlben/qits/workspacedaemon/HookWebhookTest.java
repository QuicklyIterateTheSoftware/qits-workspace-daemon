package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentState;
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
  private final HookWebhook webhook = new HookWebhook(null, 13337, sent::add);

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
}
