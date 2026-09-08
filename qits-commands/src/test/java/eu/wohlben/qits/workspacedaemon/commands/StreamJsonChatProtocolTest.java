package eu.wohlben.qits.workspacedaemon.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The transport is driven against a real process rather than a mock, because what is under test is
 * what reaches the harness's <em>stdin</em> — and the cheapest way to observe that is a process
 * that announces itself and then echoes its input back on stdout ({@code cat}), so every write
 * comes back onto the wire in order.
 */
class StreamJsonChatProtocolTest {

  private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\"}";

  @Test
  void aNamedSessionAsksForRemoteControlAsSoonAsTheHarnessSaysInit() throws Exception {
    Process process = echoAfter(INIT);
    StreamJsonChatProtocol protocol =
        new StreamJsonChatProtocol(process, "cmd-1", "ticket/some-work");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});

    assertEquals(INIT, take(lines));
    JsonObject echoed = new JsonObject(take(lines));
    assertEquals("control_request", echoed.getString("type"));
    JsonObject request = echoed.getJsonObject("request");
    assertEquals("remote_control", request.getString("subtype"));
    assertTrue(request.getBoolean("enabled"), "the request enables the bridge");
    assertEquals(
        "ticket/some-work",
        request.getString("name"),
        "the branch names the session, so a remote list is readable");

    protocol.close();
    process.destroy();
  }

  @Test
  void itAsksOnlyOnceEvenIfTheHarnessReInitialises() throws Exception {
    Process process = echoAfter(INIT, INIT);
    StreamJsonChatProtocol protocol = new StreamJsonChatProtocol(process, "cmd-1", "a-branch");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});

    assertEquals(INIT, take(lines));
    assertEquals(INIT, take(lines), "the second init passes through");
    assertTrue(take(lines).contains("remote_control"), "the echo of the one request that was sent");

    assertUserTurnOnly(protocol, lines, "the second init raised no second request");

    protocol.close();
    process.destroy();
  }

  @Test
  void withoutANameNothingIsAskedFor() throws Exception {
    Process process = echoAfter(INIT);
    StreamJsonChatProtocol protocol = new StreamJsonChatProtocol(process, "cmd-1", "  ");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});

    assertEquals(INIT, take(lines));
    assertUserTurnOnly(
        protocol,
        lines,
        "the user turn is the first thing written to stdin — a blank name leaves the bridge down");

    protocol.close();
    process.destroy();
  }

  /**
   * Sends a turn and asserts the only two lines that follow are its synthetic echo and the harness's
   * echo of the stream-json turn — in whichever order they race — and no control request.
   */
  private static void assertUserTurnOnly(
      StreamJsonChatProtocol protocol, BlockingQueue<String> lines, String because)
      throws InterruptedException {
    protocol.sendUser("ping");
    String first = take(lines);
    String second = take(lines);
    assertTrue(!first.contains("control_request") && !second.contains("control_request"), because);
    assertTrue(
        first.contains("\"role\":\"user\"") || second.contains("\"role\":\"user\""),
        "the turn reached the harness");
  }

  /** A process that prints {@code announcements} and then echoes its stdin back on stdout. */
  private static Process echoAfter(String... announcements) throws IOException {
    StringBuilder script = new StringBuilder();
    for (String announcement : announcements) {
      script.append("printf '%s\\n' ").append('\'').append(announcement).append("' ; ");
    }
    script.append("exec cat");
    return new ProcessBuilder("bash", "-c", script.toString()).start();
  }

  private static String take(BlockingQueue<String> lines) throws InterruptedException {
    String line = lines.poll(10, TimeUnit.SECONDS);
    assertTrue(line != null, "expected a line on the wire");
    return line;
  }
}
