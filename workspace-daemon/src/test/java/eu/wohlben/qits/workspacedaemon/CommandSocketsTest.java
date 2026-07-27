package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.commands.ActionResolver;
import eu.wohlben.qits.workspacedaemon.commands.CommandLifecycleService;
import eu.wohlben.qits.workspacedaemon.commands.CommandLogService;
import eu.wohlben.qits.workspacedaemon.commands.CommandRegistry;
import eu.wohlben.qits.workspacedaemon.commands.CommandService;
import eu.wohlben.qits.workspacedaemon.commands.CommandStore;
import eu.wohlben.qits.workspacedaemon.commands.WorkspaceContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The terminal and chat websockets over a real socket against a real PTY.
 *
 * <p>Worth the real socket rather than a seam: this is the one piece of the move with no host-side
 * counterpart still running beside it, and the parts most likely to break — the handshake auth, the
 * command-id parsing, and whether a keystroke actually reaches the PTY — only exist end to end.
 */
@EnabledOnOs(OS.LINUX)
class CommandSocketsTest {

  private static final String TOKEN = "s3cret-workspace-token";

  @TempDir Path root;

  private Vertx vertx;
  private HttpClient client;
  private WorkspaceApi api;
  private int port;
  private CommandService commands;

  private static final WorkspaceContext WORKSPACE =
      new WorkspaceContext() {
        @Override
        public String repoId() {
          return "repo-42";
        }

        @Override
        public String workspaceId() {
          return "feature-x";
        }

        @Override
        public String branch() {
          return "feature/x";
        }

        @Override
        public String commitHash() {
          return "0123456";
        }
      };

  private record DeclaredActions(List<ResolvedAction> declared) implements ActionResolver {
    @Override
    public Optional<ResolvedAction> resolve(String actionId) {
      return declared.stream().filter(action -> action.id().equals(actionId)).findFirst();
    }

    @Override
    public List<ResolvedAction> actions() {
      return declared;
    }
  }

  @BeforeEach
  void startServer() throws Exception {
    vertx = Vertx.vertx();
    api = new WorkspaceApi();
    api.vertx = vertx;
    CommandStore store = new CommandStore();
    CommandRegistry registry = new CommandRegistry(root, 2_000);
    commands =
        new CommandService(
            store,
            registry,
            new CommandLifecycleService(store, null),
            new CommandLogService(store, null),
            WORKSPACE,
            new DeclaredActions(
                List.of(
                    // `cat` echoes whatever is typed straight back, which is the simplest possible
                    // proof that a keystroke crossed the socket and reached the PTY.
                    new ActionResolver.ResolvedAction("echo", "Echo", "cat", true, Map.of()))));
    api.wireCommands(commands, registry, WORKSPACE);
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker-1"));
    port = api.actualPort();
    client = vertx.createHttpClient();
  }

