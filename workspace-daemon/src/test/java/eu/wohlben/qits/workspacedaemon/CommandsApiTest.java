package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code /commands} routes over a real Vert.x server on an ephemeral port, launching real
 * processes. The sibling of {@link WorkspaceApiTest} and deliberately the same kind of test: not a
 * seam, but the routing, the bearer check, the method pairing, the status mapping, and the response
 * field names.
 *
 * <p><b>The field-name assertions are the load-bearing ones.</b> These bodies deserialize into the
 * host's existing {@code CommandDto} / {@code CommandLogLineDto} record tree, which the SPA's
 * Commands list and terminal view already consume, so a renamed key is a broken Commands UX that
 * nothing else in this reactor would notice. They are asserted as literal strings for exactly that
 * reason — a test that read them off the records would rename itself along with the bug.
 */
@EnabledOnOs(OS.LINUX)
class CommandsApiTest {

  private static final String TOKEN = "s3cret-workspace-token";

  @TempDir Path root;

  private Vertx vertx;
  private HttpClient client;
  private WorkspaceApi api;
  private int port;

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
          return "0123456789abcdef0123456789abcdef01234567";
        }
      };

  /** Stands in for {@link ConfigActionResolver} over a checkout's {@code .qits-config.yml}. */
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
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker-1"));
    port = api.actualPort();

    CommandStore store = new CommandStore();
    CommandLogService logs = new CommandLogService(store, null);
    CommandRegistry registry = new CommandRegistry(root, 2_000);
    CommandService commands =
        new CommandService(
            store,
            registry,
            new CommandLifecycleService(store, null),
            logs,
            WORKSPACE,
            new DeclaredActions(
                List.of(
                    new ActionResolver.ResolvedAction(
                        "greet", "Greet", "echo hello-from-the-action", false, Map.of()),
                    new ActionResolver.ResolvedAction(
                        "boom", "Boom", "exit 4", false, Map.of()))));
    api.wireCommands(commands, registry, WORKSPACE);
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
  void launchReturnsTheHostsCommandDtoFieldNames() throws Exception {
    Answer launched = post("/commands", new JsonObject().put("actionId", "greet"));

    assertEquals(200, launched.status());
    JsonObject command = launched.body().getJsonObject("command");
    assertNotNull(command, "the host's LaunchCommandRequest.Response wraps the command");
    // Every one of these is a CommandDto component. A missing key decodes to null on the host.
    assertNotNull(command.getString("id"));
    assertEquals("repo-42", command.getString("repoId"), "synthesized from the workspace context");
    assertEquals("feature-x", command.getString("workspaceId"));
    assertEquals("feature/x", command.getString("branch"));
    assertEquals("0123456789abcdef0123456789abcdef01234567", command.getString("commitHash"));
    assertEquals("0123456", command.getString("shortCommitHash"), "the host computed this in its mapper");
    assertEquals("greet", command.getString("actionId"));
    assertEquals("Greet", command.getString("actionName"));
    assertEquals("RUNNING", command.getString("status"));
    assertEquals(false, command.getBoolean("interactive"));
    assertEquals("TERMINAL", command.getString("kind"));
    assertNotNull(command.getString("launchedAt"));
    assertEquals(new JsonArray(), command.getJsonArray("agentSessions"));
  }

  @Test
  void aFinishedCommandReportsItsExitCodeAndOutput() throws Exception {
    String id = launchAndAwait("boom");

    Answer fetched = get("/commands/" + id);
    assertEquals(200, fetched.status());
    assertEquals("EXITED", fetched.body().getString("status"));
    assertEquals(4, fetched.body().getInteger("exitCode"));
    assertNotNull(fetched.body().getString("finishedAt"));
  }

  @Test
  void theLogCarriesTheHostsLogLineFieldNames() throws Exception {
    String id = launchAndAwait("greet");

    Answer log = get("/commands/" + id + "/log");
    assertEquals(200, log.status());
    JsonArray lines = log.body().getJsonArray("lines");
    assertTrue(lines.size() > 0, "the action printed a line");
    JsonObject first = lines.getJsonObject(0);
    assertNotNull(first.getLong("sequence"));
    assertEquals("OUTPUT", first.getString("channel"));
    assertNotNull(first.getString("content"));
    assertNotNull(first.getString("timestamp"));
    assertTrue(
        lines.stream()
            .anyMatch(l -> ((JsonObject) l).getString("content").contains("hello-from-the-action")),
        "expected the action's output, got: " + lines.encode());
  }

  @Test
  void listWrapsEachCommandInTheHostsEntryEnvelope() throws Exception {
    launchAndAwait("greet");

    Answer listed = get("/commands");
    assertEquals(200, listed.status());
    JsonArray entries = listed.body().getJsonArray("entries");
    assertEquals(1, entries.size());
    assertNotNull(entries.getJsonObject(0).getJsonObject("command"), "entries[].command envelope");
  }

  @Test
  void listNarrowsByStatus() throws Exception {
    launchAndAwait("greet");

    assertEquals(1, get("/commands?status=EXITED").body().getJsonArray("entries").size());
    assertEquals(0, get("/commands?status=RUNNING").body().getJsonArray("entries").size());
  }

  @Test
  void anInvalidStatusIsARequestErrorRatherThanASilentlyWiderFilter() throws Exception {
    Answer answer = get("/commands?status=NOT-A-STATUS");
    assertEquals(400, answer.status());
    assertTrue(answer.body().getString("message").contains("status"));
  }

  @Test
  void theDeclaredActionsAreListable() throws Exception {
    Answer answer = get("/commands/actions");
    assertEquals(200, answer.status());
    JsonArray actions = answer.body().getJsonArray("actions");
    assertEquals(2, actions.size());
    assertEquals("greet", actions.getJsonObject(0).getString("id"));
    assertEquals("Greet", actions.getJsonObject(0).getString("name"));
  }

  @Test
  void anUndeclaredActionIs400() throws Exception {
    Answer answer = post("/commands", new JsonObject().put("actionId", "no-such-action"));
    assertEquals(400, answer.status());
    assertTrue(answer.body().getString("message").contains(".qits-config.yml"));
  }

  @Test
  void aMissingActionIdIs400() throws Exception {
    Answer answer = post("/commands", new JsonObject());
    assertEquals(400, answer.status());
    assertEquals("actionId is required", answer.body().getString("message"));
  }

  @Test
  void anUnknownCommandIs404() throws Exception {
    assertEquals(404, get("/commands/does-not-exist").status());
  }

  @Test
  void anUnknownVerbIs404() throws Exception {
    assertEquals(404, get("/commands/whatever/nonsense").status());
  }

  @Test
  void launchIsPostOnly() throws Exception {
    // The pairing check keeps a GET from ever spawning a process — the same discipline the
    // fast-forward and update-from-parent routes have.
    assertEquals(405, put("/commands").status());
  }

  @Test
  void terminateIsPostOnly() throws Exception {
    String id = launchAndAwait("greet");
    assertEquals(405, get("/commands/" + id + "/terminate").status());
  }

  @Test
  void everyCommandRouteRequiresTheBearer() throws Exception {
    assertEquals(401, get("/commands", null).status());
    assertEquals(401, get("/commands", "Bearer wrong-token").status());
    assertEquals(401, get("/commands/anything", "Basic " + TOKEN).status());
  }

  @Test
  void commandsAreUnavailableUntilWired() throws Exception {
    // A second server with no wireCommands call: a retryable 503, not a 404 that would read as
    // "this daemon will never serve commands".
    WorkspaceApi unwired = new WorkspaceApi();
    unwired.vertx = vertx;
    await(unwired.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker-1"));
    try {
      Answer answer =
          await(
              client
                  .request(HttpMethod.GET, unwired.actualPort(), "127.0.0.1", "/commands")
                  .compose(request -> request.putHeader("Authorization", "Bearer " + TOKEN).send())
                  .compose(CommandsApiTest::answerOf));
      assertEquals(503, answer.status());
    } finally {
      unwired.close();
    }
  }

  // --- helpers -------------------------------------------------------------------------------

  /** Launch an action and poll until it leaves RUNNING; returns the command id. */
  private String launchAndAwait(String actionId) throws Exception {
    String id =
        post("/commands", new JsonObject().put("actionId", actionId))
            .body()
            .getJsonObject("command")
            .getString("id");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (!"RUNNING".equals(get("/commands/" + id).body().getString("status"))) {
        return id;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("command " + id + " never finished");
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

  private Answer put(String uri) throws Exception {
    return send(HttpMethod.PUT, uri, "Bearer " + TOKEN, null);
  }

  private Answer post(String uri, JsonObject body) throws Exception {
    return send(HttpMethod.POST, uri, "Bearer " + TOKEN, body);
  }

  /**
   * Composed end to end so every step attaches on the event loop — blocking on the response and
   * only then asking for its body races the bytes, as {@link WorkspaceApiTest} documents.
   */
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
            .compose(CommandsApiTest::answerOf));
  }

  private static Future<Answer> answerOf(HttpClientResponse response) {
    return response.body().map(body -> new Answer(response.statusCode(), new JsonObject(body)));
  }

  private record Answer(int status, JsonObject body) {}
}
