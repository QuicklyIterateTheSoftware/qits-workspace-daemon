package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reverse tunnel over real sockets: a real Vert.x server plays qits' dial-back endpoint, a real
 * server plays {@link WorkspaceApi} on loopback, and the tunnel is driven exactly as {@code
 * ControlSocket} drives it — the same kind of test as {@link CommandSocketsTest}, and for the same
 * reason: the thing under test <em>is</em> the integration.
 *
 * <p>Since the tunnel grew a second target, the servers here are two: the API and the web editor,
 * on separate ephemeral ports. What the target tests prove is that the <em>name</em> picks the port
 * — the host never states one — and that a name this container has no listener for is refused
 * rather than quietly served by the other one.
 *
 * <p>The two cases worth naming are the ones a seam would never have caught. A ≥1 MB body pins the
 * frame-splitting: a WebSocket's {@code write(Buffer)} emits one binary frame of whatever length it
 * is handed, and a {@code NetSocket} read chunk sits at exactly Netty's default maximum frame size,
 * so a large file read would trip the peer's limit and drop the socket — presenting as "the terminal
 * randomly dies" rather than as a framing bug. And a WebSocket upgrade <em>through</em> the tunnel
 * is the whole reason the tunnel carries bytes instead of HTTP requests: not testing it would be not
 * testing the reason.
 */
class DaemonStreamTunnelTest {

  private static final String STREAM_PATH = "/workspaces/daemon/stream/test-nonce";

  private Vertx vertx;

  /** Plays qits: accepts the dial-back and hands the socket to whoever the test wired up. */
  private HttpServer qits;

  /** Plays WorkspaceApi on loopback. */
  private HttpServer api;

  private DaemonStreamTunnel tunnel;

  /** Every path the dial-back endpoint was asked for, so a refusal is provable by absence. */
  private final CopyOnWriteArrayList<String> dialled = new CopyOnWriteArrayList<>();

  private final AtomicReference<ServerWebSocket> lastDialBack =
      new AtomicReference<>();

  private final CompletableFuture<Void> dialBackArrived = new CompletableFuture<>();

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    qits = vertx.createHttpServer();
    qits.webSocketHandler(
        socket -> {
          dialled.add(socket.path());
          lastDialBack.set(socket);
          dialBackArrived.complete(null);
        });
    await(qits.listen(0, "127.0.0.1"));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (tunnel != null) {
      tunnel.close();
    }
    if (api != null) {
      api.close();
    }
    if (editor != null) {
      editor.close();
    }
    if (qits != null) {
      qits.close();
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  /** Plays the supervised web editor on its own loopback port. */
  private HttpServer editor;

  /** Start the tunnel pointed at {@code qits} and at an API server on {@code apiPort}. */
  private void startTunnel(int apiPort) {
    startTunnel(apiPort, 0);
  }

  /** As above, with an editor to serve {@link StreamTarget#EDITOR} on — {@code 0} for none. */
  private void startTunnel(int apiPort, int editorPort) {
    tunnel =
        new DaemonStreamTunnel(
            vertx,
            "ws://127.0.0.1:" + qits.actualPort() + "/workspaces/daemon/7",
            apiPort,
            editorPort);
    tunnel.start();
  }

  @Test
  void anHttpRequestRoundTripsThroughTheTunnel() throws Exception {
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    startTunnel(api.actualPort());

    tunnel.open("test-nonce", STREAM_PATH);
    dialBackArrived.get(15, TimeUnit.SECONDS);
    assertEquals(STREAM_PATH, dialled.getFirst(), "the daemon dials the path it was given");

    // The host end: an ordinary HTTP client, over a socket the daemon opened towards us. That
    // asymmetry is the whole trick — no Vert.x API for "an HttpClient over a socket I supply" is
    // needed, because a loopback listener on the host side turns the tunnel back into an address.
    assertEquals("api:/files?path=src", requestThroughTunnel("/files?path=src"));
  }

  @Test
  void aLargeBodySurvivesFrameSplitting() throws Exception {
    // >1 MB, and deliberately larger than both the 65536 default frame size and the 262144 default
    // maximum *message* size — the second is why the pump reads frames rather than aggregated
    // messages.
    String payload = "x".repeat(1_500_000);
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end(payload));
    await(api.listen(0, "127.0.0.1"));
    startTunnel(api.actualPort());

    tunnel.open("test-nonce", STREAM_PATH);
    dialBackArrived.get(15, TimeUnit.SECONDS);

    String body = requestThroughTunnel("/files/content?path=big");
    assertEquals(payload.length(), body.length(), "the whole body arrived, unsplit and untruncated");
  }

