package eu.wohlben.qits.workspacedaemon;

import static eu.wohlben.qits.workspacedaemon.GitFixtures.CHILD;
import static eu.wohlben.qits.workspacedaemon.GitFixtures.WRAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentConfigurationDocument;
import eu.wohlben.qits.agents.AgentDefaults;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentPluginService;
import eu.wohlben.qits.agents.AgentSessionQueryService;
import eu.wohlben.qits.agents.AgentSessionStore;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentTranscriptService;
import eu.wohlben.qits.agents.AgentTranscriptTailService;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.CommandsAgentCommands;
import eu.wohlben.qits.agents.EntityFacts;
import eu.wohlben.qits.agents.McpEndpoints;
import eu.wohlben.qits.agents.ProcessRunner;
import eu.wohlben.qits.agents.PromptRefinementService;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.AgentSessionSource;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent surface over a real Vert.x server, mirroring {@link CommandsApiTest}: the workspace-wide
 * routes (sign-in, plugins, sessions, refinement) and the agent worktrees (qits-1152).
 *
 * <p>The agent worktrees are real: a wrapper and a submodule with bare origins and a base clone
 * ({@link GitFixtures}). So is the harness process, as far as this daemon can see: a fake {@code
 * claude} on the {@code PATH} of the launch's login shell records its arguments, its working
 * directory, its credential and what it is sent, and stays running like a harness does. That is
 * what lets these tests prove the three things only a real launch shows — the harness runs in the
 * agent's worktree, carries the agent's credential and nothing else, and resumes its session.
 *
 * <p>The JSON keys are asserted as <strong>literal strings</strong>, for the reason {@link
 * CommandsApiTest} states: they are a wire contract with the host, and a test that read the names
 * off the records would rename itself along with the bug.
 */
@EnabledOnOs(OS.LINUX)
class AgentsApiTest {

  private static final String TOKEN = "s3cret-workspace-token";
  private static final String REPO = "11111111-1111-1111-1111-111111111111";
  private static final String PROJECT = "22222222-2222-2222-2222-222222222222";
  private static final int HOOKS_PORT = 13337;
  private static final String IMAGE_VERSION = "2026.909.111643";
  private static final String BRANCH = "ticket/qits-1";

  /**
   * A boot-time capability report, as {@link eu.wohlben.qits.agents.HarnessCapabilityService} would
   * have produced it. Shipped rather than probed, because what is under test here is the wire shape
   * the host caches, not the probe — the probe's own parsing is proven in the library's suite.
   */
  private static final List<eu.wohlben.qits.agents.HarnessCapabilities> CAPABILITIES =
      List.of(
          eu.wohlben.qits.agents.HarnessCapabilities.shipped(AgentType.CLAUDE, "not probed here")
              .withAuth(true, "Signed in on the shared credential volume."),
          eu.wohlben.qits.agents.HarnessCapabilities.shipped(AgentType.KIMI, "not probed here"));

  /** The commands layer's root: the workspace volume. */
  @TempDir Path root;

  @TempDir Path claudeMount;
  @TempDir Path estateDir;

  private Vertx vertx;
  private HttpClient client;

  /** The one context every client call is issued on; see {@code send}. */
  private Context ctx;

  private WorkspaceApi api;
  private int port;
  private CommandStore store;
  private CommandLifecycleService lifecycle;
  private AgentSessionStore sessionStore;

  /** The workspace's own launch service: the sign-in terminal's. */
  private AgentLaunchService launch;

  private AgentWorktrees worktrees;
  private AgentRuntime runtime;

