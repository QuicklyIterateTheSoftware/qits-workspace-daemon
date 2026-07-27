package eu.wohlben.qits.workspacedaemon.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.agents.acp.AcpSessionConfig;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionRef;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionSource;
import eu.wohlben.qits.workspacedaemon.commands.ChatProtocolFactory;
import eu.wohlben.qits.workspacedaemon.commands.Command;
import eu.wohlben.qits.workspacedaemon.commands.CommandExitListener;
import eu.wohlben.qits.workspacedaemon.commands.CommandKind;
import eu.wohlben.qits.workspacedaemon.commands.CommandLogService;
import eu.wohlben.qits.workspacedaemon.commands.CommandStore;
import eu.wohlben.qits.workspacedaemon.commands.InvalidCommandRequestException;
import eu.wohlben.qits.workspacedaemon.commands.WorkspaceContext;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launch hub, against a recording {@link AgentCommands}.
 *
 * <p>These were {@code @QuarkusTest}s with twelve injected beans and a database. What is worth
 * keeping from them is the half that still exists: the MCP scope narrowing, the read-only marking and
 * allowlists, the credential overlay, the session lineage, and the auth redirect. Those translate
 * directly.
 */
class AgentLaunchServiceTest {

  private static final String REPO = "11111111-1111-1111-1111-111111111111";
  private static final String PROJECT = "22222222-2222-2222-2222-222222222222";
  private static final String WORKSPACE = "feature-x";
  private static final String CLAUDE_MOUNT = "/claude-home";
  private static final int HOOKS_PORT = 13337;
  private static final String KIMI_SESSION = "session_aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @TempDir Path workspaceRoot;

  private Commands commands;
  private boolean loggedIn;
  private AgentType defaultType;
  private boolean activityTracking;

  @BeforeEach
  void setUp() {
    commands = new Commands();
    loggedIn = true;
    defaultType = AgentType.CLAUDE;
    activityTracking = true;
  }

  // --- fakes ------------------------------------------------------------------------------------

  /** Records every launch instead of spawning one. */
  private static final class Commands implements AgentCommands {
    private final List<Launch> launches = new ArrayList<>();
    private final Map<String, String> ownedSessions = new HashMap<>();
    private final List<String> chatSends = new ArrayList<>();

    private record Launch(
        String name,
        String script,
        boolean interactive,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef session,
        ChatProtocolFactory protocolFactory,
        String agentType,
        CommandKind kind) {}

    private Launch last() {
      return launches.get(launches.size() - 1);
    }

    private Command record(Launch launch) {
      launches.add(launch);
      return Command.running(
          launch.commandId() == null ? "generated" : launch.commandId(),
          launch.kind(),
          "main",
          "abc1234",
          null,
          launch.name(),
          launch.script(),
          launch.interactive(),
          launch.agentType(),
          Instant.now());
    }

    @Override
    public Command launchAgent(
        String name,
        String script,
        boolean interactive,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef agentSession,
        CommandExitListener onExit,
        String agentType) {
      return record(
          new Launch(
              name,
              script,
              interactive,
              environment,
              commandId,
              agentSession,
              null,
              agentType,
              CommandKind.TERMINAL));
    }

    @Override
    public Command launchChat(
        String name,
        String script,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef agentSession,
        CommandExitListener onExit,
        ChatProtocolFactory protocolFactory,
        String agentType) {
      return record(
          new Launch(
              name,
              script,
              false,
              environment,
              commandId,
              agentSession,
              protocolFactory,
              agentType,
              CommandKind.CHAT));
    }

    @Override
    public boolean chatSend(String commandId, String text) {
      chatSends.add(text);
      return true;
    }

    @Override
    public void reportAgentSession(String commandId, String sessionId, String transcriptPath) {}

    @Override
    public boolean ownsSession(String sessionId) {
      return ownedSessions.containsKey(sessionId);
    }

    @Override
    public Optional<String> agentTypeForSession(String sessionId) {
      return Optional.ofNullable(ownedSessions.get(sessionId));
    }
  }

  private static final WorkspaceContext WORKSPACE_CONTEXT =
      new WorkspaceContext() {
        @Override
        public String repoId() {
          return REPO;
        }

        @Override
        public String workspaceId() {
          return WORKSPACE;
        }

        @Override
        public String branch() {
          return "feature/x";
        }

        @Override
        public String commitHash() {
          return "abc1234";
        }
      };

