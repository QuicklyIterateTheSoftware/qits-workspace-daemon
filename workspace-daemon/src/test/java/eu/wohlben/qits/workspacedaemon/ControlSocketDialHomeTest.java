package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The dial-home over a real socket against a host that is not ready yet (qits-2294): a real Vert.x
 * server plays qits-workspaces' daemon endpoint and refuses the first five upgrades with a 503, the
 * way the edge answers while it does not yet route a just-started container, and accepts the sixth.
 * The host gives a new container 30 s; the schedule this replaced put the sixth dial at 15.5 s, so
 * a socket inside 15 s is the boot cap at work, not luck.
 *
 * <p>Driven through {@link ControlSocket#dialHome()} rather than {@code start()}, which also wires
 * the editor, the agents, the tunnel and a live provisioner — the seam {@code
 * ControlSocketStopAgentsTest} takes for the same reason. What {@code onConnected} reads beyond the
 * url and the token is null-tolerant, so the socket comes up and announces itself exactly as in the
 * daemon.
 */
class ControlSocketDialHomeTest {

  private static final int REFUSED_UPGRADES = 5;

  private Vertx vertx;

  /** The one context the daemon's dial is started on — see the "Testing" notes in CLAUDE.md. */
  private Context ctx;

  private HttpServer host;

  private final AtomicInteger upgrades = new AtomicInteger();

  private final CompletableFuture<String> firstFrame = new CompletableFuture<>();

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    ctx = vertx.getOrCreateContext();
    host = vertx.createHttpServer();
    host.requestHandler(
        req -> {
          if (upgrades.incrementAndGet() <= REFUSED_UPGRADES) {
            req.response().setStatusCode(503).end();
            return;
          }
          req.toWebSocket().onSuccess(ws -> ws.textMessageHandler(firstFrame::complete));
        });
    await(host.listen(0, "127.0.0.1"));
  }

  @AfterEach
  void tearDown() throws Exception {
    await(vertx.close());
  }

  @Test
  void aHostThatRefusesTheFirstFiveDialsStillGetsItsSocketWellInsideItsWindow() throws Exception {
    ControlSocket daemon = new ControlSocket();
    daemon.vertx = vertx;
    daemon.url =
        Optional.of("ws://127.0.0.1:" + host.actualPort() + "/workspaces/daemon/qits-2294");
    daemon.token = Optional.empty();
    daemon.buildVersionConfig = Optional.empty();
    daemon.buildTimeConfig = Optional.empty();
    daemon.maxBackoffMs = 30_000;

    long started = System.nanoTime();
    ctx.runOnContext(v -> daemon.dialHome());
    String hello = firstFrame.get(15, TimeUnit.SECONDS);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertInstanceOf(Hello.class, DaemonCodec.decode(new JsonObject(hello).getMap()));
    assertEquals(REFUSED_UPGRADES + 1, upgrades.get(), "the sixth dial is the one that lands");
    assertTrue(elapsedMs < 15_000, "socket open after " + elapsedMs + " ms");
  }

  @Test
  void bothDialsGiveUpOnAConnectAfterFiveSeconds() {
    // Vert.x's default is 60 s, twice the host's whole window, for a single unanswered SYN.
    assertEquals(5_000, DaemonDial.clientOptions().getConnectTimeout());
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }
}
