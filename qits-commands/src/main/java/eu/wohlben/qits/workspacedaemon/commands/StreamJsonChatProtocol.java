package eu.wohlben.qits.workspacedaemon.commands;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;

/**
 * The Claude Code chat transport: the process already speaks the stream-json event envelope on
 * plain pipes, so this protocol is a straight pass-through. Stdout lines are emitted verbatim onto
 * the {@link ChatWire}; a user turn is written to stdin as a stream-json {@code user} message and
 * echoed into the stream as a synthetic {@code {"type":"user","text":…}} line (the same one unified
 * stream the frontend renders).
 *
 * <p>The only thing the move changed is the JSON library — {@code ObjectMapper} to {@link
 * JsonObject}, because the daemon carries no Jackson databind. The envelopes are built explicitly
 * rather than from a map literal, which is a little longer and makes the wire shape readable.
 */
final class StreamJsonChatProtocol implements ChatProtocol {

  private static final Logger LOG = System.getLogger(StreamJsonChatProtocol.class.getName());

  private final Process process;
  private final String commandId;
  private final BufferedWriter stdin;
  private final Object stdinLock = new Object();

  private volatile ChatWire wire;

  StreamJsonChatProtocol(Process process, String commandId) {
    this.process = process;
    this.commandId = commandId;
    this.stdin =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
  }

  @Override
  public void start(ChatWire wire, Runnable onEnd) {
    this.wire = wire;
    Thread reader = new Thread(() -> readLoop(onEnd), "chat-" + commandId);
    reader.setDaemon(true);
    reader.start();
  }

  private void readLoop(Runnable onEnd) {
    try (BufferedReader out =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = out.readLine()) != null) {
        if (!line.isEmpty()) {
          wire.emit(line);
        }
      }
    } catch (IOException e) {
      LOG.log(Level.DEBUG, () -> "Chat output pump ended for command " + commandId, e);
    } finally {
      onEnd.run();
    }
  }

  @Override
  public void sendUser(String text) {
    String turn =
        new JsonObject()
            .put("type", "user")
            .put(
                "message",
                new JsonObject()
                    .put("role", "user")
                    .put(
                        "content",
                        new JsonArray().add(new JsonObject().put("type", "text").put("text", text))))
            .encode();
    synchronized (stdinLock) {
      try {
        stdin.write(turn);
        stdin.write("\n");
        stdin.flush();
      } catch (IOException e) {
        LOG.log(Level.DEBUG, () -> "Chat stdin write failed for command " + commandId, e);
        return;
      }
    }
    ChatWire bound = wire;
    if (bound == null) {
      return; // sendUser before start() bound the wire — unreachable via spawnChat, guarded anyway.
    }
    bound.emit(new JsonObject().put("type", "user").put("text", text).encode());
  }

  @Override
  public void close() {
    try {
      stdin.close();
    } catch (IOException ignored) {
      // best effort
    }
  }
}
