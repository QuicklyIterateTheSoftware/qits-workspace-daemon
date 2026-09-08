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
 *
 * <p>The same stdin channel also carries the <strong>Remote Control</strong> enable, when a session
 * name is given. It is not a launch flag: {@code --remote-control} parses under {@code --print} but
 * the harness drops it on the headless branch (verified against CLI 2.1.226), and the
 * {@code remoteControlAtStartup} setting is read on the same interactive-only path — the control
 * request below is the one route that attaches a bridge to a stream-json session.
 */
public final class StreamJsonChatProtocol implements ChatProtocol {

  private static final Logger LOG = System.getLogger(StreamJsonChatProtocol.class.getName());

  private final Process process;
  private final String commandId;
  private final String remoteControlName;
  private final BufferedWriter stdin;
  private final Object stdinLock = new Object();

  private volatile ChatWire wire;
  private boolean remoteControlRequested;

  StreamJsonChatProtocol(Process process, String commandId) {
    this(process, commandId, null);
  }

  /**
   * {@code remoteControlName} names the session in a remote list — pass the branch or another label
   * that says which piece of work this is, or null to leave Remote Control off.
   */
  public StreamJsonChatProtocol(Process process, String commandId, String remoteControlName) {
    this.process = process;
    this.commandId = commandId;
    this.remoteControlName =
        remoteControlName == null || remoteControlName.isBlank() ? null : remoteControlName.trim();
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
          enableRemoteControlOnInit(line);
          wire.emit(line);
        }
      }
    } catch (IOException e) {
      LOG.log(Level.DEBUG, () -> "Chat output pump ended for command " + commandId, e);
    } finally {
      onEnd.run();
    }
  }

  /**
   * Asks the harness to attach Remote Control, once, when it announces itself with {@code
   * system/init}. Waiting for init rather than writing at start is what makes the request land: the
   * control channel is only answered once the session is up, and init is the first thing it says.
   * The reply ({@code control_response}) and the bridge's {@code system/bridge_state} events ride
   * the same stdout stream and pass onto the wire like any other line — the frontend renders the
   * conversation kinds it knows and ignores these.
   */
  private void enableRemoteControlOnInit(String line) {
    if (remoteControlName == null || remoteControlRequested) {
      return;
    }
    JsonObject event;
    try {
      event = new JsonObject(line);
    } catch (RuntimeException notJson) {
      return; // A non-JSON line is not the init event; keep waiting.
    }
    if (!"system".equals(event.getString("type")) || !"init".equals(event.getString("subtype"))) {
      return;
    }
    remoteControlRequested = true;
    String request =
        new JsonObject()
            .put("type", "control_request")
            .put("request_id", "qits-remote-control-" + commandId)
            .put(
                "request",
                new JsonObject()
                    .put("subtype", "remote_control")
                    .put("enabled", true)
                    .put("name", remoteControlName))
            .encode();
    // Best effort throughout: a chat whose bridge cannot be raised is still a working chat.
    synchronized (stdinLock) {
      try {
        stdin.write(request);
        stdin.write("\n");
        stdin.flush();
      } catch (IOException e) {
        LOG.log(Level.DEBUG, () -> "Remote Control enable failed for command " + commandId, e);
      }
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
