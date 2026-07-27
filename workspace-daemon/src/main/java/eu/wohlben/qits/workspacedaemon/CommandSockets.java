package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.commands.CommandOutputSink;
import eu.wohlben.qits.workspacedaemon.commands.CommandRegistry;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.ServerWebSocketHandshake;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import org.jboss.logging.Logger;

/**
 * The interactive half of commands: the terminal and chat websockets, served on the daemon's own
 * {@code HttpServer} beside the REST routes.
 *
 * <pre>
 *   WS /terminal/commands/{commandId}
 *   WS /chat/commands/{commandId}
 * </pre>
 *
 * <p><strong>Not over the control socket, deliberately.</strong> The protocol's {@code
 * RunCommand}/{@code CommandChunk}/{@code CommandExit} triple is fire-and-collect — no stdin, no
 * resize — so carrying an interactive terminal over it would mean new message types and a capability
 * bump for something the daemon already has a perfectly good HTTP server for.
 *
 * <p>The browser-facing message shapes are the host sockets' shapes, unchanged, so the SPA's
 * {@code web-terminal.component.ts} and {@code command-chat.component.ts} do not move: terminals send
 * {@code {"type":"data","data":…}} and {@code {"type":"resize","cols":N,"rows":M}}, chats send {@code
 * {"type":"user","text":…}}. Server to client, a terminal is raw PTY text and a chat is
 * newline-delimited JSON envelopes.
 *
 * <p>Closing detaches; it never terminates. That was {@code TerminalSocket}'s behaviour and it is
 * what makes a command survive a browser refresh. (The service terminal <em>did</em> kill on close,
 * but it was deleted rather than moved.)
 */
final class CommandSockets {

  private static final Logger LOG = Logger.getLogger(CommandSockets.class);

  static final String TERMINAL_PREFIX = "/terminal/commands/";
  static final String CHAT_PREFIX = "/chat/commands/";

  /**
   * What a terminal client sees when it attaches to a command that is no longer running: the host's
   * literal bytes, so the browser renders the same yellow line it always did.
   */
  static final String TERMINAL_GONE = "\r\n[33mThis command is no longer running.[0m\r\n";

  /** The chat equivalent — an envelope, because a chat client parses rather than prints. */
  static final String CHAT_GONE = "{\"type\":\"session_closed\"}";

  private CommandSockets() {}

  /** Whether {@code path} is one of the two sockets. */
  static boolean isCommandSocket(String path) {
    return path != null && (path.startsWith(TERMINAL_PREFIX) || path.startsWith(CHAT_PREFIX));
  }

  /** The command id a socket path addresses, or null when the path names none. */
  static String commandIdOf(String path) {
    String rest =
        path.startsWith(TERMINAL_PREFIX)
            ? path.substring(TERMINAL_PREFIX.length())
            : path.substring(CHAT_PREFIX.length());
    // One segment only: /terminal/commands/{id} and nothing after it.
    return rest.isEmpty() || rest.contains("/") ? null : rest;
  }

  /**
   * Authenticate and route the upgrade. Rejecting here rather than in {@link #onWebSocket} means an
   * unauthenticated caller never gets a socket at all, and — as with the REST 401 — learns only that
   * a credential is required, never whether the command exists.
   */
  static void onHandshake(ServerWebSocketHandshake handshake, boolean authorized) {
    if (!authorized) {
      handshake.reject(401);
      return;
    }
    if (!isCommandSocket(handshake.path()) || commandIdOf(handshake.path()) == null) {
      handshake.reject(404);
      return;
    }
    handshake.accept();
  }

  /**
   * Attach {@code socket} to its command. A registry that has no such live command answers false, and
   * the socket is told so in its own dialect and closed — the caller's cue to fall back to the
   * command's log rather than wait forever on a stream that will never speak.
   */
  static void attach(ServerWebSocket socket, CommandRegistry registry) {
    String path = socket.path();
    boolean terminal = path.startsWith(TERMINAL_PREFIX);
    String commandId = commandIdOf(path);
    if (commandId == null) {
      socket.close();
      return;
    }
    CommandOutputSink sink = new WebSocketSink(socket);
    if (!registry.attach(commandId, sink)) {
      // Close only once the refusal has actually gone out. The host awaited its send before
      // closing; closing straight after an async write can truncate the frame, leaving the client
      // with a socket that shut without ever saying why.
      socket
          .writeTextMessage(terminal ? TERMINAL_GONE : CHAT_GONE)
          .onComplete(sent -> socket.close());
      return;
    }
    socket.textMessageHandler(
        message -> {
          if (terminal) {
            onTerminalMessage(registry, commandId, message);
          } else {
            onChatMessage(registry, commandId, message);
          }
        });
    // Detach, never terminate: the process outlives the browser tab, which is what makes a refresh
    // re-attach rather than restart.
    socket.closeHandler(v -> registry.detach(commandId, sink));
    socket.exceptionHandler(t -> registry.detach(commandId, sink));
  }

  private static void onTerminalMessage(
      CommandRegistry registry, String commandId, String message) {
    JsonObject json = parseQuietly(message);
    if (json == null) {
      return;
    }
    switch (json.getString("type", "")) {
      case "data" ->
          registry.input(commandId, json.getString("data", "").getBytes(StandardCharsets.UTF_8));
      // The 80x24 fallbacks are the host socket's, and they are what a client that sends a resize
      // with a missing dimension gets rather than a zero-sized terminal.
      case "resize" ->
          registry.resize(commandId, json.getInteger("cols", 80), json.getInteger("rows", 24));
      default -> {
        // Silently ignored, as the host socket did — an unknown type is a newer client, not a fault.
      }
    }
  }

  private static void onChatMessage(CommandRegistry registry, String commandId, String message) {
    JsonObject json = parseQuietly(message);
    if (json == null) {
      return;
    }
    if ("user".equals(json.getString("type", ""))) {
      registry.chatSend(commandId, json.getString("text", ""));
    }
  }

  /** Same contract as {@code ChatSession.parseQuietly}: null rather than a throw. */
  private static JsonObject parseQuietly(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    try {
      return new JsonObject(message);
    } catch (RuntimeException notJson) {
      LOG.debugf("Dropping an undecodable websocket frame: %s", message);
      return null;
    }
  }

  /**
   * Adapts a websocket to the sink the registry broadcasts through.
   *
   * <p>{@code writeTextMessage} is not awaited: the host's sink blocked on each flush so a slow
   * reader applied backpressure to the PTY pump, but this runs on an event loop where blocking is
   * not an option. Vert.x's own write queue takes the role instead — see {@link #isOpen()}.
   */
  private record WebSocketSink(ServerWebSocket socket) implements CommandOutputSink {

    @Override
    public void write(String data) {
      if (socket.isClosed()) {
        return;
      }
      try {
        socket.writeTextMessage(data);
      } catch (RuntimeException closing) {
        // Raced the close; the closeHandler detaches us either way.
        LOG.debugf("Dropping output for a closing websocket: %s", closing.getMessage());
      }
    }

    @Override
    public boolean isOpen() {
      // A client that has stopped reading fills the write queue; treating that as closed lets the
      // registry drop the sink rather than buffer a terminal's output without bound.
      return !socket.isClosed() && !socket.writeQueueFull();
    }
  }
}
