package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.HealthCheckDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.ServiceDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.WebViewDecl;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
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

  /**
   * Three declared services: a web-viewable dev server that stays up and prints a banner, a plain
   * worker with no web view, and one that fails its first launch so a restart count is observable.
   */
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
              new WebViewDecl(4200, "/index.html", "/app"),
              List.of(
                  new HealthCheckDecl(
                      "up", "HTTP", 4200, "/health", "200", null, null, null, null, null, null))),
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
              List.of()),
          // Fails once, then stays up: the only way to observe a non-zero restart count in a list
          // read, because a service that exhausts its restarts leaves `running` and reads as a
          // never-started one again.
          new ServiceDecl(
              "flaky",
              "flaky",
              "crashes once on the way up",
              "if [ -f .flaky-started ]; then echo flaky-up; sleep 60;"
                  + " else touch .flaky-started; exit 1; fi",
              "flaky-up",
              false,
              "ON_FAILURE",
              3,
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
      // Signal every one so no `sleep 60` outlives the test; close() only stops the timer.
      supervisor.signal("web", "KILL");
      supervisor.signal("worker", "KILL");
      supervisor.signal("flaky", "KILL");
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
    assertEquals(3, services.size());
    JsonObject web = services.getJsonObject(0);
    assertEquals("web", web.getString("name"));
    assertEquals("web", web.getString("id"));
    assertEquals("the dev server", web.getString("description"));
    // Absent, not missing: the caller's next move is to start it, and no entry would read as "no
    // such service".
    assertEquals(ServiceTransition.State.STOPPED, web.getString("state"));
    // Zero rather than omitted, for the same reason the state is STOPPED rather than absent.
    assertEquals(0, web.getInteger("restartCount"));
  }

  @Test
  void aWebViewableServiceCarriesItsDeclarationAndTheDerivedFlag() throws Exception {
    JsonArray services = get("/services").body().getJsonArray("services");

    JsonObject web = services.getJsonObject(0);
    assertEquals(true, web.getBoolean("webViewable"));
    JsonObject webView = web.getJsonObject("webView");
    assertEquals(4200, webView.getInteger("port"));
    assertEquals("/index.html", webView.getString("entryPath"));
    assertEquals("/app", webView.getString("basePath"));

    // The flag is always present so the web view's picker is a filter, not an inference over an
    // omitted key; the declaration itself is omitted when there is none.
    JsonObject worker = services.getJsonObject(1);
    assertEquals(false, worker.getBoolean("webViewable"));
    assertNull(worker.getJsonObject("webView"));
  }

  @Test
  void theListCarriesNoHealthResultsAndNoDegradedState() throws Exception {
    // `web` declares a health check and the daemon parses it, but nothing here runs it — there is
    // no prober in this daemon and none on the host either. A `health` key would read as a verdict
    // the daemon has not formed. DEGRADED is out for the harder reason: it was derived host-side
    // from per-line log observers that were deleted, and re-minting it here resurrects them.
    JsonObject web = get("/services").body().getJsonArray("services").getJsonObject(0);
    assertNull(web.getJsonArray("health"));
    assertNull(web.getJsonArray("healthChecks"));
    for (Object entry : get("/services").body().getJsonArray("services")) {
      assertNotEquals("DEGRADED", ((JsonObject) entry).getString("state"));
    }
  }

  @Test
  void aServiceThatCrashedOnTheWayUpReportsHowOftenItWasRelaunched() throws Exception {
    assertEquals(202, post("/services/flaky/start", null).status());

    // The first launch exits 1 and the policy relaunches it; the second stays up and prints its
    // banner. The count is this container's, and it resets with the container like everything else
    // the daemon holds.
    awaitState("flaky", ServiceTransition.State.READY);
    for (Object entry : get("/services").body().getJsonArray("services")) {
      JsonObject service = (JsonObject) entry;
      if ("flaky".equals(service.getString("name"))) {
        assertEquals(1, service.getInteger("restartCount"));
        return;
      }
    }
    throw new AssertionError("flaky was not listed");
  }

  @Test
  void aWriteIsAcknowledgedRatherThanAnswered() throws Exception {
    // 202 with a shape, not an empty body: the outcome rides the control socket, and giving the
    // acknowledgement a body keeps a client's JSON parse unconditional across every route here.
    assertEquals(true, post("/services/worker/signal", null).body().getBoolean("accepted"));
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
                .put("script", "echo ad-hoc-up on $PORT; sleep 60")
                .put("env", new JsonObject().put("PORT", 8080)));
    assertEquals(202, answer.status());

    awaitState("adhoc", ServiceTransition.State.READY);
    // The overlay reached the child's environment. A JSON number is coerced with String.valueOf,
    // because a .qits-config.yml-shaped body writing `PORT: 8080` means the number.
    assertTrue(
        emitted.stream()
            .anyMatch(m -> m instanceof CommandChunk c && c.text().contains("ad-hoc-up on 8080")),
        "expected the overlaid PORT in the service's output, got: " + emitted);
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
  void theSignalMayComeFromTheBodyWhenThereIsNoQueryParameter() throws Exception {
    post("/services/worker/start", null);
    awaitState("worker", ServiceTransition.State.READY);

    // The query parameter wins where both are given, so a signal can be sent with no body at all;
    // the body form is what a client with a JSON-only transport uses.
    assertEquals(
        202, post("/services/worker/signal", new JsonObject().put("signal", "TERM")).status());
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