  /**
   * Each server on its owning service's segment, as {@code DaemonMcpEndpoints} resolves them — not
   * a {@code /mcp/<server>} family, which no longer exists.
   */
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

  private AgentLaunchService service() {
    ProcessRunner probe =
        (command, cwd, env, timeout) ->
            new ProcessRunner.Result(loggedIn ? 0 : 1, loggedIn ? "" : "signed out", "", false);
    AgentDefaults defaults =
        new AgentDefaults() {
          @Override
          public AgentType defaultAgentType() {
            return defaultType;
          }

          @Override
          public boolean activityTrackingEnabled() {
            return activityTracking;
          }

          @Override
          public Optional<String> refinementModel() {
            return Optional.empty();
          }
        };
    CommandStore store = new CommandStore();
    AgentTranscriptService transcripts =
        new AgentTranscriptService(
            store,
            new CommandLogService(store, null),
            new AgentSessionStore(),
            workspaceRoot.toString(),
            null);
    return new AgentLaunchService(
        commands,
        new AgentAuthStatus(probe, CLAUDE_MOUNT, workspaceRoot),
        transcripts,
        new AgentTranscriptTailService(transcripts, new CommandLogService(store, null)),
        defaults,
        ENDPOINTS,
        WORKSPACE_CONTEXT,
        CLAUDE_MOUNT,
        HOOKS_PORT);
  }

  private static AgentLaunchRequest chat(AgentMcpScope scope) {
    return new AgentLaunchRequest(scope, AgentLaunchMode.CHAT, null, null, false, false, null);
  }

  // --- MCP scoping ------------------------------------------------------------------------------

  @Nested
  class McpScoping {

    @Test
    void repositoryScopeNarrowsByProjectRepositoryAndWorkspace() {
      List<AgentLaunchService.ScopedMcp> servers = service().serversFor(AgentMcpScope.REPOSITORY);

      assertEquals(2, servers.size(), "the repository server plus the observability one");
      assertEquals("repository", servers.get(0).key());
      assertEquals(
          "http://qits:8080/projects/mcp?projectId="
              + PROJECT
              + "&repositoryId="
              + REPO
              + "&workspaceId="
              + WORKSPACE,
          servers.get(0).url());
    }

    @Test
    void theObservabilityServerIsSeparateAndCarriesOnlyTheScopesItReads() {
      List<AgentLaunchService.ScopedMcp> servers = service().serversFor(AgentMcpScope.REPOSITORY);

      assertEquals("observability", servers.get(1).key());
      assertEquals(
          "http://qits:8080/observability/mcp?repositoryId=" + REPO + "&workspaceId=" + WORKSPACE,
          servers.get(1).url(),
          "qits-observability reads repositoryId and workspaceId, and has no notion of a project");
    }

    @Test
    void actionsScopePairsTheActionServerWithTheNarrowedRepositoryAndObservabilityServers() {
      List<AgentLaunchService.ScopedMcp> servers = service().serversFor(AgentMcpScope.ACTIONS);

      assertEquals(3, servers.size());
      assertEquals("actions", servers.get(0).key());
      assertEquals("http://qits:8080/actions/mcp?repositoryId=" + REPO, servers.get(0).url());
      assertEquals("repository", servers.get(1).key());
      assertTrue(servers.get(1).url().contains("workspaceId=" + WORKSPACE));
      assertEquals("observability", servers.get(2).key());
    }

    @Test
    void projectScopeDropsTheRepositoryNarrowingAndWithItObservability() {
      List<AgentLaunchService.ScopedMcp> servers = service().serversFor(AgentMcpScope.PROJECT);

      assertEquals(1, servers.size(), "telemetry answers per workspace, so it has nothing to say");
      assertEquals(
          "http://qits:8080/projects/mcp?projectId=" + PROJECT,
          servers.get(0).url(),
          "project scope sees every repository, so it must not carry repositoryId");
    }

    @Test
    void onlyReadOnlyToolsArePreApproved() {
      List<AgentLaunchService.ScopedMcp> servers = service().serversFor(AgentMcpScope.ACTIONS);

      assertTrue(servers.get(0).allowedTools().contains("mcp__actions__listGlobalActions"));
      assertFalse(
          servers.get(0).allowedTools().stream().anyMatch(t -> t.contains("create")),
          "a mutating tool must still prompt");
      assertTrue(servers.get(1).allowedTools().contains("mcp__repository__taskPrompt"));
      assertFalse(
          servers.get(1).allowedTools().stream().anyMatch(t -> t.contains("integrateBranch")));
    }

