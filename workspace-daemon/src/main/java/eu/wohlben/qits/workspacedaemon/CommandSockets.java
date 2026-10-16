package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.commands.CommandOutputSink;
import eu.wohlben.qits.commands.CommandRegistry;
import io.vertx.core.Future;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.ServerWebSocketHandshake;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
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
 *
 * <p><strong>The command ending ends the attach</strong> (qits-1265). Every socket attached to a live
 * command is closed cleanly, {@code 1000}, once that command's process has exited and its last
 * output has gone out. Both the landing app's sockets and {@code qits workspaces attach} read a
 * clean server close as final and anything else as a cue to reconnect, so without it a client
 * attached when the process ended sat on a silent socket forever. The registry offers no exit
 * callback per sink, so the signal is the command's recorded status: {@link #commandsChanged()} is
 * wired to the lifecycle's change listener, which fires on the reader thread after the process's
 * final chunk was broadcast and the status left {@code RUNNING}.
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

  private final CommandRegistry registry;

  /**
   * Whether a command has ended — its recorded status has left {@code RUNNING}, or it is no longer
   * recorded at all (only finished commands are evicted). Set only after the process's final output
   * was broadcast, which is what makes it safe to close on.
   */
  private final Predicate<String> ended;

  /** The sockets attached to each live command, so its exit can close them. */
  private final Map<String, Set<WebSocketSink>> attached = new ConcurrentHashMap<>();

  CommandSockets(CommandRegistry registry, Predicate<String> ended) {
    this.registry = registry;
    this.ended = ended;
  }

  /**
   * The command list changed: close, with {@code 1000}, every socket whose command has ended.
   *
   * <p>Called on the registry's reader thread from the exit listener, after the final broadcast, so
   * every chunk the process wrote is already queued on the socket ahead of the close. Cheap enough
   * to run on every change: it looks only at the commands that have a socket attached.
   */
  void commandsChanged() {
    for (String commandId : attached.keySet()) {
      if (ended.test(commandId)) {
        Set<WebSocketSink> sinks = attached.remove(commandId);
        if (sinks != null) {
          sinks.forEach(WebSocketSink::closeCleanly);
        }
      }
    }
  }

  /** How many commands have a socket attached; for tests, to prove a close leaves nothing behind. */
  int attachedCommands() {
    return attached.size();
  }

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
   *
   * <p>{@code path} is the route {@link WorkspaceApi} resolved, not {@code handshake.path()}: when
   * the daemon is served under a base path the two differ, and {@code null} means the request was
   * not addressed to this daemon's base at all.
   */
  static void onHandshake(ServerWebSocketHandshake handshake, boolean authorized, String path) {
    if (!authorized) {
      handshake.reject(401);
      return;
    }
    if (path == null || !isCommandSocket(path) || commandIdOf(path) == null) {
      handshake.reject(404);
      return;
    }
    handshake.accept();
  }

  /**
   * Attach {@code socket} to its command. A registry that has no such live command answers false, and
   * the socket is told so in its own dialect and closed — the caller's cue to fall back to the
   * command's log rather than wait forever on a stream that will never speak.
   *
   * <p>{@code path} is the resolved route, as in {@link #onHandshake}. The handshake already
   * rejected anything that does not name a command, so the guards here are belt-and-braces: this
   * method is reachable only through that handshake.
   */
  void attach(ServerWebSocket socket, String path) {
    if (path == null || !isCommandSocket(path)) {
      socket.close();
      return;
    }
    boolean terminal = path.startsWith(TERMINAL_PREFIX);
    String commandId = commandIdOf(path);
    if (commandId == null) {
      socket.close();
      return;
    }
    WebSocketSink sink = new WebSocketSink(socket);
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
    socket.closeHandler(v -> release(commandId, sink));
    socket.exceptionHandler(t -> release(commandId, sink));
    attached.compute(
        commandId,
        (id, sinks) -> {
          Set<WebSocketSink> set = sinks != null ? sinks : ConcurrentHashMap.newKeySet();
          set.add(sink);
          return set;
        });
    // The race: the process may have ended between registry.attach answering true and the line
    // above, in which case commandsChanged() already ran without seeing this socket. The status is
    // written before that sweep, so reading it after registering closes the gap from this side —
    // and a socket attached that late got the full scrollback, final chunk included, as its replay.
    if (ended.test(commandId)) {
      release(commandId, sink);
      sink.closeCleanly();
    }
  }

  /** Forget a socket: detach it from the registry and from the set its command's exit closes. */
  private void release(String commandId, WebSocketSink sink) {
    registry.detach(commandId, sink);
    attached.computeIfPresent(
        commandId,
        (id, sinks) -> {
          sinks.remove(sink);
          return sinks.isEmpty() ? null : sinks;
        });
  }

  private static void onTerminalMessage(
      CommandRegistry registry, String commandId, String message) {
    JsonObject json = parseQuietly(message);
    if (json == null) {
      return;
    }
    switch (json.getString("type", "")) {
      // personInput, not input: these are the person's keystrokes, and only theirs move the draft
      // flag a live interactive rename waits on (AgentLaunchService.setEntity).
      case "data" ->
          registry.personInput(
              commandId, json.getString("data", "").getBytes(StandardCharsets.UTF_8));
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
   *
   * <p>It remembers its last write so {@link #closeCleanly()} can wait for it: the final chunk of
   * output must reach the client before the close does.
   */
  private static final class WebSocketSink implements CommandOutputSink {

    private final ServerWebSocket socket;
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile Future<Void> lastWrite = Future.succeededFuture();

    WebSocketSink(ServerWebSocket socket) {
      this.socket = socket;
    }

    /**
     * Close with {@code 1000} once every write issued so far has gone out, whether it succeeded or
     * not. Idempotent: the exit sweep and the attach-race check may both get here.
     */
    void closeCleanly() {
      if (!closing.compareAndSet(false, true)) {
        return;
      }
      lastWrite.onComplete(
          written -> {
            if (!socket.isClosed()) {
              socket.close((short) 1000);
            }
          });
    }

    @Override
    public void write(String data) {
      if (socket.isClosed()) {
        return;
      }
      try {
        lastWrite = socket.writeTextMessage(data);
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
