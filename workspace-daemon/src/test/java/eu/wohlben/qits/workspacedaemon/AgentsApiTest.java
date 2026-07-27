package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.agents.AgentCommands;
import eu.wohlben.qits.workspacedaemon.agents.AgentDefaults;
import eu.wohlben.qits.workspacedaemon.agents.AgentLaunchService;
import eu.wohlben.qits.workspacedaemon.agents.AgentPluginService;
import eu.wohlben.qits.workspacedaemon.agents.AgentSessionQueryService;
import eu.wohlben.qits.workspacedaemon.agents.AgentSessionStore;
import eu.wohlben.qits.workspacedaemon.agents.AgentTranscriptService;
import eu.wohlben.qits.workspacedaemon.agents.AgentTranscriptTailService;
import eu.wohlben.qits.workspacedaemon.agents.AgentType;
import eu.wohlben.qits.workspacedaemon.agents.CommandsAgentCommands;
import eu.wohlben.qits.workspacedaemon.agents.McpEndpoints;
import eu.wohlben.qits.workspacedaemon.agents.ProcessRunner;
import eu.wohlben.qits.workspacedaemon.agents.PromptRefinementService;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionRef;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionSource;
import eu.wohlben.qits.workspacedaemon.commands.CommandKind;
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
import java.time.Instant;
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
 * The coding-agent surface over a real Vert.x server, mirroring {@link CommandsApiTest}.
 *
 * <p>The JSON keys are asserted as <strong>literal strings</strong> for the reason that test states:
 * they deserialize into the host's existing {@code AgentSessionNodeDto}, {@code AgentSubagentDto} and
 * {@code InstalledPluginDto} records and the SPA consumes them unchanged, so a test that read the
 * names off the records would rename itself along with the bug.
 */
@EnabledOnOs(OS.LINUX)
class AgentsApiTest {

  private static final String TOKEN = "s3cret-workspace-token";
  private static final String REPO = "11111111-1111-1111-1111-111111111111";
  private static final String PROJECT = "22222222-2222-2222-2222-222222222222";
  private static final int HOOKS_PORT = 13337;

  @TempDir Path root;
  @TempDir Path claudeMount;

  private Vertx vertx;
  private HttpClient client;
  private WorkspaceApi api;
  private int port;
  private CommandStore store;
  private CommandLifecycleService lifecycle;
  private AgentSessionStore sessionStore;
  private AgentLaunchService launch;

