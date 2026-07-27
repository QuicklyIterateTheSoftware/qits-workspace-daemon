package eu.wohlben.qits.workspacedaemon.agents.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.agents.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Drives {@link AcpChatProtocol} against a fake ACP peer (a thread speaking scripted JSON-RPC over
 * piped streams), exercising the full handshake, a prompt turn with a streamed update,
 * auto-approved permission, the learned session id, and clean teardown — no real {@code kimi}
 * needed.
 */
public class AcpChatProtocolTest {

  private static final String SESSION = "session_aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  /** A {@link Process} backed by two pipes we drive from the test's fake peer. */
  private static final class FakeProcess extends Process {
    private final InputStream in;
    private final OutputStream out;

    FakeProcess(InputStream in, OutputStream out) {
      this.in = in;
      this.out = out;
    }

    @Override
    public OutputStream getOutputStream() {
      return out;
    }

    @Override
    public InputStream getInputStream() {
      return in;
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {}

    @Override
    public boolean isAlive() {
      return true;
    }
  }

  @Test
  @Timeout(30)
  public void drivesAnAcpSessionEndToEnd() throws Exception {
    // The client reads kimi stdout via agentIn; the peer writes replies to peerOut.
    PipedInputStream agentIn = new PipedInputStream(1 << 16);
    PipedOutputStream peerOut = new PipedOutputStream(agentIn);
    // The client writes kimi stdin via agentOut; the peer reads requests from peerIn.
    PipedInputStream peerIn = new PipedInputStream(1 << 16);
    PipedOutputStream agentOut = new PipedOutputStream(peerIn);

    List<String> emitted = new CopyOnWriteArrayList<>();
    CountDownLatch sessionLatch = new CountDownLatch(1);
    AtomicReference<String> reportedSession = new AtomicReference<>();
    AtomicReference<Json> newSessionParams = new AtomicReference<>();
    AtomicReference<String> approvedOptionId = new AtomicReference<>();

    AcpSessionConfig config =
        new AcpSessionConfig(
            "/workspace",
            List.of(
                new AcpSessionConfig.AcpMcpServer(
                    "repository", "http://qits:8080/projects/mcp?x", List.of("taskPrompt"))),
            null,
            id -> {
              reportedSession.set(id);
              sessionLatch.countDown();
            });

    Thread peer =
        new Thread(
            () ->
                runPeer(
                    new BufferedReader(new InputStreamReader(peerIn, StandardCharsets.UTF_8)),
                    new BufferedWriter(new OutputStreamWriter(peerOut, StandardCharsets.UTF_8)),
                    newSessionParams,
                    approvedOptionId),
            "fake-acp-peer");
    peer.setDaemon(true);
    peer.start();

    AcpChatProtocol protocol = new AcpChatProtocol(new FakeProcess(agentIn, agentOut), config);
    CountDownLatch endLatch = new CountDownLatch(1);
    protocol.start(emitted::add, endLatch::countDown);

    // The handshake learns the session id from session/new and reports it.
    assertTrue(sessionLatch.await(10, TimeUnit.SECONDS), "session id must be reported");
    assertEquals(SESSION, reportedSession.get());
    // session/new carried the scoped MCP servers with bare enabledTools.
    Json servers = newSessionParams.get().path("mcpServers");
    assertEquals("repository", servers.path(0).path("name").asText());
    assertEquals("http", servers.path(0).path("type").asText());
    assertEquals("taskPrompt", servers.path(0).path("enabledTools").path(0).asText());

    protocol.sendUser("do it");
    awaitContains(emitted, "\"type\":\"result\"");

    assertTrue(
        emitted.stream().anyMatch(l -> l.contains("\"type\":\"user\"") && l.contains("do it")),
        "the user turn is echoed: " + emitted);
    assertTrue(
        emitted.stream()
            .anyMatch(l -> l.contains("\"type\":\"assistant\"") && l.contains("working")),
        "the streamed assistant update is normalized: " + emitted);
    assertEquals("allow", approvedOptionId.get(), "permission auto-approved with an allow option");

    protocol.close();
    assertTrue(endLatch.await(10, TimeUnit.SECONDS), "closing the transport latches the exit");
  }

  /** The scripted peer: replies to the handshake, then to a prompt with a permission ask + update. */
  private void runPeer(
      BufferedReader in,
      BufferedWriter out,
      AtomicReference<Json> newSessionParams,
      AtomicReference<String> approvedOptionId) {
    try {
      String line;
      while ((line = in.readLine()) != null) {
        Json msg = Json.parse(line);
        String method = msg.path("method").asText("");
        if (method.isEmpty()) {
          continue; // a stray response to us; the prompt flow reads its own inline.
        }
        int id = msg.path("id").asInt(-1);
        switch (method) {
          case "initialize" -> reply(out, id, new JsonObject().put("protocolVersion", 1));
          case "session/new" -> {
            newSessionParams.set(msg.path("params"));
            reply(out, id, new JsonObject().put("sessionId", SESSION));
          }
          case "session/prompt" -> {
            // Ask permission first; the client auto-approves on its reader thread.
            send(out, requestPermission(1000));
            Json response = Json.parse(in.readLine());
            approvedOptionId.set(
                response.path("result").path("outcome").path("optionId").asText(null));
            // Stream one assistant chunk, then end the turn.
            send(out, updateNotification("agent_message_chunk", textContent("working")));
            reply(out, id, new JsonObject().put("stopReason", "end_turn"));
          }
          default -> {
            // session/cancel and anything else: no reply.
          }
        }
      }
    } catch (IOException e) {
      // The client closed stdin — the session is over.
    } finally {
      try {
        out.close(); // EOF to the client's reader so it latches the exit.
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  private void reply(BufferedWriter out, int id, JsonObject result) throws IOException {
    send(out, base().put("id", id).put("result", result));
  }

  private JsonObject requestPermission(int id) {
    JsonArray options =
        new JsonArray()
            .add(new JsonObject().put("optionId", "reject").put("kind", "reject_once"))
            .add(new JsonObject().put("optionId", "allow").put("kind", "allow_once"));
    JsonObject params = new JsonObject().put("sessionId", SESSION).put("options", options);
    return base()
        .put("id", id)
        .put("method", "session/request_permission")
        .put("params", params);
  }

  private JsonObject updateNotification(String sessionUpdate, JsonObject content) {
    JsonObject update =
        new JsonObject().put("sessionUpdate", sessionUpdate).put("content", content);
    JsonObject params = new JsonObject().put("sessionId", SESSION).put("update", update);
    return base().put("method", "session/update").put("params", params);
  }

  private JsonObject textContent(String text) {
    return new JsonObject().put("type", "text").put("text", text);
  }

  private JsonObject base() {
    return new JsonObject().put("jsonrpc", "2.0");
  }

  private void send(BufferedWriter out, JsonObject message) throws IOException {
    out.write(message.encode());
    out.write("\n");
    out.flush();
  }

  private void awaitContains(List<String> lines, String needle) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (lines.stream().anyMatch(l -> l.contains(needle))) {
        return;
      }
      Thread.sleep(20);
    }
    assertNotNull(null, "timed out waiting for a line containing " + needle + ": " + lines);
  }
}