  @Test
  void aWebSocketUpgradeTraversesTheTunnel() throws Exception {
    // The case the byte pipe is bought for: the terminal and chat sockets are upgrades that have to
    // travel through the tunnel themselves. An HTTP-envelope framing would have to special-case
    // this; a byte pipe does not know the difference.
    api = vertx.createHttpServer();
    api.webSocketHandler(
        (ServerWebSocket socket) ->
            socket.textMessageHandler(text -> socket.writeTextMessage("api-echo:" + text)));
    await(api.listen(0, "127.0.0.1"));
    startTunnel(api.actualPort());

    tunnel.open("test-nonce", STREAM_PATH);
    dialBackArrived.get(15, TimeUnit.SECONDS);

    NetServer bridge = hostSideBridge();
    try {
      CompletableFuture<String> reply = new CompletableFuture<>();
      io.vertx.core.http.WebSocketClient client = vertx.createWebSocketClient();
      io.vertx.core.http.WebSocket socket =
          await(client.connect(bridge.actualPort(), "127.0.0.1", "/terminal/commands/abc"));
      socket.textMessageHandler(reply::complete);
      socket.writeTextMessage("{\"type\":\"data\",\"data\":\"k\"}");

      assertEquals(
          "api-echo:{\"type\":\"data\",\"data\":\"k\"}", reply.get(15, TimeUnit.SECONDS));
      client.close();
    } finally {
      bridge.close();
    }
  }

  @Test
  void aPathThatIsNotHostRelativeIsRefusedWithoutDialling() throws Exception {
    startTunnel(1);

    // Every one of these would be an SSRF primitive pointed at whatever the control socket named:
    // the path arrives over a socket that authenticates nobody.
    tunnel.open("n", "https://evil.example/x");
    tunnel.open("n", "//evil.example/x");
    tunnel.open("n", "not-relative");
    tunnel.open("n", null);
    Thread.sleep(300);

    assertTrue(dialled.isEmpty(), "refused paths must not produce a dial: " + dialled);
    assertFalse(dialBackArrived.isDone());
  }

  @Test
  void aStreamWithNoTargetIsServedByTheApi() throws Exception {
    // The compatibility case, at the tunnel rather than at the codec: an OpenStream from a host
    // that predates targets carries none, and must reach the API exactly as it always did. Both
    // spellings of "none" are exercised — the two-argument call and an explicit null.
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    editor = vertx.createHttpServer();
    editor.requestHandler(req -> req.response().end("editor:" + req.uri()));
    await(editor.listen(0, "127.0.0.1"));
    startTunnel(api.actualPort(), editor.actualPort());

    tunnel.open("test-nonce", STREAM_PATH, null);
    dialBackArrived.get(15, TimeUnit.SECONDS);

    assertEquals("api:/files", requestThroughTunnel("/files"));
  }

  @Test
  void aStreamTargetedAtTheEditorReachesTheEditorPort() throws Exception {
    // Two listeners, one tunnel: the target is what picks, and it picks by name — the host never
    // said 13339 and could not have.
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    editor = vertx.createHttpServer();
    editor.requestHandler(req -> req.response().end("editor:" + req.uri()));
    await(editor.listen(0, "127.0.0.1"));
    startTunnel(api.actualPort(), editor.actualPort());

    tunnel.open("test-nonce", STREAM_PATH, StreamTarget.EDITOR);
    dialBackArrived.get(15, TimeUnit.SECONDS);
    assertEquals(STREAM_PATH, dialled.getFirst(), "the dial-back path is the target's business");

    assertEquals("editor:/?folder=/workspace", requestThroughTunnel("/?folder=/workspace"));
  }

