package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.files.LocalWorkspaceFiles;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The daemon served under a base path — what qits-workspaces injects as {@code
 * QITS_WORKSPACE_DAEMON_API_BASE_PATH} so its proxy can forward a caller's path untouched.
 *
 * <p>This is the half of that contract that lives here; the other half is that qits-workspaces
 * injects {@code ContainerProxyPath.base(id)} and rewrites nothing. Neither side can be checked
 * from the other's repo, so each pins its own end — and the value is one literal in one place, so
 * what they have to agree on is the shape, not a string.
 *
 * <p>{@link WorkspaceApiTest} covers the unmounted case (no base configured), which is every direct
 * caller and stayed the default precisely so nothing about a bare daemon changed.
 */
class WorkspaceApiBasePathTest {

  private static final String TOKEN = "s3cret-workspace-token";
  private static final String BASE = "/workspaces/container/1";

  @TempDir Path root;

  private Vertx vertx;
  private HttpClient client;
  private WorkspaceApi api;
  private int port;

  @BeforeEach
  void startServer() throws Exception {
    LocalWorkspaceFiles files = new LocalWorkspaceFiles(root);
    files.git("init", "--quiet");
    Files.writeString(root.resolve("README.md"), "hello\n");

    vertx = Vertx.vertx();
    api = new WorkspaceApi();
    api.vertx = vertx;
    // The injected value carries a trailing slash (ContainerProxyPath.base does), so binding with
    // one here is the real input rather than a tidied version of it.
    api.apiBasePath = java.util.Optional.of(BASE + "/");
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker-1"));
    port = api.actualPort();
    client = vertx.createHttpClient();
  }

  @AfterEach
  void stopServer() throws Exception {
    api.close();
    if (client != null) {
      await(client.close());
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  @Test
  void servesItsRoutesUnderTheConfiguredBase() throws Exception {
    Answer answer = get(BASE + "/files");
    assertEquals(200, answer.status());
    // The real listing rather than a stub 200: the route reached the file browser.
    assertTrue(answer.body().getJsonArray("paths").contains("README.md"));
  }

  @Test
  void theUnmountedPathIsNotServedOnceABaseIsConfigured() throws Exception {
    // The daemon is addressed at its base or not at all. Serving both would mean the proxy and a
    // direct caller disagree about this daemon's address and both be right, which is the ambiguity
    // a configured base exists to remove.
    assertEquals(404, get("/files").status());
  }

  @Test
  void aRequestForADifferentWorkspacesBaseIs404() throws Exception {
    // The guard that matters: /workspaces/container/12 must not match a base of
    // /workspaces/container/1. A plain startsWith would route workspace 12's request into
    // workspace 1's daemon, and on a host running a container per workspace that is a
    // cross-workspace read rather than a miss.
    assertEquals(404, get("/workspaces/container/12/files").status());
    assertEquals(404, get("/workspaces/container/2/files").status());
  }

  @Test
  void theBaseItselfIsA404RatherThanAnError() throws Exception {
    // "/" is a route the daemon does not serve — an ordinary miss, not a crash.
    assertEquals(404, get(BASE).status());
    assertEquals(404, get(BASE + "/").status());
  }

  @Test
  void anUnknownRouteUnderTheBaseIs404() throws Exception {
    assertEquals(404, get(BASE + "/nope").status());
  }

  private Answer get(String uri) throws Exception {
    return await(
        client
            .request(HttpMethod.GET, port, "127.0.0.1", uri)
            .compose(request -> request.putHeader("Authorization", "Bearer " + TOKEN).send())
            .compose(WorkspaceApiBasePathTest::answerOf));
  }

  private static Future<Answer> answerOf(HttpClientResponse response) {
    return response.body().map(body -> new Answer(response.statusCode(), new JsonObject(body)));
  }

  private record Answer(int status, JsonObject body) {}

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }
}