    @Test
    void theTelemetryToolsArePreApprovedUnderTheServerThatActuallyDeclaresThem() {
      List<AgentLaunchService.ScopedMcp> servers = service().serversFor(AgentMcpScope.REPOSITORY);

      assertTrue(
          servers.get(1).allowedTools().contains("mcp__observability__telemetryErrors"),
          "the allowlist names the tool as the agent sees it: mcp__<server>__<tool>");
      assertFalse(
          servers.get(0).allowedTools().stream().anyMatch(t -> t.contains("telemetry")),
          "a telemetry entry on the repository server would allowlist a tool nothing declares");
    }
  }

  // --- rendering --------------------------------------------------------------------------------

  @Nested
  class Rendering {

    @Test
    void aChatCarriesTheScopedServerTheHomeOverlayAndTheHook() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec = service.renderChat(AgentMcpScope.REPOSITORY, pinned, AgentType.CLAUDE);

      assertTrue(spec.script().contains("--input-format stream-json"));
      assertTrue(spec.script().contains("workspaceId=" + WORKSPACE));
      assertTrue(spec.script().contains("--dangerously-skip-permissions"));
      assertEquals(CLAUDE_MOUNT, spec.environment().get("HOME"), "the one credential that crosses in");
      assertTrue(
          spec.script().contains("127.0.0.1:" + HOOKS_PORT + "/hooks/claude-code?commandId="),
          spec.script());
    }

    @Test
    void theHookUrlUsesTheInjectedPortRatherThanASecondConfigRead() {
      AgentLaunchService service =
          new AgentLaunchService(
              commands, null, null, null, null, ENDPOINTS, WORKSPACE_CONTEXT, CLAUDE_MOUNT, 24680);

      assertEquals(
          "http://127.0.0.1:24680/hooks/claude-code?commandId=c1", service.sessionReportUrl("c1"));
    }

    @Test
    void anAutonomousRunMarksEveryServerReadOnly() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec =
          service.renderAutonomousChat(AgentMcpScope.ACTIONS, pinned, AgentType.CLAUDE);

