package eu.wohlben.qits.workspacedaemon.agents.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.agents.json.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for the Kimi conversation normalizer — both its live ACP entry point and its {@code
 * wire.jsonl} entry point, and the shared uuid minting that ties them together.
 */
public class KimiEventNormalizerTest {

  private static final String SESSION = "session_aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  private Json json(String raw) {
    return Json.parse(raw);
  }

  @Test
  public void acpChunksCoalesceIntoOneAssistantMessagePerTurn() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    assertTrue(
        n.onAcpUpdate(
                json(
                    "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Hel\"}}"))
            .isEmpty(),
        "a chunk buffers, it does not emit yet");
    assertTrue(
        n.onAcpUpdate(
                json(
                    "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"lo\"}}"))
            .isEmpty());

    List<String> end = n.finishTurn();
    // One coalesced assistant message + the turn-end result marker.
    assertEquals(2, end.size(), end.toString());
    Json assistant = json(end.get(0));
    assertEquals("assistant", assistant.path("type").asText());
    assertEquals("Hello", assistant.path("message").path("content").path(0).path("text").asText());
    assertEquals("result", json(end.get(1)).path("type").asText());
  }

  @Test
  public void acpToolCallBecomesAToolUseLineKeyedByToolCallId() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    List<String> out =
        n.onAcpUpdate(
            json(
                "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"tc-1\",\"title\":\"Read\",\"status\":\"pending\"}"));

    assertEquals(1, out.size());
    Json line = json(out.get(0));
    Json block = line.path("message").path("content").path(0);
    assertEquals("tool_use", block.path("type").asText());
    assertEquals("Read", block.path("name").asText());
    assertEquals("tc-1", block.path("id").asText());
    assertEquals(KimiChatUuids.forToolCall("tc-1"), line.path("uuid").asText());
  }

  @Test
  public void acpToolCallUpdateWithContentBecomesAToolResult() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    List<String> out =
        n.onAcpUpdate(
            json(
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"tc-1\",\"status\":\"completed\","
                    + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"file body\"}}]}"));

    assertEquals(1, out.size());
    Json block = json(out.get(0)).path("message").path("content").path(0);
    assertEquals("tool_result", block.path("type").asText());
    assertEquals("file body", block.path("content").asText());
    assertFalse(block.has("is_error"));
  }

  @Test
  public void acpFailedToolUpdateMarksTheResultAsError() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    List<String> out =
        n.onAcpUpdate(
            json(
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"tc-1\",\"status\":\"failed\","
                    + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"boom\"}}]}"));

    assertTrue(
        json(out.get(0)).path("message").path("content").path(0).path("is_error").asBoolean());
  }

  @Test
  public void streamedToolUpdatesEmitAtMostOneResultPerToolCall() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    List<String> first =
        n.onAcpUpdate(
            json(
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"tc-1\",\"status\":\"in_progress\","
                    + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"partial\"}}]}"));
    List<String> second =
        n.onAcpUpdate(
            json(
                "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"tc-1\",\"status\":\"completed\","
                    + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"partial done\"}}]}"));

    assertEquals(1, first.size(), "the first content-bearing update emits the result");
    assertTrue(second.isEmpty(), "a later update for the same toolCallId must not double-render");
  }

  @Test
  public void onWireLineParsesRawStringsAndResetRestartsIndices() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    String u0 = n.onWireLine("{\"role\":\"user\",\"content\":\"a\"}").get(0);
    assertTrue(n.onWireLine("not json").isEmpty(), "unparseable lines are dropped");
    n.reset();
    String u0AfterReset = n.onWireLine("{\"role\":\"user\",\"content\":\"a\"}").get(0);

    // reset() restarts the user index, so the same first line mints the same uuid again.
    assertEquals(json(u0).path("uuid").asText(), json(u0AfterReset).path("uuid").asText());
  }

  @Test
  public void wireNoiseLinesAreDropped() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    assertTrue(n.onWireLine(json("{\"metadata\":{\"protocol_version\":\"1.0\"}}")).isEmpty());
    assertTrue(n.onWireLine(json("{\"config\":{\"update\":{\"tools\":[]}}}")).isEmpty());
    assertTrue(n.onWireLine(json("{\"session\":{\"resume_hint\":\"x\"}}")).isEmpty());
  }

  @Test
  public void wireMessagesNormalizeToTheEnvelope() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    Json user = json(n.onWireLine(json("{\"role\":\"user\",\"content\":\"hi\"}")).get(0));
    assertEquals("user", user.path("type").asText());
    assertEquals("hi", user.path("message").path("content").path(0).path("text").asText());

    Json assistant =
        json(
            n.onWireLine(
                    json(
                        "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"yo\"}]}"))
                .get(0));
    assertEquals("assistant", assistant.path("type").asText());
    assertEquals("yo", assistant.path("message").path("content").path(0).path("text").asText());
  }

  @Test
  public void sidechainLinesAreStampedAndScopedSoTheyNeverCollideWithMain() {
    Json mainLine =
        json(
            new KimiEventNormalizer(SESSION)
                .onWireLine(json("{\"role\":\"assistant\",\"content\":\"m\"}"))
                .get(0));
    Json sideLine =
        json(
            new KimiEventNormalizer(SESSION)
                .asSidechain("sub-1")
                .onWireLine(json("{\"role\":\"assistant\",\"content\":\"s\"}"))
                .get(0));

    assertTrue(sideLine.path("isSidechain").asBoolean());
    assertEquals("sub-1", sideLine.path("agentId").asText());
    // Same message index (0) on both, but the sidechain scope keeps their uuids distinct.
    assertFalse(mainLine.path("uuid").asText().equals(sideLine.path("uuid").asText()));
  }

  @Test
  public void wireConsecutiveTextBlocksCoalesceIntoOneAssistantEnvelope() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    List<String> out =
        n.onWireLine(
            json(
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Hel\"},"
                    + "{\"type\":\"text\",\"text\":\"lo\"}]}"));

    assertEquals(1, out.size(), "consecutive text blocks join into one message");
    assertEquals(
        "Hello", json(out.get(0)).path("message").path("content").path(0).path("text").asText());
  }

  @Test
  public void wireTextSplitByAToolMatchesTheLiveSplitOrder() {
    KimiEventNormalizer n = new KimiEventNormalizer(SESSION);

    List<String> out =
        n.onWireLine(
            json(
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"before\"},"
                    + "{\"type\":\"tool_use\",\"id\":\"tc-1\",\"name\":\"Read\"},"
                    + "{\"type\":\"text\",\"text\":\"after\"}]}"));

    assertEquals(3, out.size(), "text | tool | text splits at the tool, like the live path");
    assertEquals(
        "before", json(out.get(0)).path("message").path("content").path(0).path("text").asText());
    assertEquals(
        "tool_use", json(out.get(1)).path("message").path("content").path(0).path("type").asText());
    assertEquals(
        "after", json(out.get(2)).path("message").path("content").path(0).path("text").asText());
  }

  @Test
  public void liveAndTranscriptMintTheSameAssistantUuidForATextTurn() {
    // Live coalesces chunks into one assistant message; the transcript coalesces the wire.jsonl
    // message's text blocks the same way, so a tool-free turn's assistant uuid still aligns.
    KimiEventNormalizer live = new KimiEventNormalizer(SESSION);
    live.onAcpUpdate(
        json(
            "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Hel\"}}"));
    live.onAcpUpdate(
        json(
            "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"lo\"}}"));
    String liveAssistant = live.finishTurn().get(0);

    String wireAssistant =
        new KimiEventNormalizer(SESSION)
            .onWireLine(json("{\"role\":\"assistant\",\"content\":\"Hello\"}"))
            .get(0);

    assertEquals(
        json(liveAssistant).path("uuid").asText(), json(wireAssistant).path("uuid").asText());
  }

  @Test
  public void liveAndTranscriptMintTheSameUuidForTheSameToolCall() {
    // The re-attach contract: a tool call seen live over ACP and the same tool event replayed from
    // wire.jsonl must carry an identical uuid, so ChatSession.attach stitches at that seam.
    String live =
        new KimiEventNormalizer(SESSION)
            .onAcpUpdate(
                json(
                    "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"tc-9\",\"title\":\"Bash\"}"))
            .get(0);
    List<String> wireOut = new ArrayList<>();
    wireOut.addAll(
        new KimiEventNormalizer(SESSION)
            .onWireLine(
                json(
                    "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tc-9\",\"name\":\"Bash\"}]}")));

    assertEquals(json(live).path("uuid").asText(), json(wireOut.get(0)).path("uuid").asText());
    assertEquals(KimiChatUuids.forToolCall("tc-9"), json(live).path("uuid").asText());
  }
}
