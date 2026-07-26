package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.detection.DeclaredFramework;
import eu.wohlben.qits.workspacedaemon.files.LocalWorkspaceFiles;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link WorkspaceApi} over a <em>real</em> Vert.x server on an ephemeral port, against a
 * real git repository in a temp directory. Deliberately not a seam test: the value of this class is
 * everything a seam would skip — the routing, the bearer check, the {@link
 * eu.wohlben.qits.workspacedaemon.files.WorkspaceFilesException} status mapping, and the fact that
 * a blocking handler on a worker still writes its answer back onto the event loop.
 *
 * <p>The field-name assertions are the load-bearing ones. The host deserializes these bodies into
 * its existing {@code DetectionDto}/{@code ComponentMapDto}/{@code WorkspaceFileContentDto}/{@code
 * LazyDirDto} records and the SPA consumes those unchanged — so a renamed key is a broken file
 * browser that nothing else in this reactor would notice. They are asserted as literal strings for
 * exactly that reason: a test that read them off the records would rename itself along with the
 * bug.
 */
class WorkspaceApiTest {

  private static final String TOKEN = "s3cret-workspace-token";

  @TempDir Path root;

  /** Stands in for anything outside the workspace — what every escape case aims at. */
  @TempDir Path outside;

  private Vertx vertx;
  private HttpClient client;
  private WorkspaceApi api;
  private int port;

  /** The marker the detection caches key on; mutable so a test could move the tree if it needed. */
  private volatile String marker = "marker-1";

  private List<DeclaredFramework> declared = List.of();