  @Test
  void aWebSocketUpgradeTraversesTheTunnelToTheEditor() throws Exception {
    // openvscode-server's whole session — the file tree, the terminals, the extension host — rides
    // one upgrade. If the second target could carry a GET but not an upgrade it would be useless,
    // and the byte pipe is what makes the two indistinguishable.
    editor = vertx.createHttpServer();
    editor.webSocketHandler(
        (ServerWebSocket socket) ->
            socket.textMessageHandler(text -> socket.writeTextMessage("editor-echo:" + text)));
    await(editor.listen(0, "127.0.0.1"));
    startTunnel(freePort(), editor.actualPort());

    tunnel.open("test-nonce", STREAM_PATH, StreamTarget.EDITOR);
    dialBackArrived.get(15, TimeUnit.SECONDS);

    NetServer bridge = hostSideBridge();
    try {
      CompletableFuture<String> reply = new CompletableFuture<>();
      io.vertx.core.http.WebSocketClient client = vertx.createWebSocketClient();
      io.vertx.core.http.WebSocket socket =
          await(client.connect(bridge.actualPort(), "127.0.0.1", "/stable-abc/vscode"));
      socket.textMessageHandler(reply::complete);
      socket.writeTextMessage("hello");

      assertEquals("editor-echo:hello", reply.get(15, TimeUnit.SECONDS));
      client.close();
    } finally {
      bridge.close();
    }
  }

  @Test
  void anEditorStreamIsRefusedWhereNoEditorIsSupervised() throws Exception {
    // The plain-workspace case. The allow-list holds one port here, so EDITOR resolves to nothing
    // and is refused before anything is dialled — it must never fall back to the API, which would
    // answer 404s that read to the browser as a broken editor rather than an absent one.
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    startTunnel(api.actualPort(), 0);

    tunnel.open("test-nonce", STREAM_PATH, StreamTarget.EDITOR);
    Thread.sleep(500);

    assertTrue(dialled.isEmpty(), "a target with no listener must not produce a dial: " + dialled);
    assertFalse(dialBackArrived.isDone());
  }

  @Test
  void aLocalApiThatIsNotListeningNeverDialsBack() throws Exception {
    // The daemon is up but WorkspaceApi has not bound — no token, or not provisioned yet. Because
    // the loopback connection is made first, the failure happens before anything is dialled: the
    // host is not handed a socket that can only disappoint it, and its own parked connection
    // expires on its TTL into an ordinary connection error.
    startTunnel(freePort());

    tunnel.open("test-nonce", STREAM_PATH);
    Thread.sleep(500);

    assertTrue(dialled.isEmpty(), "nothing to serve means nothing to dial: " + dialled);
    assertFalse(dialBackArrived.isDone());
  }

  // --- helpers ------------------------------------------------------------------------------------

  /**
   * The host side of the tunnel, in miniature: a loopback {@link NetServer} whose accepted socket is
   * piped to the dial-back WebSocket. This is exactly what {@code WorkspaceTunnels} does in
   * qits-workspaces — the point being that an ordinary {@code HttpClient} can then target it.
   */
  private NetServer hostSideBridge() throws Exception {
    ServerWebSocket remote = lastDialBack.get();
    NetServer server = vertx.createNetServer();
    server.connectHandler(
        (NetSocket local) -> {
          remote.handler(
              buffer -> {
                local.write(buffer);
                if (local.writeQueueFull()) {
                  remote.pause();
                  local.drainHandler(v -> remote.resume());
                }
              });
          local.handler(
              buffer -> {
                remote.writeBinaryMessage(buffer);
                if (remote.writeQueueFull()) {
                  local.pause();
                  remote.drainHandler(v -> local.resume());
                }
              });
          remote.endHandler(v -> local.close());
          local.endHandler(v -> remote.close());
        });
    await(server.listen(0, "127.0.0.1"));
    return server;
  }

  /** One GET through a freshly bridged tunnel; returns the body. */
  private String requestThroughTunnel(String uri) throws Exception {
    NetServer bridge = hostSideBridge();
    try {
      HttpClient client = vertx.createHttpClient();
      String body =
          await(
              client
                  .request(HttpMethod.GET, bridge.actualPort(), "127.0.0.1", uri)
                  .compose(request -> request.send())
                  .compose(HttpClientResponse::body))
              .toString();
      client.close();
      return body;
    } finally {
      bridge.close();
    }
  }

  private static int freePort() throws Exception {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }
}
