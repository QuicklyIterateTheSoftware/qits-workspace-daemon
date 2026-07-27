package eu.wohlben.qits.workspacedaemon.agents.acp;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The shared uuid-minting rule for Kimi chat, consumed by <em>both</em> the live ACP normalizer and
 * the {@code wire.jsonl} transcript importer so the same logical event gets the same {@code uuid} on
 * both sides. That shared id is what lets {@code ChatSession.attach} stitch the durable transcript
 * head to the live ring losslessly (the seam is the first ring line whose uuid the transcript
 * already contains).
 *
 * <p>ACP updates and {@code wire.jsonl} lines carry no natively shared id, so the key is derived
 * from whatever both sides observe: a tool event keys on the ACP {@code toolCallId} (present in
 * both), a message on {@code sessionId:kind:index}. Tool calls — the reliable anchor — always align;
 * message indices align when both sides count messages the same way and otherwise degrade to {@code
 * ChatSession.attach}'s best-effort no-shared-uuid path (per the feature's pragmatic contract).
 */
public final class KimiChatUuids {

  private KimiChatUuids() {}

  /** A deterministic UUID for {@code key} — same key ⇒ same uuid, on either side of the seam. */
  public static String forKey(String key) {
    return UUID.nameUUIDFromBytes(("qits-kimi:" + key).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /** The tool-call event's uuid (the {@code tool_use} the assistant emits). */
  static String forToolCall(String toolCallId) {
    return forKey("tool:" + toolCallId);
  }

  /** The tool-result event's uuid (the {@code tool_result} the update carries back). */
  static String forToolResult(String toolCallId) {
    return forKey("toolresult:" + toolCallId);
  }

  /** A message event's uuid, keyed by session, kind ({@code a}/{@code t}/{@code u}) and index. */
  static String forMessage(String sessionId, String kind, int index) {
    return forKey(sessionId + ":" + kind + ":" + index);
  }
}