  @BeforeEach
  void startServer() throws Exception {
    LocalWorkspaceFiles files = new LocalWorkspaceFiles(root);
    files.git("init", "--quiet");
    // A commit-free repo is enough: every read path here runs off `ls-files --cached --others`,
    // which sees the working tree.
    Files.writeString(root.resolve("README.md"), "hello\n");

    vertx = Vertx.vertx();
    api = new WorkspaceApi();
    api.vertx = vertx;
    // Port 0: the bind future carries the ephemeral the OS actually handed out, so the suite never
    // races another process for a fixed one.
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN, root, () -> declared, () -> marker));
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
      // Awaited, not fire-and-forget: an un-awaited close leaves the previous test's event loops
      // competing with the next one's for the same cores, which is exactly how an HTTP suite starts
      // failing on timing rather than on behaviour.
      await(vertx.close());
    }
  }

  // --- helpers -------------------------------------------------------------------------------

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }

  /** One request with the valid bearer; returns status + decoded body. */
  private Answer get(String uri) throws Exception {
    return get(uri, "Bearer " + TOKEN);
  }

  /**
   * One request, composed end to end so every step is attached on the event loop. Blocking on the
   * response and only then asking for its {@code body()} would be the natural-looking version and
   * is wrong: Vert.x starts delivering the body as soon as the response head is handled, so a body
   * handler registered later from the test thread races the bytes and intermittently sees a
   * truncated buffer or none at all.
   */
  private Answer get(String uri, String authorization) throws Exception {
    return await(
        client
            .request(HttpMethod.GET, port, "127.0.0.1", uri)
            .compose(
                request -> {
                  if (authorization != null) {
                    request.putHeader("Authorization", authorization);
                  }
                  return request.send();
                })
            .compose(WorkspaceApiTest::answerOf));
  }

  private static Future<Answer> answerOf(HttpClientResponse response) {
    return response.body().map(body -> new Answer(response.statusCode(), new JsonObject(body)));
  }

  private record Answer(int status, JsonObject body) {}

  private void writeAngularComponent() throws Exception {
    Files.createDirectories(root.resolve("web/src/app"));
    Files.writeString(
        root.resolve("web/src/app/greeting.component.ts"),
        """
        import { Component } from '@angular/core';

        @Component({
          selector: 'app-greeting',
          templateUrl: './greeting.component.html',
          styleUrls: ['./greeting.component.css'],
        })
        export class GreetingComponent {}
        """);
    Files.writeString(root.resolve("web/src/app/greeting.component.html"), "<p>hi</p>\n");
    Files.writeString(root.resolve("web/src/app/greeting.component.css"), "p { color: red }\n");
  }

  // --- happy paths ---------------------------------------------------------------------------

  @Test
  void listsTheRootLevelWithTheHostsFieldNames() throws Exception {
    Answer answer = get("/files");

    assertEquals(200, answer.status());
    assertTrue(answer.body().getJsonArray("paths").contains("README.md"));
    // present-and-empty, never absent: the host's record maps a missing list to null
    assertEquals(new JsonArray(), answer.body().getJsonArray("lazyDirs"));
    assertFalse(answer.body().getString("generation").isBlank());
  }

  @Test
  void listsOneLazyDirectoryLevelWithPathAndChildCount() throws Exception {
    Files.writeString(root.resolve(".gitignore"), "node_modules/\n");
    Files.createDirectories(root.resolve("node_modules/pkg"));
    Files.writeString(root.resolve("node_modules/top.js"), "1;\n");
    Files.writeString(root.resolve("node_modules/pkg/index.js"), "2;\n");

    JsonArray lazyDirs = get("/files").body().getJsonArray("lazyDirs");
    JsonObject stub = lazyDirs.getJsonObject(0);
    assertEquals("node_modules", stub.getString("path"));
    assertEquals(2, stub.getInteger("childCount"));
    // href is the host controller's to synthesise — the daemon knows no repository/workspace id
    assertFalse(stub.containsKey("href"));

    Answer level = get("/files?path=node_modules");
    assertEquals(200, level.status());
    assertTrue(level.body().getJsonArray("paths").contains("node_modules/top.js"));
    assertEquals(
        "node_modules/pkg",
        level.body().getJsonArray("lazyDirs").getJsonObject(0).getString("path"));
  }

  @Test
  void readsFileContentWithTheHostsFieldNames() throws Exception {
    Answer answer = get("/files/content?path=README.md");

    assertEquals(200, answer.status());
    assertEquals("README.md", answer.body().getString("path"));
    assertEquals("hello\n", answer.body().getString("content"));
    assertEquals(false, answer.body().getBoolean("binary"));
  }

  @Test
  void reportsABinaryFileWithNoContent() throws Exception {
    Files.write(root.resolve("blob.bin"), new byte[] {1, 2, 0, 3});

    Answer answer = get("/files/content?path=blob.bin");

    assertEquals(200, answer.status());
    assertEquals(true, answer.body().getBoolean("binary"));
    // omitted rather than an explicit null; the host's record reconstructs it as null either way
    assertNull(answer.body().getString("content"));
  }

  @Test
  void servesDetectionWithTheHostsFieldNames() throws Exception {
    Files.writeString(root.resolve("pom.xml"), "<project>quarkus</project>\n");
    Files.createDirectories(root.resolve("src/main/java/app"));
    Files.createDirectories(root.resolve("src/test/java/app"));
    Files.writeString(root.resolve("src/main/java/app/Thing.java"), "class Thing {}\n");
    Files.writeString(root.resolve("src/test/java/app/ThingTest.java"), "class ThingTest {}\n");

    Answer answer = get("/detection");

    assertEquals(200, answer.status());
    JsonObject project = answer.body().getJsonArray("projects").getJsonObject(0);
    assertEquals("", project.getString("root"));
    assertEquals("java-quarkus", project.getString("frameworkId"));
    assertEquals("Java / Quarkus", project.getString("label"));

    JsonObject membership = answer.body().getJsonArray("frameworks").getJsonObject(0);
    assertEquals("java-quarkus", membership.getString("frameworkId"));
    assertEquals("", membership.getString("root"));
    assertEquals("Java / Quarkus", membership.getString("label"));
    assertTrue(membership.getJsonArray("memberPaths").contains("src/main/java/app/Thing.java"));

    JsonObject link = answer.body().getJsonArray("links").getJsonObject(0);
    assertEquals("src/main/java/app/Thing.java", link.getString("path"));
    assertEquals("", link.getString("projectRoot"));
    JsonObject test = link.getJsonArray("tests").getJsonObject(0);
    assertEquals("src/test/java/app/ThingTest.java", test.getString("path"));
    assertEquals(new JsonArray().add("junit"), test.getJsonArray("kinds"));

    assertFalse(answer.body().getString("generation").isBlank());
  }

  @Test
  void detectionHonoursTheDeclaredFrameworkSupplier() throws Exception {
    // Nothing on disk marks this root as anything; the declared hint alone must produce a project,
    // which is what proves the supplier is wired through from the checkout's own config.
    declared = List.of(new DeclaredFramework("docs", "handbook"));

    JsonArray projects = get("/detection").body().getJsonArray("projects");
    assertEquals("handbook", projects.getJsonObject(0).getString("root"));
    assertEquals("docs", projects.getJsonObject(0).getString("frameworkId"));
  }

  @Test
  void servesTheComponentMapWithTheHostsFieldNames() throws Exception {
    writeAngularComponent();

    Answer answer = get("/component-map");

    assertEquals(200, answer.status());
    assertEquals("angular", answer.body().getString("framework"));
    JsonObject component = answer.body().getJsonArray("components").getJsonObject(0);
    assertEquals("GreetingComponent", component.getString("className"));
    assertEquals("web/src/app/greeting.component.ts", component.getString("componentFile"));
    assertEquals("web/src/app/greeting.component.html", component.getString("templateFile"));
    assertEquals(
        new JsonArray().add("web/src/app/greeting.component.css"),
        component.getJsonArray("styleFiles"));
    assertEquals(
        "app-greeting", component.getJsonArray("selectors").getJsonObject(0).getString("element"));
  }

  @Test
  void componentMapOfANonAngularTreeIsEmptyNotAnError() throws Exception {
    Answer answer = get("/component-map");

    assertEquals(200, answer.status());
    assertEquals(new JsonArray(), answer.body().getJsonArray("components"));
  }

  // --- error mapping -------------------------------------------------------------------------

  @Test
  void rejectsPathTraversalWith400() throws Exception {
    Files.writeString(outside.resolve("secret.txt"), "not yours\n");
    Files.createSymbolicLink(root.resolve("escape"), outside);

    // the lexical guards
    assertEquals(400, get("/files/content?path=../etc/passwd").status());
    assertEquals(400, get("/files/content?path=/etc/passwd").status());
    assertEquals(400, get("/files?path=../..").status());
    // a committed symlink named outright — rejected on its lstat type, never dereferenced
    assertEquals(400, get("/files?path=escape").status());
    // the one no lexical check can see: an intermediate symlinked directory, transparently
    // followed by path resolution, so the final segment lstats as an ordinary file
    assertEquals(400, get("/files/content?path=escape/secret.txt").status());
    // .git is off limits even though it is squarely inside the root
    assertEquals(400, get("/files?path=.git").status());
    assertFalse(get("/files/content?path=../etc/passwd").body().getString("message").isBlank());
  }

  @Test
  void missingFileIs404() throws Exception {
    assertEquals(404, get("/files/content?path=nope.txt").status());
    assertEquals(404, get("/files?path=nope").status());
  }

  @Test
  void contentWithoutAPathIs400() throws Exception {
    assertEquals(400, get("/files/content").status());
  }

  @Test
  void unknownEndpointIs404WithAJsonBody() throws Exception {
    Answer answer = get("/nope");
    assertEquals(404, answer.status());
    assertFalse(answer.body().getString("message").isBlank());
  }

  @Test
  void nonGetMethodIs405() throws Exception {
    Answer answer =
        await(
            client
                .request(HttpMethod.POST, port, "127.0.0.1", "/files")
                .compose(
                    request ->
                        request.putHeader("Authorization", "Bearer " + TOKEN).send("{\"x\":1}"))
                .compose(WorkspaceApiTest::answerOf));
    assertEquals(405, answer.status());
  }

  // --- authentication ------------------------------------------------------------------------

  @Test
  void rejectsEveryEndpointWithoutTheToken() throws Exception {
    for (String uri :
        List.of("/files", "/files/content?path=README.md", "/detection", "/component-map")) {
      assertEquals(401, get(uri, null).status(), uri);
      assertEquals(401, get(uri, "Bearer wrong-token").status(), uri);
      assertEquals(401, get(uri, TOKEN).status(), uri); // right secret, no Bearer scheme
    }
  }

  @Test
  void anUnauthorizedCallerLearnsNothingAboutThePath() throws Exception {
    // A missing file and an existing one must be indistinguishable before the token is presented,
    // or the port becomes a file-existence oracle for anything on qits-net.
    assertEquals(
        get("/files/content?path=README.md", null).body(),
        get("/files/content?path=nope.txt", null).body());
  }
}
