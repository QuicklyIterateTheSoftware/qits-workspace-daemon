package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PfxOptions;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

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

  /** The TLS test's stand-in for {@code workspaces.qits.<domain>}. */
  private static final String EDGE_HOST = "workspaces.qits.test";

  private Vertx vertx;

  /**
   * The one context every <em>client</em> call here is issued on; see {@link
   * #requestThroughTunnel(String)}. The clients themselves are created per call rather than in
   * {@code setUp}, so the context is the only thing shared — which is the part that matters.
   */
  private Context ctx;

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

  /** The {@code Authorization} each dial-back carried, {@code ""} for none. */
  private final CopyOnWriteArrayList<String> dialAuthorization = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    ctx = vertx.getOrCreateContext();
    qits = vertx.createHttpServer();
    qits.webSocketHandler(
        socket -> {
          dialled.add(socket.path());
          String authorization = socket.headers().get("Authorization");
          dialAuthorization.add(authorization == null ? "" : authorization);
          lastDialBack.set(socket);
          dialBackArrived.complete(null);
        });
    await(qits.listen(0, "127.0.0.1"));
  }

  /** The service supervisor a SERVICE test runs a real (sleeping) process under, if any. */
  private ServiceSupervisor services;

  @AfterEach
  void tearDown() throws Exception {
    if (services != null) {
      services.signal("frontend", "KILL");
      services.signal("worker", "KILL");
      services.close();
    }
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
      WebSocketClient client = vertx.createWebSocketClient();
      WebSocket socket =
          connectThroughBridge(client, bridge.actualPort(), "/terminal/commands/abc");
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
      WebSocketClient client = vertx.createWebSocketClient();
      WebSocket socket = connectThroughBridge(client, bridge.actualPort(), "/stable-abc/vscode");
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

  @Test
  void theDialBackCarriesTheWorkspaceBearerWhenOneIsSet() throws Exception {
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    tunnel =
        new DaemonStreamTunnel(
            vertx,
            "ws://127.0.0.1:" + qits.actualPort() + "/workspaces/daemon/7",
            Optional.of("Bearer t"),
            api.actualPort(),
            0);
    tunnel.start();

    tunnel.open("test-nonce", STREAM_PATH);
    dialBackArrived.get(15, TimeUnit.SECONDS);

    assertEquals(List.of("Bearer t"), dialAuthorization);
    assertEquals("api:/files", requestThroughTunnel("/files"));
  }

  @Test
  void theDialBackCarriesNoAuthorizationWithoutAToken() throws Exception {
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    startTunnel(api.actualPort());

    tunnel.open("test-nonce", STREAM_PATH);
    dialBackArrived.get(15, TimeUnit.SECONDS);

    assertEquals(List.of(""), dialAuthorization, "a DIRECT workspace's dial-back is nonce only");
  }

  @Test
  void aWssUrlIsDialledOverTlsNotPlain() throws Exception {
    // The plain dial-back server cannot complete a TLS handshake, so a wss url that is honoured
    // never arrives there — where a wss url silently dialled as ws would.
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    tunnel =
        new DaemonStreamTunnel(
            vertx,
            "wss://127.0.0.1:" + qits.actualPort() + "/workspaces/daemon/7",
            Optional.of("Bearer t"),
            api.actualPort(),
            0);
    tunnel.start();

    tunnel.open("test-nonce", STREAM_PATH);
    Thread.sleep(1000);

    assertTrue(dialled.isEmpty(), "a wss dial must not arrive at a plain server: " + dialled);
  }

  @Test
  void bothDialsCompleteATlsHandshakeAgainstATrustedCertificate(@TempDir Path dir)
      throws Exception {
    // The edge in miniature: a TLS server whose self-signed certificate for its host is trusted
    // only through a test trust store installed as the JVM default — the same default trust store
    // the daemon relies on against the edge's public certificate, so nothing about the dial is
    // configured for the test. Host verification is on, so a certificate for any other name would
    // fail the handshake; SNI is read back on the server. The name is dotted on purpose: the JDK
    // sends no SNI for a dotless host such as `localhost`, so the edge's shape needs a real FQDN,
    // which this test's own Vert.x resolves to loopback.
    await(vertx.close());
    qits = null;
    vertx =
        Vertx.vertx(
            new VertxOptions()
                .setAddressResolverOptions(
                    new AddressResolverOptions()
                        .setHostsValue(Buffer.buffer("127.0.0.1 " + EDGE_HOST + "\n"))));
    ctx = vertx.getOrCreateContext();
    Path keyStore = selfSignedEdge(dir);
    String previousStore = System.getProperty("javax.net.ssl.trustStore");
    String previousPassword = System.getProperty("javax.net.ssl.trustStorePassword");
    String previousType = System.getProperty("javax.net.ssl.trustStoreType");
    System.setProperty("javax.net.ssl.trustStore", dir.resolve("trust.p12").toString());
    System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
    System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
    HttpServer edge = null;
    try {
      CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
      CompletableFuture<ServerWebSocket> tunnelDial = new CompletableFuture<>();
      CompletableFuture<Void> controlDial = new CompletableFuture<>();
      edge =
          vertx.createHttpServer(
              new HttpServerOptions()
                  .setSsl(true)
                  .setSni(true)
                  .setKeyCertOptions(
                      new PfxOptions().setPath(keyStore.toString()).setPassword("changeit")));
      edge.requestHandler(
          req -> {
            seen.add(
                req.path()
                    + " sni="
                    + req.connection().indicatedServerName()
                    + " auth="
                    + req.getHeader("Authorization"));
            req.toWebSocket()
                .onSuccess(
                    socket -> {
                      if (req.path().equals(STREAM_PATH)) {
                        tunnelDial.complete(socket);
                      } else {
                        controlDial.complete(null);
                      }
                    });
          });
      await(edge.listen(0, "127.0.0.1"));
      String socketUrl = "wss://" + EDGE_HOST + ":" + edge.actualPort() + "/workspaces/daemon/7";

      // The control socket's dial: its own options, on a client built as ControlSocket builds it.
      WebSocketClient control = vertx.createWebSocketClient(DaemonDial.clientOptions());
      Promise<WebSocket> connected = Promise.promise();
      ctx.runOnContext(
          v ->
              control
                  .connect(
                      ControlSocket.dialOptions(URI.create(socketUrl), Optional.of("Bearer t")))
                  .onComplete(connected));
      await(connected.future());
      controlDial.get(15, TimeUnit.SECONDS);

      // The tunnel's dial-back, through the real tunnel, to the same authority.
      api = vertx.createHttpServer();
      api.requestHandler(req -> req.response().end("api:" + req.uri()));
      await(api.listen(0, "127.0.0.1"));
      tunnel =
          new DaemonStreamTunnel(vertx, socketUrl, Optional.of("Bearer t"), api.actualPort(), 0);
      tunnel.start();
      tunnel.open("test-nonce", STREAM_PATH);
      lastDialBack.set(tunnelDial.get(15, TimeUnit.SECONDS));

      assertEquals("api:/files", requestThroughTunnel("/files"));
      assertEquals(
          List.of(
              "/workspaces/daemon/7 sni=" + EDGE_HOST + " auth=Bearer t",
              STREAM_PATH + " sni=" + EDGE_HOST + " auth=Bearer t"),
          seen);
      control.close();
    } finally {
      if (edge != null) {
        edge.close();
      }
      restore("javax.net.ssl.trustStore", previousStore);
      restore("javax.net.ssl.trustStorePassword", previousPassword);
      restore("javax.net.ssl.trustStoreType", previousType);
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aServiceStreamToARunningWebViewableServiceDialsItsDeclaredPort(@TempDir java.io.File dir)
      throws Exception {
    // The dev server is played by a Vert.x server on an ephemeral port; the supervised process is
    // only there to make the service running. What is proven is that the id picks the port the
    // checkout declared — the host never said it.
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    HttpServer devServer = vertx.createHttpServer();
    devServer.requestHandler(req -> req.response().end("dev:" + req.uri()));
    await(devServer.listen(0, "127.0.0.1"));
    try {
      startServiceTunnel(dir, api.actualPort(), devServer.actualPort());
      services.start("frontend", null, null);

      tunnel.open("test-nonce", STREAM_PATH, StreamTarget.SERVICE, "frontend");
      dialBackArrived.get(15, TimeUnit.SECONDS);

      assertEquals("dev:/index.html", requestThroughTunnel("/index.html"));
    } finally {
      devServer.close();
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aServiceStreamIsRefusedForAnUnknownANonWebOrAStoppedService(@TempDir java.io.File dir)
      throws Exception {
    // Three refusals, none of which may dial and none of which may fall back to the API: an id
    // nothing declares, a running service with no web view, and a web-viewable one not running.
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    startServiceTunnel(dir, api.actualPort(), api.actualPort());
    services.start("worker", null, null);

    tunnel.open("n", STREAM_PATH, StreamTarget.SERVICE, "nobody");
    tunnel.open("n", STREAM_PATH, StreamTarget.SERVICE, "worker");
    tunnel.open("n", STREAM_PATH, StreamTarget.SERVICE, "frontend");
    Thread.sleep(500);

    assertTrue(dialled.isEmpty(), "a refused service stream must not dial: " + dialled);
    assertFalse(dialBackArrived.isDone());
  }

  @Test
  void aServiceStreamIsRefusedWhereNoServicesAreSupervised() throws Exception {
    api = vertx.createHttpServer();
    api.requestHandler(req -> req.response().end("api:" + req.uri()));
    await(api.listen(0, "127.0.0.1"));
    startTunnel(api.actualPort());

    tunnel.open("n", STREAM_PATH, StreamTarget.SERVICE, "frontend");
    Thread.sleep(500);

    assertTrue(dialled.isEmpty(), "no supervisor means no service to dial: " + dialled);
  }

  // --- helpers ------------------------------------------------------------------------------------

  /**
   * A tunnel with a real {@link ServiceSupervisor} behind it, declaring {@code frontend} (web
   * view on {@code webPort}) and {@code worker} (no web view). Neither is started here.
   */
  private void startServiceTunnel(java.io.File dir, int apiPort, int webPort) {
    List<DaemonQitsConfig.ServiceDecl> decls =
        List.of(
            service("frontend", new DaemonQitsConfig.WebViewDecl(webPort, "/", null)),
            service("worker", null));
    services =
        new ServiceSupervisor(
            "ws-1", dir, message -> {}, () -> decls, 60_000, 50, 200, 1000, "/workspaces/service/7");
    tunnel =
        new DaemonStreamTunnel(
            vertx,
            "ws://127.0.0.1:" + qits.actualPort() + "/workspaces/daemon/7",
            Optional.empty(),
            apiPort,
            0,
            services);
    tunnel.start();
  }

  private static DaemonQitsConfig.ServiceDecl service(
      String name, DaemonQitsConfig.WebViewDecl webView) {
    return new DaemonQitsConfig.ServiceDecl(
        name,
        name,
        null,
        "exec sleep 60",
        null,
        false,
        "NEVER",
        0,
        "TERM",
        java.util.Map.of(),
        webView,
        List.of());
  }

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

  /**
   * One GET through a freshly bridged tunnel; returns the body.
   *
   * <p>Issued on {@link #ctx} rather than on the JUnit thread: a {@code client.request} started off
   * a Vert.x context mints a fresh one per call, and the 4.5.26 pool intermittently never leases
   * that waiter a connection — nothing is written and the await expires. See {@link
   * WorkspaceApiTest#get(String, String)}.
   */
  private String requestThroughTunnel(String uri) throws Exception {
    NetServer bridge = hostSideBridge();
    try {
      HttpClient client = vertx.createHttpClient();
      Promise<Buffer> promise = Promise.promise();
      ctx.runOnContext(
          v ->
              client
                  .request(HttpMethod.GET, bridge.actualPort(), "127.0.0.1", uri)
                  .compose(request -> request.send())
                  .compose(HttpClientResponse::body)
                  .onComplete(promise));
      String body = await(promise.future()).toString();
      client.close();
      return body;
    } finally {
      bridge.close();
    }
  }

  /**
   * One WebSocket handshake through a bridged tunnel, pinned to {@link #ctx} for the same reason
   * {@link #requestThroughTunnel(String)} is. Only the initiation moves: the caller still installs
   * the socket's handlers itself, after the await.
   */
  private WebSocket connectThroughBridge(WebSocketClient client, int bridgePort, String path)
      throws Exception {
    Promise<WebSocket> promise = Promise.promise();
    ctx.runOnContext(v -> client.connect(bridgePort, "127.0.0.1", path).onComplete(promise));
    return await(promise.future());
  }

  /**
   * A PKCS12 key store holding a self-signed certificate for {@link #EDGE_HOST}, and beside it
   * ({@code trust.p12}) a trust store holding only that certificate. {@code keytool} rather than a
   * certificate library: it ships with every JDK, and nothing new lands on the test classpath.
   */
  private static Path selfSignedEdge(Path dir) throws Exception {
    Path keyStore = dir.resolve("server.p12");
    Path cert = dir.resolve("server.cer");
    Path trust = dir.resolve("trust.p12");
    keytool(
        "-genkeypair", "-alias", "edge", "-keyalg", "EC", "-groupname", "secp256r1",
        "-dname", "CN=" + EDGE_HOST, "-ext", "san=dns:" + EDGE_HOST, "-validity", "2",
        "-storetype", "PKCS12", "-keystore", keyStore.toString(),
        "-storepass", "changeit", "-keypass", "changeit");
    keytool(
        "-exportcert", "-alias", "edge", "-keystore", keyStore.toString(),
        "-storepass", "changeit", "-file", cert.toString());
    keytool(
        "-importcert", "-noprompt", "-alias", "edge", "-file", cert.toString(),
        "-storetype", "PKCS12", "-keystore", trust.toString(), "-storepass", "changeit");
    assertTrue(Files.exists(trust));
    return keyStore;
  }

  private static void keytool(String... args) throws Exception {
    List<String> command = new java.util.ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), "keytool failed: " + output);
  }

  private static void restore(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
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