  /** Where the fake harness writes what it saw: {@code <log>.args}, {@code .cwd}, … */
  private Path fakeLog;

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
          return "";
        }

        @Override
        public String commitHash() {
          return "";
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
    GitFixtures.Estate estate = GitFixtures.estate(estateDir);
    worktrees = new AgentWorktrees(estate.base(), estate.agents(), WRAPPER);
    worktrees.installGuards();
    fakeLog = estateDir.resolve("harness");
    installFakeHarness();

    vertx = Vertx.vertx();
    api = new WorkspaceApi();
    api.vertx = vertx;
    await(api.listen(vertx, "127.0.0.1", 0, TOKEN, worktrees, List::of));
    port = api.actualPort();

    store = new CommandStore();
    lifecycle = new CommandLifecycleService(store, null);
    sessionStore = new AgentSessionStore();
    wireWith(PROCESSES, DEFAULTS);
    client = vertx.createHttpClient();
    ctx = vertx.getOrCreateContext();
  }

  @AfterEach
  void stopServer() throws Exception {
    for (AgentRuntime.View view : runtime.list()) {
      if (view.commandId() != null) {
        post("/commands/" + view.commandId() + "/terminate", new JsonObject());
      }
    }
    api.close();
    if (client != null) {
      client.close();
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  /**
   * A {@code claude} that records what a real one would have been given, and then stays up reading
   * its input like a chat harness does. The launch's login shell finds it through {@code
   * ~/.bash_profile}: the library points {@code HOME} at the credential volume, which here is a
   * temporary directory.
   */
  private void installFakeHarness() throws Exception {
    Path bin = Files.createDirectories(claudeMount.resolve("fake-bin"));
    Path claude = bin.resolve("claude");
    Files.writeString(
        claude,
        """
        #!/bin/sh
        printf '%s\\n' "$*" >> "$QITS_FAKE_LOG.args"
        pwd >> "$QITS_FAKE_LOG.cwd"
        printf '%s\\n' "$QITS_TOKEN" >> "$QITS_FAKE_LOG.token"
        printf 'api=%s secret=%s\\n' "${QITS_WORKSPACE_DAEMON_API_TOKEN-unset}" \\
          "${QITS_COMMISSIONED_CLIENT_SECRET-unset}" >> "$QITS_FAKE_LOG.secrets"
        exec cat >> "$QITS_FAKE_LOG.stdin"
        """);
    Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwxr-xr-x"));
    Files.writeString(claudeMount.resolve(".bash_profile"), "PATH=" + bin + ":$PATH\n");
  }

  /**
   * Build the agent surface on {@code processes} and {@code defaults} — the second is how a
   * container "born with a configuration document" is expressed here, since the document reaches a
   * launch through {@code AgentDefaults.surfaceConfigurations()} and nowhere else.
   */
  private void wireWith(ProcessRunner processes, AgentDefaults defaults) {
    CommandLogService logs = new CommandLogService(store, null);
    CommandRegistry registry = new CommandRegistry(root, 2_000);
    CommandService commands =
        new CommandService(store, registry, lifecycle, logs, WORKSPACE, new NoActions());
    AgentTranscriptService transcripts =
        new AgentTranscriptService(store, logs, sessionStore, claudeMount.toString(), null);
    AgentTranscriptTailService tail = new AgentTranscriptTailService(transcripts, logs);
    eu.wohlben.qits.agents.AgentAuthStatus auth =
        new eu.wohlben.qits.agents.AgentAuthStatus(processes, claudeMount.toString(), root);
    WorkspaceMcpServers mcp =
        new WorkspaceMcpServers(ENDPOINTS, REPO, "feature-x", Optional.empty(), Optional.empty());
    CommandsAgentCommands shared = new CommandsAgentCommands(commands, registry, store);
    launch =
        new AgentLaunchService(
            shared, auth, transcripts, tail, defaults, mcp, WORKSPACE, claudeMount.toString(),
            HOOKS_PORT);
    runtime =
        new AgentRuntime(
            worktrees,
            store,
            registry,
            shared,
            seat ->
                new AgentLaunchService(
                    seat.commands(),
                    auth,
                    transcripts,
                    tail,
                    defaults,
                    WorkspaceMcpServers.forAgent(
                        ENDPOINTS, REPO, "feature-x", Optional.empty(), seat),
                    WORKSPACE,
                    claudeMount.toString(),
                    HOOKS_PORT),
            claudeMount.toString());
    api.wireCommands(commands, registry, WORKSPACE);
    api.wireAgents(
        runtime,
        launch,
        new AgentSessionQueryService(store, sessionStore),
        new AgentPluginService(processes, claudeMount.toString(), root, defaults),
        new PromptRefinementService(processes, WORKSPACE, defaults, claudeMount.toString(), root),
        defaults,
        IMAGE_VERSION,
        () -> CAPABILITIES);
  }

  /**
   * A runner on whose volume nobody has signed in: {@code claude auth status} fails and the kimi
   * credential probe finds nothing.
   */
  private static ProcessRunner signedOut() {
    return (command, cwd, env, timeout) ->
        new ProcessRunner.Result(1, "", "Not logged in", false);
  }

  /** The one catalog entry these tests attach, and the credential nothing may ever serve. */
  private static final String EXTERNAL_URL = "https://mcp.stripe.example/v1";

  private static final String EXTERNAL_HEADER_NAME = "Authorization";

  private static final String EXTERNAL_HEADER_VALUE = "Bearer sk-live-nobody-may-see-this";

  private static final JsonObject EXTERNAL_SERVER =
      new JsonObject()
          .put("key", "stripe")
          .put("url", EXTERNAL_URL)
          .put("headerName", EXTERNAL_HEADER_NAME)
          .put("headerValue", EXTERNAL_HEADER_VALUE)
          .put("allowedTools", new JsonArray().add("mcp__stripe__list_charges"));

  /**
   * The configuration document a container is born with, as qits-projects writes it — parsed the
   * way a mounted file is rather than built as records, so the field names the service writes are
   * the ones exercised here.
   */
  private static AgentSurfaceConfigurations document(JsonObject... externalServers) {
    JsonArray attached = new JsonArray();
    for (JsonObject server : externalServers) {
      attached.add(server);
    }
    JsonObject surface =
        new JsonObject()
            .put(
                "configuration",
                new JsonObject()
                    .put("surface", "workspace.chat")
                    .put("harness", "CLAUDE")
                    // Deliberately not the defaults: a record that echoed the shipped values would
                    // pass whether or not it read the configuration at all.
                    .put("model", "opus")
                    .put("effort", "high")
                    .put("remoteControl", false)
                    .put("permissionMode", "SKIP_PERMISSIONS")
                    .put("activityTracking", true)
                    .put("systemPrompt", "")
                    .put("initialPrompt", "")
                    .put(
                        "mcpServers",
                        new JsonArray()
                            .add(
                                new JsonObject()
                                    .put("server", "repository")
                                    .put("narrowProject", false)
                                    .put("narrowRepository", true)
                                    .put("narrowWorkspace", true)
                                    .put("readOnly", false)
                                    .put("allowedTools", new JsonArray()))))
            .put("externalMcpServers", attached);
    String raw =
        new JsonObject()
            .put("version", AgentConfigurationDocument.CURRENT_VERSION)
            .put("generatedAt", "2026-09-09T10:00:00Z")
            .put("surfaces", new JsonArray().add(surface))
            .encode();
    return AgentSurfaceConfigurations.of(
        AgentConfigurationDocument.parse(raw, "agents-api-test-document"));
  }

  /** {@link #DEFAULTS}, for a container created with {@code configurations} mounted. */
  private static AgentDefaults bornWith(AgentSurfaceConfigurations configurations) {
    return new AgentDefaults() {
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

      @Override
      public AgentSurfaceConfigurations surfaceConfigurations() {
        return configurations;
      }
    };
  }

  /** This workspace declares no actions; agents are launched, not resolved from config. */
  private record NoActions() implements eu.wohlben.qits.commands.ActionResolver {
    @Override
    public Optional<ResolvedAction> resolve(String actionId) {
      return Optional.empty();
    }

    @Override
    public List<ResolvedAction> actions() {
      return List.of();
    }
  }

  // --- the workspace-wide surface ---------------------------------------------------------------

  @Test
  void availableListsEveryHarnessAndTheResolvedDefault() throws Exception {
    Answer answer = get("/agents/available");

    assertEquals(200, answer.status());
    assertEquals(new JsonArray().add("CLAUDE").add("KIMI"), answer.body().getJsonArray("agents"));
    assertEquals("CLAUDE", answer.body().getString("defaultAgent"));
  }

  @Test
  void availableCarriesTheBootTimeCapabilityReportAndWhatRanIt() throws Exception {
    Answer answer = get("/agents/available");

    assertEquals(200, answer.status());
    // The host caches the report keyed by (harness, imageVersion), so both halves of that key have
    // to be on the wire; reportedBy is display only, and says which container answered when a
    // project's agent container and a workspace one disagree.
    assertEquals(IMAGE_VERSION, answer.body().getString("imageVersion"));
    assertEquals("qits-workspace-daemon", answer.body().getString("reportedBy"));

    JsonArray capabilities = answer.body().getJsonArray("capabilities");
    assertEquals(2, capabilities.size(), "one report per harness");
    JsonObject claude = capabilities.getJsonObject(0);
    assertEquals("CLAUDE", claude.getString("harness"));
    // Claude has an effort concept and no way to enumerate models; Kimi is the exact opposite, and
    // the editor renders no effort control for it rather than a disabled one carrying these values.
    assertEquals(true, claude.getBoolean("effortSupported"));
    assertEquals(false, claude.getBoolean("modelsEnumerated"));
    assertEquals(
        new JsonArray().add("low").add("medium").add("high").add("xhigh").add("max"),
        claude.getJsonArray("effortLevels"));
    assertEquals(true, claude.getBoolean("authenticated"));
    assertNotNull(claude.getString("authDetail"));
    assertEquals(true, claude.getBoolean("probeFailed"), "a fallback report says so");
    assertEquals("not probed here", claude.getString("probeDetail"));
    assertNotNull(claude.getString("harnessVersion"));
    assertNotNull(claude.getJsonArray("models"));

    JsonObject kimi = capabilities.getJsonObject(1);
    assertEquals("KIMI", kimi.getString("harness"));
    assertEquals(false, kimi.getBoolean("effortSupported"));
    assertEquals(new JsonArray(), kimi.getJsonArray("effortLevels"));
    assertEquals(false, kimi.getBoolean("authenticated"), "nobody looked, so nobody is signed in");
  }

  @Test
  void aReportThatHasNotLandedYetIsAnEmptyListRatherThanAnAbsentKey() throws Exception {
    // The probe runs off the boot thread, so a request can beat it. An empty list is a cache miss
    // to the host — it keeps what it had — where an absent key would be a decode surprise.
    api.wireAgents(
        runtime,
        launch,
        new AgentSessionQueryService(store, sessionStore),
        new AgentPluginService(PROCESSES, claudeMount.toString(), root, DEFAULTS),
        new PromptRefinementService(PROCESSES, WORKSPACE, DEFAULTS, claudeMount.toString(), root),
        DEFAULTS,
        IMAGE_VERSION,
        List::of);

    Answer answer = get("/agents/available");

    assertEquals(200, answer.status());
    assertEquals(new JsonArray(), answer.body().getJsonArray("capabilities"));
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
  void installingAPluginAnswersTheListItJustChanged() throws Exception {
    // The install verb returns the same envelope the listing does, so a client applies one decoder
    // and needs no follow-up read to see the result of what it just did.
    //
    // The path segment is the bare plugin id — the marketplace suffix the listing reports
    // (`<id>@claude-plugins-official`) is appended by the daemon. The id is pattern-matched before
    // it reaches a shell, so the qualified form is refused.
    Answer answer = post("/agent-plugins/jdtls-lsp/install", new JsonObject());

    assertEquals(200, answer.status());
    assertNotNull(answer.body().getJsonArray("installed"));

    Answer qualified =
        post("/agent-plugins/jdtls-lsp@claude-plugins-official/install", new JsonObject());
    assertEquals(400, qualified.status());
    assertTrue(qualified.body().getString("message").contains("plugin id"));
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
  void refinementTakesAPreambleBesideTheTranscript() throws Exception {
    Answer answer =
        post(
            "/prompt-refinements",
            new JsonObject()
                .put("transcript", "uh do the thing")
                .put("preamble", "you are refining a prompt"));

    assertEquals(200, answer.status());
    assertEquals("refined prompt", answer.body().getString("prompt"));
  }

  @Test
  void theSignInTerminalIsADoorOfItsOwnAnsweringTheCommandEnvelope() throws Exception {
    Answer answer = post("/agents/sign-in", new JsonObject().put("agentType", "KIMI"));

    assertEquals(200, answer.status());
    JsonObject command = answer.body().getJsonObject("command");
    assertEquals("Kimi sign-in", command.getString("actionName"));
    assertEquals(true, command.getBoolean("interactive"), "a sign-in is a PTY");
    assertNull(
        command.getString("agentSurface"),
        "a sign-in terminal is nobody's surface, so it is keyed to no configuration");

    post("/commands/" + command.getString("id") + "/terminate", new JsonObject());
  }

  @Test
  void signingInWithoutNamingAHarnessTakesTheResolvedDefault() throws Exception {
    Answer answer = post("/agents/sign-in", new JsonObject());

    assertEquals(200, answer.status());
    JsonObject command = answer.body().getJsonObject("command");
    assertEquals("Claude sign-in", command.getString("actionName"));

    post("/commands/" + command.getString("id") + "/terminate", new JsonObject());
  }

  @Test
  void theSignInDoorRejectsTheWrongMethodLikeEveryOtherRoute() throws Exception {
    assertEquals(405, get("/agents/sign-in").status());
  }

  @Test
  void aCommandThatIsNotAConfiguredSessionCarriesNoRecordAtAll() throws Exception {
    // Absent, not null. A sign-in terminal is nobody's surface and runs from no configuration, and
    // so does an agent command launched before a launch recorded itself — both must stay
    // distinguishable from a session that ran with an EMPTY configuration, which is an object.
    Answer answer = post("/agents/sign-in", new JsonObject().put("agentType", "CLAUDE"));

    assertEquals(200, answer.status());
    JsonObject command = answer.body().getJsonObject("command");
    assertFalse(command.containsKey("agentLaunchRecord"), command.encode());

    post("/commands/" + command.getString("id") + "/terminate", new JsonObject());
  }

  @Test
  void wrongMethodsAreRejectedPerRoute() throws Exception {
    assertEquals(405, post("/agents/available", new JsonObject()).status());
    assertEquals(405, post("/agent-sessions", new JsonObject()).status());
    assertEquals(405, get("/agent-worktrees/agent-1/turn").status());
    assertEquals(404, get("/agents").status(), "launching moved to /agent-worktrees");
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
    await(unwired.listen(vertx, "127.0.0.1", 0, TOKEN, null, List::of));
    int unwiredPort = unwired.actualPort();
    try {
      // Pinned to the same ctx as every other call: one context per test, not one per server.
      Promise<Answer> promise = Promise.promise();
      ctx.runOnContext(
          v ->
              client
                  .request(HttpMethod.GET, unwiredPort, "127.0.0.1", "/agent-sessions")
                  .compose(request -> request.putHeader("Authorization", "Bearer " + TOKEN).send())
                  .compose(AgentsApiTest::answerOf)
                  .onComplete(promise));
      Answer answer = await(promise.future());

      assertEquals(503, answer.status(), "retryable, not a 404 that reads as never-will-be");
      assertEquals("Coding agents are not available yet", answer.body().getString("message"));
    } finally {
      unwired.close();
    }
  }

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

  // --- agent worktrees: starting one -----------------------------------------------------------

  /** A start body with everything a dispatch sends; tests remove or change what they are about. */
  private JsonObject start(String agentId) {
    return new JsonObject()
        .put("agentId", agentId)
        .put("workId", "qits-1")
        .put("entityId", "qits-1")
        .put("wrapperBranch", BRANCH)
        .put("instruction", "fix the bug")
        .put(
            "env",
            new JsonObject()
                .put("QITS_TOKEN", "agent-token")
                .put("QITS_FAKE_LOG", fakeLog.toString()));
  }

  private Path agentDir(String agentId) {
    return worktrees.wrapperDir(agentId);
  }

  @Test
  @Timeout(60)
  void aStartMakesTheWorktreeAndRunsTheHarnessThereWithTheAgentsCredential() throws Exception {
    Answer answer = post("/agent-worktrees", start("agent-1"));

    assertEquals(200, answer.status(), answer.raw());
    JsonObject body = answer.body();
    assertEquals("agent-1", body.getString("agentId"));
    assertEquals(agentDir("agent-1").toString(), body.getString("path"));
    assertEquals(true, body.getBoolean("launched"));
    assertEquals(false, body.getBoolean("resumed"));
    assertNotNull(body.getString("sessionId"), "a fresh Claude session is pinned up front");
    String commandId = body.getString("commandId");

    JsonObject command = get("/commands/" + commandId).body();
    assertEquals("CHAT", command.getString("kind"));
    assertEquals("ticket.dispatch", command.getString("agentSurface"), "the dispatch surface");

    awaitContains(fakeLog.resolveSibling("harness.cwd"), agentDir("agent-1").toString());
    awaitContains(fakeLog.resolveSibling("harness.token"), "agent-token");
    awaitContains(fakeLog.resolveSibling("harness.stdin"), "fix the bug");
    assertTrue(
        Files.exists(agentDir("agent-1").resolve(CHILD).resolve("lib.txt")),
        "the submodule worktree is there too");
  }

  @Test
  void theCredentialIsNeverWrittenBesideTheWorktree() throws Exception {
    post("/agent-worktrees", start("agent-1"));

    String metadata =
        Files.readString(worktrees.agentDir("agent-1").resolve(AgentRuntime.METADATA_FILE));
    assertFalse(metadata.contains("agent-token"), metadata);
    assertTrue(metadata.contains(BRANCH), metadata);
  }

  @Test
  @Timeout(60)
  void aSecondStartWhileTheHarnessRunsLaunchesNothing() throws Exception {
    String first = post("/agent-worktrees", start("agent-1")).body().getString("commandId");
    awaitRunning(first);

    Answer again = post("/agent-worktrees", start("agent-1"));

    assertEquals(200, again.status());
    assertEquals(first, again.body().getString("commandId"));
    assertEquals(false, again.body().getBoolean("launched"));
  }

  @Test
  void anInteractiveStartRunsOnATerminal() throws Exception {
    Answer answer = post("/agent-worktrees", start("agent-1").put("mode", "INTERACTIVE"));

    assertEquals(200, answer.status(), answer.raw());
    JsonObject command = get("/commands/" + answer.body().getString("commandId")).body();
    assertEquals("TERMINAL", command.getString("kind"));
    assertEquals(true, command.getBoolean("interactive"));
  }

  @Test
  void theSurfaceANamedStartAsksForComesBackOnItsCommand() throws Exception {
    Answer answer = post("/agent-worktrees", start("agent-1").put("surface", "workspace.chat"));

    JsonObject command = get("/commands/" + answer.body().getString("commandId")).body();
    assertEquals("workspace.chat", command.getString("agentSurface"));
  }

  @Test
  void aMalformedStartIsAFourHundred() throws Exception {
    assertEquals(400, post("/agent-worktrees", start("agent-1").put("mode", "SIDEWAYS")).status());
    assertTrue(
        post("/agent-worktrees", start("agent-1").put("harness", "NOBODY"))
            .body()
            .getString("message")
            .contains("harness"));
    assertTrue(
        post("/agent-worktrees", start("agent-1").put("surface", "workspace.telepathy"))
            .body()
            .getString("message")
            .contains("surface"));
    assertEquals(400, post("/agent-worktrees", start("agent-1").putNull("workId")).status());
    assertEquals(400, post("/agent-worktrees", start("agent-1").putNull("agentId")).status());
    assertEquals(400, post("/agent-worktrees", start("agent-1").putNull("wrapperBranch")).status());
    assertEquals(400, post("/agent-worktrees", start("../x")).status());
    assertEquals(
        400,
        post("/agent-worktrees", start("agent-1").put("env", new JsonObject().put("N", 1)))
            .status(),
        "the credential becomes an environment, so it must be strings");
    assertFalse(worktrees.exists("agent-1"), "a refused start makes nothing");
  }

  @Test
  void aBranchAnotherAgentHoldsIsAConflict() throws Exception {
    post("/agent-worktrees", start("agent-1"));

    Answer answer = post("/agent-worktrees", start("agent-2"));

    assertEquals(409, answer.status());
    assertTrue(answer.body().getString("message").contains(BRANCH), answer.raw());
  }

  @Test
  void anUnauthenticatedHarnessIsRefusedRatherThanSwappedForASignInTerminal() throws Exception {
    wireWith(signedOut(), DEFAULTS);

    Answer answer = post("/agent-worktrees", start("agent-1"));

    // 409, not 500: the request was well-formed and the caller cannot fix it by asking
    // differently. The discriminator is the contract, the sentence beside it is not.
    assertEquals(409, answer.status());
    assertEquals("not-signed-in", answer.body().getString("error"));
    assertEquals("CLAUDE", answer.body().getString("agentType"));
    assertNotNull(answer.body().getString("message"));
  }

  @Test
  void aLaunchRecordsWhatItRanWithAndAnswersItOnEveryReadOfTheCommand() throws Exception {
    // A container keeps the document it was born with, and an edit in qits-projects applies to the
    // next one — so the store cannot answer what THIS session ran with. Only the record can.
    wireWith(PROCESSES, bornWith(document()));

    Answer answer = post("/agent-worktrees", start("agent-1").put("surface", "workspace.chat"));
    String commandId = answer.body().getString("commandId");

    JsonObject record = get("/commands/" + commandId).body().getJsonObject("agentLaunchRecord");
    assertNotNull(record);
    assertEquals("workspace.chat", record.getString("surface"));
    assertEquals("CLAUDE", record.getString("harness"));
    assertEquals("opus", record.getString("model"));
    assertEquals("high", record.getString("effort"));
    assertEquals("SKIP_PERMISSIONS", record.getString("permissionMode"));
    assertEquals(false, record.getBoolean("remoteControl"));
    assertEquals(true, record.getBoolean("configured"));
    JsonObject fromList =
        get("/commands").body().getJsonArray("entries").getJsonObject(0).getJsonObject("command");
    assertEquals(record, fromList.getJsonObject("agentLaunchRecord"));
  }

  @Test
  void anAttachedExternalServerIsRecordedByKeyAndItsCredentialAppearsNowhere() throws Exception {
    wireWith(PROCESSES, bornWith(document(EXTERNAL_SERVER)));

    Answer answer = post("/agent-worktrees", start("agent-1").put("surface", "workspace.chat"));

    String served = get("/commands/" + answer.body().getString("commandId")).raw();
    assertTrue(served.contains("stripe"), served);
    assertFalse(served.contains(EXTERNAL_HEADER_VALUE), "a header value must never be served");
    assertFalse(served.contains(EXTERNAL_URL), "nor the url, which can itself carry a credential");
    assertFalse(served.contains("agent-token"), "nor the agent's own credential");
    assertEquals(
        Set.of(
            "surface",
            "harness",
            "model",
            "effort",
            "permissionMode",
            "remoteControl",
            "remoteControlName",
            "activityTracking",
            "mcpServers",
            "externalMcpServers",
            "configured",
            "notes"),
        new JsonObject(served).getJsonObject("agentLaunchRecord").fieldNames());
  }

  // --- yield, turn and resume -------------------------------------------------------------------

  @Test
  @Timeout(60)
  void aTurnReachesTheRunningHarness() throws Exception {
    String commandId = post("/agent-worktrees", start("agent-1")).body().getString("commandId");
    awaitRunning(commandId);

    Answer answer = post("/agent-worktrees/agent-1/turn", new JsonObject().put("text", "go on"));

    assertEquals(200, answer.status(), answer.raw());
    assertEquals(true, answer.body().getBoolean("delivered"));
    assertEquals(false, answer.body().getBoolean("restarted"));
    assertEquals(commandId, answer.body().getString("commandId"));
    assertEquals("CHAT", answer.body().getString("kind"));
    awaitContains(fakeLog.resolveSibling("harness.stdin"), "go on");
  }

  @Test
  @Timeout(60)
  void aYieldedAgentIsResumedWithItsSessionAndTheTurn() throws Exception {
    JsonObject started = post("/agent-worktrees", start("agent-1")).body();
    String session = started.getString("sessionId");
    awaitRunning(started.getString("commandId"));
    writeSessionFile("agent-1", session);

    Answer yielded = post("/agent-worktrees/agent-1/yield", new JsonObject());
    assertEquals(200, yielded.status());
    assertEquals("agent-1", yielded.body().getString("agentId"));
    assertEquals(true, yielded.body().getBoolean("yielded"));
    awaitStopped(started.getString("commandId"));
    assertEquals(false, list().getJsonObject(0).getBoolean("harnessRunning"));

    Answer turn = post("/agent-worktrees/agent-1/turn", new JsonObject().put("text", "answered"));

    assertEquals(true, turn.body().getBoolean("delivered"));
    assertEquals(true, turn.body().getBoolean("restarted"));
    awaitContains(fakeLog.resolveSibling("harness.args"), "--resume " + session);
    awaitContains(fakeLog.resolveSibling("harness.stdin"), "answered");
  }

  @Test
  @Timeout(60)
  void aRestartedDaemonResumesTheSessionTheHostKept() throws Exception {
    // A new runtime is what a container restart leaves: the worktree on the volume, the session
    // files on the harness volume, and nothing in memory. The host hands the session id back.
    String session = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    worktrees.ensure("agent-1", BRANCH);
    writeSessionFile("agent-1", session);
    wireWith(PROCESSES, DEFAULTS);

    Answer answer = post("/agent-worktrees", start("agent-1").put("sessionId", session));

    assertEquals(200, answer.status(), answer.raw());
    assertEquals(true, answer.body().getBoolean("resumed"));
    assertEquals(session, answer.body().getString("sessionId"));
    awaitContains(fakeLog.resolveSibling("harness.args"), "--resume " + session);
  }

  @Test
  void aSessionWhoseFilesAreGoneStartsAFreshOne() throws Exception {
    String session = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    Answer answer = post("/agent-worktrees", start("agent-1").put("sessionId", session));

    assertEquals(200, answer.status(), answer.raw());
    assertEquals(false, answer.body().getBoolean("resumed"));
    assertFalse(session.equals(answer.body().getString("sessionId")));
  }

  @Test
  void aTurnNeedsTextAndAKnownAgent() throws Exception {
    post("/agent-worktrees", start("agent-1"));

    assertEquals(400, post("/agent-worktrees/agent-1/turn", new JsonObject()).status());
    assertEquals(
        400, post("/agent-worktrees/agent-1/turn", new JsonObject().put("text", " ")).status());
    assertEquals(
        404, post("/agent-worktrees/agent-9/turn", new JsonObject().put("text", "hi")).status());
  }

  /** Where Claude keeps a session's transcript: keyed by the escaped working directory. */
  private void writeSessionFile(String agentId, String session) throws Exception {
    String escaped = agentDir(agentId).toString().replaceAll("[^A-Za-z0-9]", "-");
    Path file = claudeMount.resolve(".claude/projects").resolve(escaped).resolve(session + ".jsonl");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "{}\n");
  }

  // --- the entity, per agent --------------------------------------------------------------------

  @Test
  void settingTheEntityRenamesThatAgentAndAnswersTheFacts() throws Exception {
    post("/agent-worktrees", start("agent-1"));

    Answer answer =
        post(
            "/agent-worktrees/agent-1/entity",
            new JsonObject()
                .put("title", "Session names carry status")
                .put("status", "IMPLEMENTING")
                .put("blocked", true)
                .put("blockSource", "AGENT_WAITING"));

    assertEquals(200, answer.status());
    assertEquals("Session names carry status", answer.body().getString("title"));
    assertEquals("IMPLEMENTING", answer.body().getString("status"));
    assertEquals(true, answer.body().getBoolean("blocked"));
    assertEquals("AGENT_WAITING", answer.body().getString("blockSource"));
    assertEquals(1, answer.body().getInteger("renamed"), "this agent's running chat, and no other");
    assertEquals(
        new EntityFacts("Session names carry status", "IMPLEMENTING", true, "AGENT_WAITING"),
        runtime.entity("agent-1"));

    Answer blocked =
        post("/agent-worktrees/agent-1/blocked", new JsonObject().put("blocked", false));
    assertEquals(200, blocked.status());
    assertEquals(false, blocked.body().getBoolean("blocked"));
    assertEquals(
        new EntityFacts("Session names carry status", "IMPLEMENTING", false, null),
        runtime.entity("agent-1"),
        "the blocked route moves only the flag");
  }

  @Test
  void aMistypedEntityBodyIsAFourHundredAndMovesNothing() throws Exception {
    post("/agent-worktrees", start("agent-1"));
    post(
        "/agent-worktrees/agent-1/entity",
        new JsonObject().put("title", "kept").put("blocked", false));

    assertEquals(
        400, post("/agent-worktrees/agent-1/entity", new JsonObject().put("title", "t")).status());
    assertEquals(
        400,
        post("/agent-worktrees/agent-1/entity", new JsonObject().put("title", 7).put("blocked", true))
            .status());
    assertEquals(
        400,
        post("/agent-worktrees/agent-1/blocked", new JsonObject().put("blocked", "true")).status());
    assertEquals(new EntityFacts("kept", null, false), runtime.entity("agent-1"));
  }

  @Test
  void aStartCarriesTheEntityItIsNamedFor() throws Exception {
    post(
        "/agent-worktrees",
        start("agent-1")
            .put("entityTitle", "Fix it")
            .put("entityStatus", "REFINED")
            .put("entityBlocked", true)
            .put("blockSource", "AGENT_WAITING"));

    assertEquals(
        new EntityFacts("Fix it", "REFINED", true, "AGENT_WAITING"), runtime.entity("agent-1"));
    assertEquals(
        400,
        post("/agent-worktrees", start("agent-1").put("entityBlocked", "yes")).status(),
        "a mistyped fact is refused, not coerced");
  }

  // --- listing, cleanup and removal ---------------------------------------------------------------

  private JsonArray list() throws Exception {
    Answer answer = get("/agent-worktrees");
    assertEquals(200, answer.status(), answer.raw());
    return answer.array();
  }

  @Test
  void theListNamesEachAgentItsBranchesAndWhetherAnythingWouldBeLost() throws Exception {
    JsonObject started = post("/agent-worktrees", start("agent-1")).body();
    awaitRunning(started.getString("commandId"));
    Path child = agentDir("agent-1").resolve(CHILD);
    GitFixtures.git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    GitFixtures.git(child, "commit", "--quiet", "--allow-empty", "-m", "work");

    JsonObject agent = list().getJsonObject(0);

    assertEquals("agent-1", agent.getString("agentId"));
    assertEquals("qits-1", agent.getString("workId"));
    assertEquals(BRANCH, agent.getString("wrapperBranch"));
    assertEquals(agentDir("agent-1").toString(), agent.getString("path"));
    assertEquals(true, agent.getBoolean("harnessRunning"));
    assertEquals(started.getString("commandId"), agent.getString("commandId"));
    assertEquals(started.getString("sessionId"), agent.getString("sessionId"));
    assertEquals(false, agent.getBoolean("dirty"));
    assertEquals(true, agent.getBoolean("unpushed"));
    JsonArray branches = agent.getJsonArray("branches");
    assertEquals(2, branches.size());
    JsonObject wrapper = branches.getJsonObject(0);
    assertEquals(WRAPPER, wrapper.getString("repository"));
    assertEquals("", wrapper.getString("path"));
    assertEquals(BRANCH, wrapper.getString("branch"));
    assertNotNull(wrapper.getString("head"));
    assertEquals(false, wrapper.getBoolean("pushed"));
    JsonObject sub = branches.getJsonObject(1);
    assertEquals("child", sub.getString("repository"));
    assertEquals(CHILD, sub.getString("path"));
    assertEquals(BRANCH + "-fix", sub.getString("branch"));
    assertEquals(get("/agent-worktrees/agent-1").body(), agent, "one agent reads the same");
  }

  @Test
  void anAgentWithWorkThatWouldBeLostIsNotRemovedUnlessForced() throws Exception {
    post("/agent-worktrees", start("agent-1"));
    Files.writeString(agentDir("agent-1").resolve("notes.md"), "unfinished\n");

    Answer check = get("/agent-worktrees/agent-1/cleanup-check");
    assertEquals(200, check.status());
    assertEquals(false, check.body().getBoolean("clean"));
    assertEquals(new JsonArray().add(WRAPPER), check.body().getJsonArray("dirty"));
    assertEquals(new JsonArray(), check.body().getJsonArray("unpushed"));

    Answer refused = delete("/agent-worktrees/agent-1");
    assertEquals(409, refused.status());
    assertEquals(check.body(), refused.body(), "the refusal is the check");
    assertTrue(worktrees.exists("agent-1"));

    Answer forced = delete("/agent-worktrees/agent-1?force=true");
    assertEquals(200, forced.status());
    assertEquals(true, forced.body().getBoolean("removed"));
    assertFalse(worktrees.exists("agent-1"));
    assertEquals(new JsonArray(), list());
  }

  @Test
  void anUnpushedCommitIsReportedWithItsRepositoryAndBranch() throws Exception {
    post("/agent-worktrees", start("agent-1"));
    Path child = agentDir("agent-1").resolve(CHILD);
    GitFixtures.git(child, "commit", "--quiet", "--allow-empty", "--no-verify", "-m", "stray");

    JsonObject leftover =
        get("/agent-worktrees/agent-1/cleanup-check").body().getJsonArray("unpushed").getJsonObject(0);

    assertEquals("child", leftover.getString("repository"));
    assertTrue(leftover.containsKey("branch"), "named, as null: a detached HEAD");
    assertNull(leftover.getString("branch"));
    assertEquals(1, leftover.getInteger("commits"));
  }

  @Test
  void aCleanAgentIsRemoved() throws Exception {
    post("/agent-worktrees", start("agent-1"));

    Answer removed = delete("/agent-worktrees/agent-1");

    assertEquals(200, removed.status(), removed.raw());
    assertEquals("agent-1", removed.body().getString("agentId"));
    assertFalse(Files.exists(worktrees.agentDir("agent-1")));
    assertEquals(404, get("/agent-worktrees/agent-1").status());
  }

  @Test
  void anAgentsFilesAreItsOwnWorktree() throws Exception {
    post("/agent-worktrees", start("agent-1"));
    Files.writeString(agentDir("agent-1").resolve("mine.txt"), "agent one\n");

    Answer content = get("/agent-worktrees/agent-1/files/content?path=mine.txt");

    assertEquals(200, content.status(), content.raw());
    assertEquals("agent one\n", content.body().getString("content"));
    assertTrue(
        get("/agent-worktrees/agent-1/files").body().getJsonArray("paths").contains("README.md"));
  }

  @Test
  void anActivityFrameNamesItsAgentAndKeepsItsSessionCurrent() throws Exception {
    String commandId = post("/agent-worktrees", start("agent-1")).body().getString("commandId");

    AgentActivity tagged =
        (AgentActivity)
            runtime.tag(
                new AgentActivity(commandId, "s-switched", "IDLE", "SessionStart", null, null, 1L));

    assertEquals("agent-1", tagged.agentId());
    assertEquals("s-switched", list().getJsonObject(0).getString("sessionId"));
    AgentActivity foreign = new AgentActivity("not-an-agent", null, "IDLE", "Stop", null, null, 1L);
    assertNull(((AgentActivity) runtime.tag(foreign)).agentId(), "the sign-in terminal, say");
  }

  // --- the security pass ------------------------------------------------------------------------

  @Test
  @Timeout(60)
  void theHarnessHoldsNoSecretOfTheWorkspaceAndItsMcpCarriesTheAgentsToken() throws Exception {
    post("/agent-worktrees", start("agent-1"));

    // Blanked, not merely absent here: the commands layer can only lay values over the daemon's
    // environment, so the daemon's API token and commissioned client become empty strings.
    awaitContains(fakeLog.resolveSibling("harness.secrets"), "api= secret=");
    awaitContains(fakeLog.resolveSibling("harness.args"), "Bearer agent-token");
  }

  @Test
  void aSymbolicLinkPlantedAsAgentJsonIsReplacedNeverWrittenThrough() throws Exception {
    Path outside = estateDir.resolve("outside.txt");
    Files.writeString(outside, "untouched\n");
    worktrees.ensure("agent-1", BRANCH);
    Path metadata = worktrees.agentDir("agent-1").resolve(AgentRuntime.METADATA_FILE);
    Files.createSymbolicLink(metadata, outside);

    post("/agent-worktrees", start("agent-1"));

    assertEquals("untouched\n", Files.readString(outside));
    assertFalse(Files.isSymbolicLink(metadata));
    assertFalse(Files.readString(metadata).contains("agent-token"));
  }

  @Test
  void aSessionIdThatIsNotAPlainNameIsRefused() throws Exception {
    assertEquals(
        400, post("/agent-worktrees", start("agent-1").put("sessionId", "../../x")).status());
    assertFalse(worktrees.exists("agent-1"));
  }

  @Test
  void anAgentWhoseWorktreeWasSwappedForALinkServesNoFilesAndCannotBeRemoved() throws Exception {
    post("/agent-worktrees", start("agent-2").put("wrapperBranch", "ticket/qits-2"));
    worktrees.ensure("agent-1", BRANCH);
    AgentWorktrees.deleteTree(agentDir("agent-1"));
    Files.createSymbolicLink(agentDir("agent-1"), agentDir("agent-2"));

    assertEquals(404, get("/agent-worktrees/agent-1/files/content?path=README.md").status());
    assertEquals(404, get("/agent-worktrees/agent-1/cleanup-check").status());
    assertEquals(404, delete("/agent-worktrees/agent-1?force=true").status());
    assertTrue(worktrees.exists("agent-2"), "agent 2 is left as it was");
  }

  @Test
  void anAgentIdThatIsNotAPlainNameReachesNoRoute() throws Exception {
    for (String id : new String[] {"..", "%2e%2e", "..%2Fx", "-rf"}) {
      assertEquals(404, get("/agent-worktrees/" + id + "/cleanup-check").status(), id);
      assertEquals(404, delete("/agent-worktrees/" + id).status(), id);
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** Polls {@code file} until it holds {@code needle}; a launch crosses a process boundary. */
  private static void awaitContains(Path file, String needle) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    String seen = "";
    while (System.nanoTime() < deadline) {
      seen = Files.exists(file) ? Files.readString(file) : "";
      if (seen.contains(needle)) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("timed out waiting for '" + needle + "' in " + file + ": " + seen);
  }

  /** Waits until the fake harness has started reading its input. */
  private void awaitRunning(String commandId) throws Exception {
    awaitContains(fakeLog.resolveSibling("harness.cwd"), "/");
    assertTrue(store.find(commandId).orElseThrow().isRunning(), "the harness stays up");
  }

  private void awaitStopped(String commandId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (store.find(commandId).orElseThrow().isRunning()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("the harness did not stop");
      }
      Thread.sleep(25);
    }
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

  private Answer delete(String uri) throws Exception {
    return send(HttpMethod.DELETE, uri, "Bearer " + TOKEN, null);
  }

  /**
   * Composed end to end, and <em>issued</em> on {@link #ctx}: a {@code client.request} started from
   * the JUnit thread mints a fresh context per call, and the 4.5.26 pool then intermittently never
   * leases that waiter a connection — nothing is written, the await expires. See {@link
   * WorkspaceApiTest#get(String, String)}.
   */
  private Answer send(HttpMethod method, String uri, String authorization, JsonObject body)
      throws Exception {
    Promise<Answer> promise = Promise.promise();
    ctx.runOnContext(
        v ->
            client
                .request(method, port, "127.0.0.1", uri)
                .compose(
                    request -> {
                      if (authorization != null) {
                        request.putHeader("Authorization", authorization);
                      }
                      return body == null ? request.send() : request.send(body.encode());
                    })
                .compose(AgentsApiTest::answerOf)
                .onComplete(promise));
    return await(promise.future());
  }

  private static Future<Answer> answerOf(HttpClientResponse response) {
    return response.body().map(body -> new Answer(response.statusCode(), body.toString()));
  }

  /** One answer: its status and its body, read as an object or, for a list, an array. */
  private record Answer(int status, String raw) {
    JsonObject body() {
      return new JsonObject(raw);
    }

    JsonArray array() {
      return new JsonArray(raw);
    }
  }
}