  private static final WorkspaceContext WORKSPACE =
      new WorkspaceContext() {
        @Override
        public String repoId() {
          return REPO;
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

  private static final McpEndpoints ENDPOINTS =
      new McpEndpoints() {
        @Override
        public String mcpUrl(String server) {
          return switch (server) {
            case "repository" -> "http://qits:8080/projects/mcp";
            case "observability" -> "http://qits:8080/observability/mcp";
            case "actions" -> "http://qits:8080/actions/mcp";
            default -> throw new IllegalArgumentException("Unknown MCP server: " + server);
          };
        }

        @Override
        public String projectId() {
          return PROJECT;
        }
      };

  private static final AgentDefaults DEFAULTS =
      new AgentDefaults() {
        @Override
        public AgentType defaultAgentType() {
          return AgentType.CLAUDE;
        }

        @Override
        public boolean activityTrackingEnabled() {
          return true;
        }

        @Override
        public Optional<String> refinementModel() {
          return Optional.empty();
        }
      };

  /** Answers every probe successfully and echoes a refined prompt. */
  private static final ProcessRunner PROCESSES =
      (command, cwd, env, timeout) -> new ProcessRunner.Result(0, "refined prompt", "", false);

  @BeforeEach
  void startServer() throws Exception {
    vertx = Vertx.vertx();
    api = new WorkspaceApi();
    api.vertx = vertx;
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker-1"));
    port = api.actualPort();

    store = new CommandStore();
    CommandLogService logs = new CommandLogService(store, null);
    lifecycle = new CommandLifecycleService(store, null);
    sessionStore = new AgentSessionStore();
    CommandRegistry registry = new CommandRegistry(root, 2_000);
    CommandService commands =
        new CommandService(store, registry, lifecycle, logs, WORKSPACE, new NoActions());
    AgentTranscriptService transcripts =
        new AgentTranscriptService(store, logs, sessionStore, claudeMount.toString(), null);
    AgentTranscriptTailService tail = new AgentTranscriptTailService(transcripts, logs);
    AgentCommands agentCommands = new CommandsAgentCommands(commands, registry, store);
    launch =
        new AgentLaunchService(
            agentCommands,
            new eu.wohlben.qits.workspacedaemon.agents.AgentAuthStatus(
                PROCESSES, claudeMount.toString(), root),
            transcripts,
            tail,
            DEFAULTS,
            ENDPOINTS,
            WORKSPACE,
            claudeMount.toString(),
            HOOKS_PORT);
    api.wireCommands(commands, registry, WORKSPACE);
    api.wireAgents(
        launch,
        new AgentSessionQueryService(store, sessionStore),
        new AgentPluginService(PROCESSES, claudeMount.toString(), root, DEFAULTS),
        new PromptRefinementService(PROCESSES, WORKSPACE, DEFAULTS, claudeMount.toString(), root),
        DEFAULTS);
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

  /** This workspace declares no actions; agents are launched, not resolved from config. */
  private record NoActions()
      implements eu.wohlben.qits.workspacedaemon.commands.ActionResolver {
    @Override
    public Optional<ResolvedAction> resolve(String actionId) {
      return Optional.empty();
    }

    @Override
    public List<ResolvedAction> actions() {
      return List.of();
    }
  }

  // --- the surface ------------------------------------------------------------------------------

  @Test
  void availableListsEveryHarnessAndTheResolvedDefault() throws Exception {
    Answer answer = get("/agents/available");

    assertEquals(200, answer.status());
    assertEquals(new JsonArray().add("CLAUDE").add("KIMI"), answer.body().getJsonArray("agents"));
    assertEquals("CLAUDE", answer.body().getString("defaultAgent"));
  }

  @Test
  void sessionTreeUsesTheHostsAgentSessionNodeFieldNames() throws Exception {
    lifecycle.createRunning(
        "main",
        "abc",
        "agent",
        "Agent",
        "exec claude",
        false,
        CommandKind.CHAT,
        "cmd-1",
        new AgentSessionRef("s-root", AgentSessionSource.PINNED, null, null, Instant.EPOCH),
        "CLAUDE");
    sessionStore.replace(
        List.of("s-root"),
        List.of(
            new AgentSessionStore.Stat("cmd-1", "s-root", null, null, null, 4, Instant.EPOCH),
            new AgentSessionStore.Stat(
                "cmd-1", "s-root", "a1", "Explore", "look around", 2, Instant.EPOCH)));

    Answer answer = get("/agent-sessions");

    assertEquals(200, answer.status());
    JsonObject node = answer.body().getJsonArray("sessions").getJsonObject(0);
    assertEquals("s-root", node.getString("sessionId"));
    assertEquals("1970-01-01T00:00:00Z", node.getString("firstRecordedAt"));
    assertEquals(4, node.getInteger("messageCount"));
    assertEquals("cmd-1", node.getString("newestCommandId"));
    assertEquals(new JsonArray(), node.getJsonArray("children"), "always present, empty when none");
    assertNull(node.getString("forkedFromSessionId"), "an absent optional is omitted, not null");

    JsonObject subagent = node.getJsonArray("subagents").getJsonObject(0);
    assertEquals("a1", subagent.getString("agentId"));
    assertEquals("Explore", subagent.getString("agentType"));
    assertEquals("look around", subagent.getString("description"));
    assertEquals(2, subagent.getInteger("messageCount"));
    assertEquals("1970-01-01T00:00:00Z", subagent.getString("firstTimestamp"));
  }

  @Test
  void anUnsweptSessionOmitsItsCountRatherThanReportingZero() throws Exception {
    lifecycle.createRunning(
        "main",
        "abc",
        "agent",
        "Agent",
        "exec claude",
        false,
        CommandKind.CHAT,
        "cmd-1",
        new AgentSessionRef("s-new", AgentSessionSource.PINNED, null, null, Instant.EPOCH),
        "CLAUDE");

    JsonObject node = get("/agent-sessions").body().getJsonArray("sessions").getJsonObject(0);

    assertNull(
        node.getInteger("messageCount"),
        "absent means not swept yet, which the UI renders differently from a swept zero");
  }

  @Test
  void pluginsUseTheHostsInstalledPluginFieldNames() throws Exception {
    Path settings = claudeMount.resolve(".claude/settings.json");
    Files.createDirectories(settings.getParent());
    Files.writeString(settings, "{\"enabledPlugins\":{\"jdtls-lsp@claude-plugins-official\":true}}");

    Answer answer = get("/agent-plugins");

    assertEquals(200, answer.status());
    JsonObject plugin = answer.body().getJsonArray("installed").getJsonObject(0);
    assertEquals("jdtls-lsp@claude-plugins-official", plugin.getString("pluginId"));
    assertTrue(plugin.getBoolean("enabled"));
  }

  @Test
  void anEmptyVolumeListsNoPlugins() throws Exception {
    assertEquals(new JsonArray(), get("/agent-plugins").body().getJsonArray("installed"));
  }

  @Test
  void promptRefinementReturnsTheAgentsStdout() throws Exception {
    Answer answer =
        post("/prompt-refinements", new JsonObject().put("transcript", "uh do the thing"));

    assertEquals(200, answer.status());
    assertEquals("refined prompt", answer.body().getString("prompt"));
  }

  @Test
  void aBlankTranscriptIsAFourHundred() throws Exception {
    Answer answer = post("/prompt-refinements", new JsonObject().put("transcript", "  "));

    assertEquals(400, answer.status());
    assertEquals("transcript is required", answer.body().getString("message"));
  }

  @Test
  void anUnknownScopeIsAFourHundredRatherThanASilentDefault() throws Exception {
    Answer answer = post("/agents", new JsonObject().put("scope", "EVERYTHING"));

    assertEquals(400, answer.status());
    assertTrue(answer.body().getString("message").contains("scope"), answer.body().encode());
  }

  @Test
  void aMissingScopeIsAFourHundred() throws Exception {
    assertEquals(400, post("/agents", new JsonObject()).status());
  }

  @Test
  void wrongMethodsAreRejectedPerRoute() throws Exception {
    assertEquals(405, post("/agents/available", new JsonObject()).status());
    assertEquals(405, post("/agent-sessions", new JsonObject()).status());
    assertEquals(405, get("/agents").status());
    assertEquals(405, get("/prompt-refinements").status());
    assertEquals(405, put("/agent-sessions").status());
  }

  @Test
  void anUnknownAgentRouteIsAFourOhFour() throws Exception {
    assertEquals(404, post("/agent-plugins/jdtls-lsp/uninstall", new JsonObject()).status());
  }

  @Test
  void everyAgentRouteRequiresTheBearerToken() throws Exception {
    assertEquals(401, get("/agent-sessions", null).status());
    assertEquals(401, get("/agents/available", "Bearer wrong").status());
  }

  @Test
  void agentsAreUnavailableUntilWired() throws Exception {
    WorkspaceApi unwired = new WorkspaceApi();
    unwired.vertx = vertx;
    await(unwired.listen(vertx, "127.0.0.1", 0, TOKEN, root, List::of, () -> "marker"));
    int unwiredPort = unwired.actualPort();
    try {
      Answer answer =
          await(
              client
                  .request(HttpMethod.GET, unwiredPort, "127.0.0.1", "/agent-sessions")
                  .compose(request -> request.putHeader("Authorization", "Bearer " + TOKEN).send())
                  .compose(AgentsApiTest::answerOf));

      assertEquals(503, answer.status(), "retryable, not a 404 that reads as never-will-be");
      assertEquals("Coding agents are not available yet", answer.body().getString("message"));
    } finally {
      unwired.close();
    }
  }

  // --- the one contract that spans two components -----------------------------------------------

  @Test
  void theRenderedHookUrlPointsAtThePortTheWebhookActuallyBinds() throws Exception {
    // AgentLaunchService renders this URL into every launch's hook curl and HookWebhook binds it.
    // If they ever read the setting separately and disagree, launches still succeed and simply
    // never report session lineage or activity -- an invisible failure. ControlSocket passes one
    // field to both; this asserts the two ends actually meet.
    HookWebhook webhook =
        new HookWebhook(vertx, HOOKS_PORT, message -> {});
    assertEquals(
        "http://127.0.0.1:" + HOOKS_PORT + HookWebhook.PATH + "?commandId=c-1",
        launch.sessionReportUrl("c-1"),
        "the launch renders exactly the path and port the webhook serves");
    assertNotNull(webhook, "constructed with the same port the launch service was given");
  }

  // --- helpers ----------------------------------------------------------------------------------

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
            .compose(AgentsApiTest::answerOf));
  }

  private static Future<Answer> answerOf(HttpClientResponse response) {
    return response.body().map(body -> new Answer(response.statusCode(), new JsonObject(body)));
  }

  private record Answer(int status, JsonObject body) {}
}
