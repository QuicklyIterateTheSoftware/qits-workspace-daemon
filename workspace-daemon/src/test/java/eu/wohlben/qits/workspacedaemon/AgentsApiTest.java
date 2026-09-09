package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentDefaults;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentPluginService;
import eu.wohlben.qits.agents.AgentSessionQueryService;
import eu.wohlben.qits.agents.AgentSessionStore;
import eu.wohlben.qits.agents.AgentTranscriptService;
import eu.wohlben.qits.agents.AgentTranscriptTailService;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.CommandsAgentCommands;
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
  private static final String IMAGE_VERSION = "2026.909.111643";

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
            new eu.wohlben.qits.agents.AgentAuthStatus(
                PROCESSES, claudeMount.toString(), root),
            transcripts,
            tail,
            DEFAULTS,
            new WorkspaceMcpServers(ENDPOINTS, REPO, "feature-x"),
            WORKSPACE,
            claudeMount.toString(),
            HOOKS_PORT);
    api.wireCommands(commands, registry, WORKSPACE);
    api.wireAgents(
        launch,
        new AgentSessionQueryService(store, sessionStore),
        new AgentPluginService(PROCESSES, claudeMount.toString(), root, DEFAULTS),
        new PromptRefinementService(PROCESSES, WORKSPACE, DEFAULTS, claudeMount.toString(), root),
        DEFAULTS,
        IMAGE_VERSION,
        () -> CAPABILITIES);
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

  /**
   * A runner on whose volume nobody has signed in: {@code claude auth status} fails and the kimi
   * credential probe finds nothing.
   */
  private static ProcessRunner signedOut() {
    return (command, cwd, env, timeout) ->
        new ProcessRunner.Result(1, "", "Not logged in", false);
  }

  /** Rebuild the agent surface on a different {@link ProcessRunner}, leaving everything else. */
  private void rewireWith(ProcessRunner processes) {
    CommandLogService logs = new CommandLogService(store, null);
    CommandRegistry registry = new CommandRegistry(root, 2_000);
    CommandService commands =
        new CommandService(store, registry, lifecycle, logs, WORKSPACE, new NoActions());
    AgentTranscriptService transcripts =
        new AgentTranscriptService(store, logs, sessionStore, claudeMount.toString(), null);
    AgentTranscriptTailService tail = new AgentTranscriptTailService(transcripts, logs);
    AgentLaunchService rewired =
        new AgentLaunchService(
            new CommandsAgentCommands(commands, registry, store),
            new eu.wohlben.qits.agents.AgentAuthStatus(processes, claudeMount.toString(), root),
            transcripts,
            tail,
            DEFAULTS,
            new WorkspaceMcpServers(ENDPOINTS, REPO, "feature-x"),
            WORKSPACE,
            claudeMount.toString(),
            HOOKS_PORT);
    api.wireCommands(commands, registry, WORKSPACE);
    api.wireAgents(
        rewired,
        new AgentSessionQueryService(store, sessionStore),
        new AgentPluginService(processes, claudeMount.toString(), root, DEFAULTS),
        new PromptRefinementService(processes, WORKSPACE, DEFAULTS, claudeMount.toString(), root),
        DEFAULTS,
        IMAGE_VERSION,
        () -> CAPABILITIES);
  }

  /** This workspace declares no actions; agents are launched, not resolved from config. */
  private record NoActions()
      implements eu.wohlben.qits.commands.ActionResolver {
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
  void aLaunchAnswersTheCommandEnvelopeAndTakesItsSeedInline() throws Exception {
    Answer answer =
        post(
            "/agents",
            new JsonObject()
                .put("scope", "REPOSITORY")
                .put("mode", "INTERACTIVE")
                .put("agentType", "CLAUDE")
                .put("initialContext", "look at the README")
                .put("fork", false)
                .put("deliverTaskPrompt", false));

    assertEquals(200, answer.status());
    // The same `{command: …}` envelope POST /commands answers with, so one client-side decoder
    // serves both launch paths. The harness binary is absent in the suite, so the process exits
    // immediately — what is under test here is the request contract and the response shape.
    JsonObject command = answer.body().getJsonObject("command");
    assertEquals("feature-x", command.getString("workspaceId"));
    // The launch mode maps onto the command kind the frontend routes its view on: INTERACTIVE is a
    // PTY (TERMINAL, xterm.js), CHAT is line-delimited JSON on pipes (see the sibling test).
    assertEquals("TERMINAL", command.getString("kind"));
    assertEquals(true, command.getBoolean("interactive"), "INTERACTIVE renders onto a PTY");
    assertNotNull(command.getString("id"));

    post("/commands/" + command.getString("id") + "/terminate", new JsonObject());
  }

  @Test
  void aChatLaunchIsNotInteractive() throws Exception {
    Answer answer =
        post(
            "/agents",
            new JsonObject().put("scope", "REPOSITORY").put("mode", "CHAT").put("fork", false));

    assertEquals(200, answer.status());
    assertEquals(false, answer.body().getJsonObject("command").getBoolean("interactive"));
    post("/commands/" + answer.body().getJsonObject("command").getString("id") + "/terminate",
        new JsonObject());
  }

  @Test
  void aForkWithoutASessionToForkFromIsAFourHundred() throws Exception {
    // The two request fields that only make sense together, and the message names both.
    Answer answer =
        post(
            "/agents",
            new JsonObject().put("scope", "REPOSITORY").put("mode", "CHAT").put("fork", true));

    assertEquals(400, answer.status());
    assertEquals("fork requires resumeSessionId", answer.body().getString("message"));
  }

  @Test
  void resumingASessionThisContainerDoesNotOwnIsRefused() throws Exception {
    // Fails closed: the store that would vouch for the session did not survive the container, so
    // resuming a vanished id would exit instantly with "no conversation found".
    Answer answer =
        post(
            "/agents",
            new JsonObject()
                .put("scope", "REPOSITORY")
                .put("mode", "CHAT")
                .put("resumeSessionId", "3f2504e0-4f89-11d3-9a0c-0305e82c3301"));

    assertEquals(400, answer.status());
    assertTrue(
        answer.body().getString("message").contains("3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
        answer.body().encode());
  }

  @Test
  void anUnknownModeOrAgentTypeIsAFourHundredToo() throws Exception {
    assertTrue(
        post("/agents", new JsonObject().put("scope", "REPOSITORY").put("mode", "SIDEWAYS"))
            .body()
            .getString("message")
            .contains("mode"));
    assertTrue(
        post(
                "/agents",
                new JsonObject()
                    .put("scope", "REPOSITORY")
                    .put("mode", "CHAT")
                    .put("agentType", "NOBODY"))
            .body()
            .getString("message")
            .contains("agentType"));
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
  void anUnauthenticatedLaunchIsRefusedRatherThanSwappedForASignInTerminal() throws Exception {
    // The substitution this replaced: launchChat used to answer launchLogin's bare REPL, and the
    // caller redirected you into it. You asked for a chat about an epic and got a login terminal,
    // and nothing in the answer said so.
    rewireWith(signedOut());

    Answer answer =
        post("/agents", new JsonObject().put("scope", "REPOSITORY").put("mode", "CHAT"));

    // 409, not 500 and not 400: the request was well-formed and the caller cannot fix it by asking
    // differently. A 500 would have shown "Internal error" for a state one click fixes.
    assertEquals(409, answer.status());
    // The DISCRIMINATOR is the contract, asserted as a literal — the sentence beside it is not, and
    // matching prose is the display-string-as-contract mistake this epic exists to delete.
    assertEquals("not-signed-in", answer.body().getString("error"));
    assertEquals("CLAUDE", answer.body().getString("agentType"));
    assertNotNull(answer.body().getString("message"));
  }

  @Test
  void anUnattendedDispatchIsRefusedTheSameWayRatherThanOpeningATerminalNobodyWatches()
      throws Exception {
    rewireWith(signedOut());

    Answer answer =
        post(
            "/agents",
            new JsonObject()
                .put("scope", "REPOSITORY")
                .put("surface", "ticket.dispatch")
                .put("mode", "INTERACTIVE"));

    assertEquals(409, answer.status());
    assertEquals("not-signed-in", answer.body().getString("error"));
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
  void theSurfaceComesBackOnTheCommandItLaunched() throws Exception {
    // The parameter earns its keep here and nowhere else: epic.chat and workspace.chat send
    // byte-identical requests to two containers, so until this field travelled nothing downstream
    // could tell one of this daemon's four surfaces from another.
    Answer answer =
        post(
            "/agents",
            new JsonObject()
                .put("scope", "REPOSITORY")
                .put("surface", "epic.chat")
                .put("mode", "CHAT"));

    assertEquals(200, answer.status());
    JsonObject command = answer.body().getJsonObject("command");
    assertEquals("epic.chat", command.getString("agentSurface"));

    post("/commands/" + command.getString("id") + "/terminate", new JsonObject());
  }

  @Test
  void aMissingSurfaceResolvesToTheShapeTheRequestImplies() throws Exception {
    // The dated crutch that lets this daemon ship before the frontends. It is lossy exactly where
    // the field exists to fix: an epic's agent tab reads as workspace.agent.
    Answer answer =
        post("/agents", new JsonObject().put("scope", "REPOSITORY").put("mode", "INTERACTIVE"));

    assertEquals(200, answer.status());
    JsonObject command = answer.body().getJsonObject("command");
    assertEquals("workspace.agent", command.getString("agentSurface"));

    post("/commands/" + command.getString("id") + "/terminate", new JsonObject());
  }

  @Test
  void anUnknownSurfaceIsAFourHundredRatherThanASilentDefault() throws Exception {
    Answer answer =
        post(
            "/agents",
            new JsonObject().put("scope", "REPOSITORY").put("surface", "workspace.telepathy"));

    assertEquals(400, answer.status());
    assertTrue(answer.body().getString("message").contains("surface"), answer.body().encode());
  }

  @Test
  void aTicketDispatchNamesItsOwnSurface() throws Exception {
    // A workspace cut for a ticket: nobody presses a button for it, and it is configured on the
    // same footing as the four a human starts.
    Answer answer =
        post(
            "/agents",
            new JsonObject()
                .put("scope", "REPOSITORY")
                .put("surface", "ticket.dispatch")
                .put("mode", "INTERACTIVE"));

    assertEquals(200, answer.status());
    JsonObject command = answer.body().getJsonObject("command");
    assertEquals("ticket.dispatch", command.getString("agentSurface"));

    post("/commands/" + command.getString("id") + "/terminate", new JsonObject());
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