  @AfterEach
  void stopServer() throws Exception {
    api.close();
    if (client != null) {
      client.close();
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  // --- path parsing -----------------------------------------------------------------------------

  @Test
  void socketPathsAreRecognisedAndTheirCommandIdExtracted() {
    assertTrue(CommandSockets.isCommandSocket("/terminal/commands/abc"));
    assertTrue(CommandSockets.isCommandSocket("/chat/commands/abc"));
    assertEquals("abc", CommandSockets.commandIdOf("/terminal/commands/abc"));
    assertEquals("abc", CommandSockets.commandIdOf("/chat/commands/abc"));

    assertNull(CommandSockets.commandIdOf("/terminal/commands/"), "no id at all");
    assertNull(
        CommandSockets.commandIdOf("/terminal/commands/abc/extra"),
        "one segment only — a deeper path is not a command id");
    assertTrue(!CommandSockets.isCommandSocket("/commands"));
    assertTrue(!CommandSockets.isCommandSocket(null));
  }

  // --- the sockets ------------------------------------------------------------------------------

  @Test
  @Timeout(60)
  void aKeystrokeCrossesTheSocketAndComesBackOffThePty() throws Exception {
    String commandId = launch("echo");
    List<String> received = new CopyOnWriteArrayList<>();

    WebSocket socket = connect("/terminal/commands/" + commandId, "Bearer " + TOKEN, received);
    socket.writeTextMessage(new JsonObject().put("type", "data").put("data", "ping\n").encode());

    awaitContains(received, "ping");
    socket.close();
  }

  @Test
  @Timeout(60)
  void aResizeIsAcceptedByTheRunningPty() throws Exception {
    String commandId = launch("echo");

    List<String> received = new CopyOnWriteArrayList<>();
    WebSocket socket = connect("/terminal/commands/" + commandId, "Bearer " + TOKEN, received);
    socket.writeTextMessage(
        new JsonObject().put("type", "resize").put("cols", 120).put("rows", 40).encode());
    // A resize is fire-and-forget over the wire, so the assertion is that the session survives it
    // and still echoes — a botched ioctl would kill the PTY.
    socket.writeTextMessage(new JsonObject().put("type", "data").put("data", "after\n").encode());

    awaitContains(received, "after");
    socket.close();
  }

  @Test
  @Timeout(60)
  void anUnparseableOrUnknownFrameIsIgnoredRatherThanFatal() throws Exception {
    String commandId = launch("echo");
    List<String> received = new CopyOnWriteArrayList<>();

    WebSocket socket = connect("/terminal/commands/" + commandId, "Bearer " + TOKEN, received);
    socket.writeTextMessage("not json at all");
    socket.writeTextMessage(new JsonObject().put("type", "fromANewerClient").encode());
    socket.writeTextMessage(new JsonObject().put("type", "data").put("data", "still here\n").encode());

    awaitContains(received, "still here");
    socket.close();
  }

  @Test
  @Timeout(60)
  void closingASocketDetachesButLeavesTheCommandRunning() throws Exception {
    String commandId = launch("echo");

    WebSocket first = connect("/terminal/commands/" + commandId, "Bearer " + TOKEN);
    first.close();
    Thread.sleep(200);

    assertEquals(
        "RUNNING",
        commands.get(commandId).status().name(),
        "a closed tab must not kill the process — that is what makes a refresh re-attach");

    List<String> received = new CopyOnWriteArrayList<>();
    WebSocket second = connect("/terminal/commands/" + commandId, "Bearer " + TOKEN, received);
    second.writeTextMessage(new JsonObject().put("type", "data").put("data", "again\n").encode());

    awaitContains(received, "again");
    second.close();
  }

  @Test
  @Timeout(60)
  void attachingToAFinishedCommandSaysSoInEachSocketsOwnDialect() throws Exception {
    List<String> terminal = new CopyOnWriteArrayList<>();
    connect("/terminal/commands/never-existed", "Bearer " + TOKEN, terminal);
    awaitContains(terminal, "no longer running");

    List<String> chat = new CopyOnWriteArrayList<>();
    connect("/chat/commands/never-existed", "Bearer " + TOKEN, chat);
    awaitContains(chat, "session_closed");
  }

  @Test
  @Timeout(60)
  void theUpgradeIsRefusedWithoutAValidBearerToken() {
    assertThrows(
        ExecutionException.class,
        () -> connect("/terminal/commands/anything", null),
        "an unauthenticated upgrade must never become a socket");
    assertThrows(
        ExecutionException.class, () -> connect("/terminal/commands/anything", "Bearer wrong"));
  }

  @Test
  @Timeout(60)
  void anUnknownSocketPathIsRefusedAtTheHandshake() {
    assertThrows(
        ExecutionException.class, () -> connect("/terminal/commands/", "Bearer " + TOKEN));
    assertThrows(ExecutionException.class, () -> connect("/nope", "Bearer " + TOKEN));
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String launch(String actionId) {
    String id = commands.launch(actionId).id();
    assertNotNull(id);
    return id;
  }

  private WebSocket connect(String path, String authorization) throws Exception {
    return connect(path, authorization, new CopyOnWriteArrayList<>());
  }

  /**
   * Connects and installs {@code frames} as the message collector <em>inside</em> the connect
   * composition — a refusal frame is written the instant the socket opens, so a handler attached
   * after awaiting the connect can miss it.
   */
  private WebSocket connect(String path, String authorization, List<String> frames)
      throws Exception {
    WebSocketConnectOptions options =
        new WebSocketConnectOptions().setHost("127.0.0.1").setPort(port).setURI(path);
    if (authorization != null) {
      options.addHeader("Authorization", authorization);
    }
    return await(
        client
            .webSocket(options)
            .map(
                socket -> {
                  socket.textMessageHandler(frames::add);
                  return socket;
                }));
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }

  private static void awaitContains(List<String> frames, String needle) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (frames.stream().anyMatch(frame -> frame.contains(needle))) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("timed out waiting for a frame containing '" + needle + "': " + frames);
  }
}