      assertEquals(
          3,
          spec.script().split("agentReadOnly=true", -1).length - 1,
          "every server is fenced, or the unattended turn could mutate through MCP");
    }

    @Test
    void anInteractiveLaunchEmbedsTheSeedAndRendersTheRepl() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec =
          service.renderInteractive(AgentMcpScope.REPOSITORY, "do the thing", pinned, AgentType.CLAUDE);

      assertTrue(spec.script().startsWith("exec claude 'do the thing'"), spec.script());
      assertTrue(spec.interactive());
    }

    @Test
    void kimiTakesNoHomeOverlay() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      LaunchSpec spec = service.renderChat(AgentMcpScope.REPOSITORY, pinned, AgentType.KIMI);

      assertFalse(
          spec.environment().containsKey("HOME"), "Kimi reads KIMI_CODE_HOME, set container-wide");
      assertEquals("exec kimi acp", spec.script());
    }

    @Test
    void activityTrackingOffStillWiresTheLineageHook() {
      activityTracking = false;
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script = service.renderChat(AgentMcpScope.REPOSITORY, pinned, AgentType.CLAUDE).script();

      assertTrue(script.contains("\"SessionStart\""), "lineage is not optional");
      assertFalse(script.contains("\"UserPromptSubmit\""), "the turn-boundary hooks are");
    }

    @Test
    void theLoginTerminalOverlaysTheRightHomeForEachHarness() {
      assertEquals(CLAUDE_MOUNT, service().renderLogin(AgentType.CLAUDE).environment().get("HOME"));
      assertEquals("exec claude", service().renderLogin(AgentType.CLAUDE).script());
      assertEquals(
          CLAUDE_MOUNT + "/.kimi-code",
          service().renderLogin(AgentType.KIMI).environment().get("KIMI_CODE_HOME"));
      assertEquals("exec kimi login", service().renderLogin(AgentType.KIMI).script());
    }
  }

  // --- session lineage --------------------------------------------------------------------------

  @Nested
  class Lineage {

    @Test
    void aFreshClaudeLaunchPinsANewSession() {
      AgentLaunchService.PinnedSession pinned = service().pinSession(null, false, AgentType.CLAUDE);

      assertNotNull(pinned.ref());
      assertEquals(AgentSessionSource.PINNED, pinned.ref().source());
      assertNotNull(pinned.commandId());
    }

    @Test
    void aFreshKimiLaunchPinsNothingBecauseItCannot() {
      AgentLaunchService.PinnedSession pinned = service().pinSession(null, false, AgentType.KIMI);

      assertNull(pinned.ref(), "the id arrives later, on the SessionStart hook");
      assertNotNull(pinned.commandId());
    }

    @Test
    void resumeOfASessionFromAPreviousContainerIsRefused() {
      // THE behaviour change of this move. CommandStore does not outlive the container, so it
      // cannot vouch for a session an earlier one drove. It fails closed: refused, not allowed.
      AgentLaunchService service = service();

      InvalidCommandRequestException refused =
          assertThrows(
              InvalidCommandRequestException.class,
              () ->
                  service.pinSession(
                      "33333333-3333-3333-3333-333333333333", false, AgentType.CLAUDE));

      assertTrue(refused.getMessage().contains("not started in this container"), refused.getMessage());
    }

    @Test
    void resumeOfAKnownSessionContinuesItInPlace() {
      commands.ownedSessions.put("33333333-3333-3333-3333-333333333333", "CLAUDE");

      AgentLaunchService.PinnedSession pinned =
          service().pinSession("33333333-3333-3333-3333-333333333333", false, AgentType.CLAUDE);

      assertEquals(AgentSessionSource.RESUMED, pinned.ref().source());
      assertEquals("33333333-3333-3333-3333-333333333333", pinned.ref().sessionId());
    }

    @Test
    void aForkBranchesIntoAFreshIdAndRecordsItsOrigin() {
      commands.ownedSessions.put("33333333-3333-3333-3333-333333333333", "CLAUDE");

      AgentLaunchService.PinnedSession pinned =
          service().pinSession("33333333-3333-3333-3333-333333333333", true, AgentType.CLAUDE);

      assertEquals(AgentSessionSource.FORKED, pinned.ref().source());
      assertEquals("33333333-3333-3333-3333-333333333333", pinned.ref().forkedFromSessionId());
      assertFalse(pinned.ref().sessionId().equals("33333333-3333-3333-3333-333333333333"));
    }

    @Test
    void kimiRefusesToFork() {
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");

      assertThrows(
          InvalidCommandRequestException.class,
          () -> service().pinSession(KIMI_SESSION, true, AgentType.KIMI));
    }

    @Test
    void aResumeKeepsTheResumedSessionsHarnessOverTheDefault() {
      // You cannot resume a Kimi session under Claude: the transcript layout and auth probe differ.
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");
      defaultType = AgentType.CLAUDE;

      Command command =
          service()
              .launchChat(
                  new AgentLaunchRequest(
                      AgentMcpScope.REPOSITORY,
                      AgentLaunchMode.CHAT,
                      null,
                      KIMI_SESSION,
                      false,
                      false,
                      AgentType.CLAUDE));

      assertEquals("KIMI", commands.last().agentType(), "the session's harness wins");
      assertNotNull(command);
    }

    @Test
    void aMalformedSessionIdIsARequestError() {
      commands.ownedSessions.put("not-a-uuid", "CLAUDE");

      assertThrows(
          InvalidCommandRequestException.class,
          () -> service().pinSession("not-a-uuid", false, AgentType.CLAUDE));
    }
  }

  // --- launch flow ------------------------------------------------------------------------------

  @Nested
  class Flow {

    @Test
    void aChatLaunchNamesItselfAfterTheScopeAndHarness() {
      service().launchChat(chat(AgentMcpScope.ACTIONS));

      assertEquals("Claude Code (actions + repository MCP)", commands.last().name());
      assertEquals(CommandKind.CHAT, commands.last().kind());
      assertEquals("CLAUDE", commands.last().agentType());
    }

    @Test
    void kimiChatsCarryTheAcpProtocolFactoryAndClaudeChatsDoNot() {
      defaultType = AgentType.KIMI;
      service().launchChat(chat(AgentMcpScope.REPOSITORY));
      assertNotNull(commands.last().protocolFactory(), "Kimi has no stdin chat mode");

      defaultType = AgentType.CLAUDE;
      service().launchChat(chat(AgentMcpScope.REPOSITORY));
      assertNull(commands.last().protocolFactory(), "null means the default stream-json transport");
    }

    @Test
    void aSeedIsDeliveredAsTheFirstUserTurn() {
      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY, AgentLaunchMode.CHAT, "start here", null, false, false, null));

      assertEquals(List.of("start here"), commands.chatSends);
    }

    @Test
    void deliverTaskPromptSeedsTheBootstrapInsteadOfTheLiteral() {
      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY, AgentLaunchMode.CHAT, "ignored", null, false, true, null));

      assertEquals(
          List.of(AgentLaunchService.TASK_PROMPT_BOOTSTRAP),
          commands.chatSends,
          "the caller owns the draft now, so its word is taken");
    }

    @Test
    void aBlankSeedSendsNothing() {
      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY, AgentLaunchMode.CHAT, "   ", null, false, false, null));

      assertTrue(commands.chatSends.isEmpty());
    }

    @Test
    void aSignedOutAgentRedirectsToTheLoginTerminal() {
      loggedIn = false;

      service().launchChat(chat(AgentMcpScope.REPOSITORY));

      assertEquals("Claude sign-in", commands.last().name());
      assertEquals(CommandKind.TERMINAL, commands.last().kind());
      assertTrue(commands.last().interactive(), "the operator finishes OAuth over a real PTY");
      assertTrue(commands.chatSends.isEmpty(), "nothing is seeded into a login terminal");
    }

    @Test
    void anAutonomousRunAlwaysSeedsTheBootstrap() {
      service().launchAutonomous("Resolve conflicts");

      assertEquals("Resolve conflicts", commands.last().name());
      assertEquals(List.of(AgentLaunchService.TASK_PROMPT_BOOTSTRAP), commands.chatSends);
    }

    @Test
    void anInteractiveLaunchIsATerminalCommand() {
      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  null,
                  false,
                  false,
                  null));

      assertEquals(CommandKind.TERMINAL, commands.last().kind());
      assertEquals("Claude Code terminal (repository MCP)", commands.last().name());
    }

    @Test
    void launchDefaultsToChatWhenNoModeIsGiven() {
      service()
          .launch(
              new AgentLaunchRequest(AgentMcpScope.REPOSITORY, null, null, null, false, false, null));

      assertEquals(CommandKind.CHAT, commands.last().kind());
    }

    @Test
    void aMissingScopeAndAForkWithoutAResumeAreRequestErrors() {
      assertThrows(InvalidCommandRequestException.class, () -> service().launch(chat(null)));
      assertThrows(InvalidCommandRequestException.class, () -> service().launch(null));
      assertThrows(
          InvalidCommandRequestException.class,
          () ->
              service()
                  .launch(
                      new AgentLaunchRequest(
                          AgentMcpScope.REPOSITORY,
                          AgentLaunchMode.CHAT,
                          null,
                          null,
                          true,
                          false,
                          null)));
    }
  }

  // --- the ACP session config -------------------------------------------------------------------

  @Nested
  class AcpConfig {

    @Test
    void scopedServersRideSessionNewWithBareToolNames() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      AcpSessionConfig config = service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, pinned);

      assertEquals("/workspace", config.cwd());
      assertEquals(2, config.mcpServers().size());
      AcpSessionConfig.AcpMcpServer server = config.mcpServers().get(0);
      assertEquals("repository", server.name());
      assertTrue(
          server.enabledTools().contains("taskPrompt"),
          "kimi takes bare names, not the mcp__server__ form: " + server.enabledTools());
      assertFalse(server.enabledTools().contains("mcp__repository__taskPrompt"));
      assertEquals("observability", config.mcpServers().get(1).name());
      assertTrue(config.mcpServers().get(1).enabledTools().contains("telemetryErrors"));
    }

    @Test
    void aResumedSessionIsCarriedAndAFreshOneIsNot() {
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");
      AgentLaunchService service = service();

      assertEquals(
          KIMI_SESSION,
          service
              .buildAcpSessionConfig(
                  AgentMcpScope.REPOSITORY, service.pinSession(KIMI_SESSION, false, AgentType.KIMI))
              .resumeSessionId());
      assertNull(
          service
              .buildAcpSessionConfig(
                  AgentMcpScope.REPOSITORY, service.pinSession(null, false, AgentType.KIMI))
              .resumeSessionId());
    }

    @Test
    void theAutonomousVariantMarksTheUrlsReadOnly() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      AcpSessionConfig config =
          service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, pinned, true);

      assertTrue(config.mcpServers().get(0).url().contains("agentReadOnly=true"));
    }
  }
}
