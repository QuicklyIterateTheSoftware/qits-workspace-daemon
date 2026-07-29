package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.ServiceDecl;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.ServiceTransition;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
 * The {@code /services} routes over a real Vert.x server driving a real {@link ServiceSupervisor}
 * over real processes — the sibling of {@link CommandsApiTest}, and the same kind of test.
 *
 * <p>This surface was a host route that was deleted rather than moved when service supervision went
 * into the container, so unlike the commands and agent surfaces it has no field names inherited from
 * a host DTO to preserve. What it does have to hold is the vocabulary: {@code state} is spelled with
 * {@link ServiceTransition.State}'s constants, so a caller reading the list and a caller following
 * the transition stream never see two spellings of one thing. That is what {@link
 * #aDeclaredServiceIsListedAsStoppedBeforeItRuns} and {@link #aStartedServiceReachesReady} assert.
 */
@EnabledOnOs(OS.LINUX)
class ServicesApiTest {

  private static final String TOKEN = "s3cret-workspace-token";

  @TempDir Path root;

  private Vertx vertx;
  private HttpClient client;
  private WorkspaceApi api;
  private ServiceSupervisor supervisor;
  private int port;

  private final List<DaemonMessage> emitted = new CopyOnWriteArrayList<>();

  /** Two declared services: one that stays up and prints a banner, one that is never started. */
  private static final List<ServiceDecl> DECLARED =
      List.of(
          new ServiceDecl(
              "web",
              "web",
              "the dev server",
              "echo listening-on-4200; sleep 60",
              "listening-on",
              false,
              "NEVER",
              0,
              "TERM",
              Map.of(),
              null,
              List.of()),
          new ServiceDecl(
              "worker",
              "worker",
              "the background worker",
              "sleep 60",
              null,
              false,
              "NEVER",
              0,
              "TERM",
              Map.of(),
              null,
              List.of()));

  @BeforeEach
  void startServer() throws Exception {
    vertx = Vertx.vertx();
    api = new WorkspaceApi();
    api.vertx = vertx;
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker-1"));
    port = api.actualPort();

    supervisor =
        new ServiceSupervisor(
            "feature-x",
            root.toFile(),
            emitted::add,
            () -> DECLARED,
            // A short ready grace so the no-readyPattern service settles within the test's patience.
            500,
            50,
            500,
            500);
    api.wireServices(supervisor);
    client = vertx.createHttpClient();
  }

  @AfterEach
  void stopServer() throws Exception {
    if (supervisor != null) {
      // Signal both so no `sleep 60` outlives the test; close() only stops the timer.
      supervisor.signal("web", "KILL");
      supervisor.signal("worker", "KILL");
      supervisor.close();
    }
    api.close();
    if (client != null) {
      client.close();
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  @Test
  void aDeclaredServiceIsListedAsStoppedBeforeItRuns() throws Exception {
    Answer answer = get("/services");

    assertEquals(200, answer.status());
    JsonArray services = answer.body().getJsonArray("services");
    assertEquals(2, services.size());
    JsonObject web = services.getJsonObject(0);
    assertEquals("web", web.getString("name"));
    assertEquals("web", web.getString("id"));
    assertEquals("the dev server", web.getString("description"));
    // Absent, not missing: the caller's next move is to start it, and no entry would read as "no
    // such service".
    assertEquals(ServiceTransition.State.STOPPED, web.getString("state"));
  }

  @Test
  void aStartedServiceReachesReady() throws Exception {
    assertEquals(202, post("/services/web/start", null).status());

    assertEquals(ServiceTransition.State.READY, awaitState("web", ServiceTransition.State.READY));
    // And it said so on the control plane too — the HTTP 202 is an acknowledgement, not the report.
    assertTrue(
        emitted.stream()
            .anyMatch(
                m ->
                    m instanceof ServiceTransition t
                        && "web".equals(t.id())
                        && ServiceTransition.State.READY.equals(t.state())),
        "expected a ServiceTransition READY, got: " + emitted);
  }

  @Test
  void aStartOverridesTheDeclaredScriptWhenAsked() throws Exception {
    // The "try this edit" start: a script the committed config does not carry.
    Answer answer =
        post(
            "/services/adhoc/start",
            new JsonObject()
                .put("script", "echo ad-hoc-up; sleep 60")
                .put("env", new JsonObject().put("PORT", 8080)));
    assertEquals(202, answer.status());

    awaitState("adhoc", ServiceTransition.State.READY);
    // A service supervised without being declared still has to be visible to the caller that
    // started it.
    JsonArray services = get("/services").body().getJsonArray("services");
    assertTrue(
        services.stream().anyMatch(s -> "adhoc".equals(((JsonObject) s).getString("name"))),
        "an undeclared but running service is listed too, got: " + services.encode());
    supervisor.signal("adhoc", "KILL");
  }

  @Test
  void signallingAServiceStopsIt() throws Exception {
    post("/services/worker/start", null);
    awaitState("worker", ServiceTransition.State.READY);

    assertEquals(202, post("/services/worker/signal?signal=TERM", null).status());

    assertEquals(
        ServiceTransition.State.STOPPED, awaitState("worker", ServiceTransition.State.STOPPED));
  }

  @Test
  void signallingAServiceThatIsNotRunningIsStillAccepted() throws Exception {
    // The supervisor has nothing to signal and says so by doing nothing. A 404 here would be a
    // claim about the declared set that this route is not the one to make.
    assertEquals(202, post("/services/worker/signal", null).status());
  }

  @Test
  void listingIsGetOnlyAndTheVerbsArePostOnly() throws Exception {
    assertEquals(405, post("/services", null).status());
    assertEquals(405, get("/services/web/start").status());
    assertEquals(405, get("/services/web/signal").status());
  }

  @Test
  void anUnknownVerbIs404() throws Exception {
    assertEquals(404, post("/services/web/nonsense", null).status());
  }

  @Test
  void everyServiceRouteRequiresTheBearer() throws Exception {
    assertEquals(401, get("/services", null).status());
    assertEquals(401, get("/services", "Bearer wrong-token").status());
    assertEquals(401, send(HttpMethod.POST, "/services/web/start", null, null).status());
  }

  @Test
  void servicesAreUnavailableUntilWired() throws Exception {
    WorkspaceApi unwired = new WorkspaceApi();
    unwired.vertx = vertx;
    await(unwired.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker-1"));
    try {
      Answer answer =
          await(
              client
                  .request(HttpMethod.GET, unwired.actualPort(), "127.0.0.1", "/services")
                  .compose(request -> request.putHeader("Authorization", "Bearer " + TOKEN).send())
                  .compose(ServicesApiTest::answerOf));
      // Retryable, not a 404 that would read as "this daemon will never supervise services".
      assertEquals(503, answer.status());
      assertNotNull(answer.body().getString("message"));
    } finally {
      unwired.close();
    }
  }

  @Test
  void theListDoesNotLeakTheDeclaredStartScript() throws Exception {
    // The scripts come from an untrusted checkout and the caller's use for the list is to name a
    // service, not to read what it will run.
    JsonObject web = get("/services").body().getJsonArray("services").getJsonObject(0);
    assertNull(web.getString("start"));
  }

  // --- helpers -------------------------------------------------------------------------------

  /** Poll {@code GET /services} until {@code name} reaches {@code want}; returns the state seen. */
  private String awaitState(String name, String want) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    String last = null;
    while (System.nanoTime() < deadline) {
      for (Object entry : get("/services").body().getJsonArray("services")) {
        JsonObject service = (JsonObject) entry;
        if (name.equals(service.getString("name"))) {
          last = service.getString("state");
          if (want.equals(last)) {
            return last;
          }
        }
      }
      Thread.sleep(25);
    }
    throw new AssertionError("service " + name + " never reached " + want + " (last: " + last + ")");
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }

  private Answer get(String uri) throws Exception {
    return get(uri, "Bearer " + TOKEN);
  }

  private Answer get(String uri, String authorization) throws Exception {
    return send(HttpMethod.GET, uri, authorization, null);
  }

  private Answer post(String uri, JsonObject body) throws Exception {
    return send(HttpMethod.POST, uri, "Bearer " + TOKEN, body);
  }

  /** Composed end to end so every step attaches on the event loop; see {@link CommandsApiTest}. */
  private Answer send(HttpMethod method, String uri, String authorization, JsonObject body)
      throws Exception {
    return await(
        client
            .request(method, port, "127.0.0.1", uri)
            .compose(
                request -> {
                  if (authorization != null) {
                    request.putHeader("Authorization", authorization);
                  }
                  return body == null ? request.send() : request.send(body.encode());
                })
            .compose(ServicesApiTest::answerOf));
  }

  private static Future<Answer> answerOf(HttpClientResponse response) {
    return response.body().map(body -> new Answer(response.statusCode(), new JsonObject(body)));
  }

  private record Answer(int status, JsonObject body) {}
}
