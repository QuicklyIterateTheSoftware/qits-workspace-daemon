package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.BootstrapDecl;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapOutcome;
import eu.wohlben.qits.workspacedaemon.protocol.Bootstrapped;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code /bootstrap-commands} routes over a real Vert.x server running real {@code bash} steps
 * — the sibling of {@link CommandsApiTest} and {@link ServicesApiTest}.
 *
 * <p>The thing worth testing here is that the route is an <em>acknowledgement</em>, not a report:
 * the run is bounded only by the step timeout (an hour by default), so the response comes back
 * immediately and the outcome arrives on the control plane as the same {@code BootstrapStep} /
 * {@code BootstrapOutcome} / {@code Bootstrapped} sequence the autonomous boot run emits. Every
 * assertion about what actually happened is therefore made against the emitted messages, not the
 * body.
 */
@EnabledOnOs(OS.LINUX)
class BootstrapApiTest {

  private static final String TOKEN = "s3cret-workspace-token";

  @TempDir Path root;

  private Vertx vertx;
  private HttpClient client;
  private WorkspaceApi api;
  private int port;

  private final List<DaemonMessage> emitted = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startServer() throws Exception {
    vertx = Vertx.vertx();
    api = new WorkspaceApi();
    api.vertx = vertx;
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker-1"));
    port = api.actualPort();

    List<BootstrapDecl> chain =
        List.of(
            new BootstrapDecl(
                "install", "install", "install dependencies", "touch installed", null, Map.of()),
            new BootstrapDecl("migrate", "migrate", "run migrations", "touch migrated", null, Map.of()));
    api.wireBootstrap("feature-x", () -> chain, root.toFile(), 30_000, emitted::add);
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

  @Test
  void theDeclaredChainIsListableInOrder() throws Exception {
    Answer answer = get("/bootstrap-commands");

    assertEquals(200, answer.status());
    JsonArray steps = answer.body().getJsonArray("steps");
    assertEquals(2, steps.size());
    assertEquals("install", steps.getJsonObject(0).getString("name"));
    assertEquals("install", steps.getJsonObject(0).getString("id"));
    assertEquals("install dependencies", steps.getJsonObject(0).getString("description"));
    assertEquals("migrate", steps.getJsonObject(1).getString("name"), "the chain keeps its order");
  }

  @Test
  void theListDoesNotLeakTheScripts() throws Exception {
    // They come from an untrusted checkout, and naming a step is all this list is for.
    JsonObject step = get("/bootstrap-commands").body().getJsonArray("steps").getJsonObject(0);
    assertNull(step.getString("execute"));
    assertNull(step.getString("check"));
  }

  @Test
  void runningTheChainIsAcknowledgedImmediatelyAndReportedOnTheControlPlane() throws Exception {
    Answer answer = post("/bootstrap-commands/run");

    assertEquals(202, answer.status());
    assertEquals(true, answer.body().getBoolean("accepted"));

    awaitBootstrapped();
    assertTrue(Files.exists(root.resolve("installed")), "the first step ran");
    assertTrue(Files.exists(root.resolve("migrated")), "and so did the second");
    assertTrue(
        emitted.stream()
            .anyMatch(
                m ->
                    m instanceof BootstrapOutcome o
                        && "install".equals(o.name())
                        && BootstrapOutcome.Result.SUCCEEDED.equals(o.outcome())),
        "expected an install SUCCEEDED outcome, got: " + emitted);
  }

  @Test
  void oneNamedStepRunsAlone() throws Exception {
    assertEquals(202, post("/bootstrap-commands/migrate/run").status());

    awaitBootstrapped();
    assertTrue(Files.exists(root.resolve("migrated")), "the named step ran");
    assertTrue(Files.notExists(root.resolve("installed")), "and only it");
  }

  @Test
  void anUnknownStepRunsNothingAndStillTerminates() throws Exception {
    // BootstrapRunner filters by name and emits its terminal Bootstrapped for an empty selection,
    // so a host awaiting one is not left hanging by a typo.
    assertEquals(202, post("/bootstrap-commands/no-such-step/run").status());

    awaitBootstrapped();
    assertTrue(Files.notExists(root.resolve("installed")));
    assertTrue(Files.notExists(root.resolve("migrated")));
  }

  @Test
  void listingIsGetOnlyAndRunningIsPostOnly() throws Exception {
    assertEquals(405, post("/bootstrap-commands").status());
    assertEquals(405, get("/bootstrap-commands/run").status());
  }

  @Test
  void anUnknownVerbIs404() throws Exception {
    assertEquals(404, post("/bootstrap-commands/install/nonsense").status());
  }

  @Test
  void everyBootstrapRouteRequiresTheBearer() throws Exception {
    assertEquals(401, get("/bootstrap-commands", null).status());
    assertEquals(401, get("/bootstrap-commands", "Bearer wrong-token").status());
    assertEquals(401, send(HttpMethod.POST, "/bootstrap-commands/run", null).status());
  }

  @Test
  void bootstrapIsUnavailableUntilWired() throws Exception {
    WorkspaceApi unwired = new WorkspaceApi();
    unwired.vertx = vertx;
    await(unwired.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker-1"));
    try {
      Answer answer =
          await(
              client
                  .request(HttpMethod.GET, unwired.actualPort(), "127.0.0.1", "/bootstrap-commands")
                  .compose(request -> request.putHeader("Authorization", "Bearer " + TOKEN).send())
                  .compose(BootstrapApiTest::answerOf));
      assertEquals(503, answer.status());
      assertNotNull(answer.body().getString("message"));
    } finally {
      unwired.close();
    }
  }

  // --- helpers -------------------------------------------------------------------------------

  /** Wait for the terminal {@link Bootstrapped} every run ends with, however it went. */
  private void awaitBootstrapped() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (emitted.stream().anyMatch(Bootstrapped.class::isInstance)) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("no terminal Bootstrapped arrived; saw: " + emitted);
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }

  private Answer get(String uri) throws Exception {
    return get(uri, "Bearer " + TOKEN);
  }

  private Answer get(String uri, String authorization) throws Exception {
    return send(HttpMethod.GET, uri, authorization);
  }

  private Answer post(String uri) throws Exception {
    return send(HttpMethod.POST, uri, "Bearer " + TOKEN);
  }

  /** Composed end to end so every step attaches on the event loop; see {@link CommandsApiTest}. */
  private Answer send(HttpMethod method, String uri, String authorization) throws Exception {
    return await(
        client
            .request(method, port, "127.0.0.1", uri)
            .compose(
                request -> {
                  if (authorization != null) {
                    request.putHeader("Authorization", authorization);
                  }
                  return request.send();
                })
            .compose(BootstrapApiTest::answerOf));
  }

  private static Future<Answer> answerOf(HttpClientResponse response) {
    return response.body().map(body -> new Answer(response.statusCode(), new JsonObject(body)));
  }

  private record Answer(int status, JsonObject body) {}
}
