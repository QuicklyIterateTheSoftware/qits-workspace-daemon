package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentDefaults;
import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentPluginService;
import eu.wohlben.qits.agents.AgentSessionQueryService;
import eu.wohlben.qits.agents.AgentSessionStore;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentTranscriptService;
import eu.wohlben.qits.agents.AgentTranscriptTailService;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.CommandsAgentCommands;
import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.agents.McpEndpoints;
import eu.wohlben.qits.agents.ProcessRunner;
import eu.wohlben.qits.agents.PromptRefinementService;
import eu.wohlben.qits.commands.ActionResolver;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.CommandStore;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * <b>The provider states this daemon records and verifies</b> (tickets qits-1149, qits-1152): one
 * real {@link WorkspaceApi} per state, on an ephemeral loopback port, mounted under the base path
 * qits-workspaces injects ({@code /workspaces/container/<row id>}), wired the way {@link
 * AgentsApiTest} wires it: real git origins ({@link GitFixtures}), real agent worktrees, a real
 * {@link AgentRuntime}.
 *
 * <p>Every state answers two params: {@code workspaceRowId}, the row id qits-workspaces keys the
 * container on and puts in the path, always {@value #WORKSPACE_ROW_ID}; and {@code agentId}, the
 * agent the host names in {@code /agent-worktrees/{agentId}}, always {@value #AGENT_ID}.
 *
 * <p><b>No real harness ever starts.</b> Every launch goes through {@link StubHarness}, which swaps
 * the harness script for a stub that reads its input and stays up. What is under contract is the
 * route and the answer, not the harness.
 *
 * <p>Close it after each interaction: it terminates what it launched and stops its Vert.x.
 */
final class ProviderStates implements AutoCloseable {

  /** No agent yet, and the harness signed in: a start launches. */
  static final String SIGNED_IN = "a workspace daemon with a signed-in harness";

  /** {@link #AGENT_ID} started, its interactive harness running in its worktree. */
  static final String AGENT_RUNNING = "a workspace daemon with an agent running";

  /** The row id qits-workspaces keys the container on. */
  static final String WORKSPACE_ROW_ID = "1";

  /** What qits-workspaces injects as {@code QITS_WORKSPACE_DAEMON_API_BASE_PATH}. */
  static final String BASE_PATH = "/workspaces/container/" + WORKSPACE_ROW_ID;

  /** The daemon token; every request carries it, the way qits-workspaces' proxy sends it. */
  static final String TOKEN = "contract-daemon-token";

  /** The agent every state names, already in frozen form. */
  static final String AGENT_ID = "00000000-0000-4000-8000-0000000000a1";

  /** The agent's work item, already in frozen form. */
  static final String WORK_ID = "00000000-0000-4000-8000-000000000001";

  static final String ENTITY_ID = "contract-00000001-10";
  static final String WRAPPER_BRANCH = "ticket/contract-00000001-10";

  /** Where a real daemon's volume is; the temporary one is frozen to it. */
  static final String VOLUME = "/workspace";

  static final String REPOSITORY_ID = "00000000-0000-4000-8000-000000000003";
  static final String PROJECT_ID = "00000000-0000-4000-8000-000000000004";
  static final String WORKSPACE_ID = "ticket-contract-1";
  static final String BRANCH = "ticket/contract-1";
  static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  private static final WorkspaceContext WORKSPACE =
      new WorkspaceContext() {
        @Override
        public String repoId() {
          return REPOSITORY_ID;
        }

        @Override
        public String workspaceId() {
          return WORKSPACE_ID;
        }

        @Override
        public String branch() {
          return BRANCH;
        }

        @Override
        public String commitHash() {
          return COMMIT;
        }
      };

  private static final McpEndpoints ENDPOINTS =
      new McpEndpoints() {
        @Override
        public String mcpUrl(String server) {
          return switch (server) {
            case "repository" -> "http://qits:8080/projects/mcp";
            case "observability" -> "http://qits:8080/observability/mcp";
            default -> throw new IllegalArgumentException("Unknown MCP server: " + server);
          };
        }

        @Override
        public String projectId() {
          return PROJECT_ID;
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
          return false;
        }

        @Override
        public Optional<String> refinementModel() {
          return Optional.empty();
        }
      };

  /** Every probe succeeds: the harness reads as signed in, so a launch is not refused. */
  private static final ProcessRunner PROCESSES =
      (command, cwd, env, timeout) -> new ProcessRunner.Result(0, "", "", false);

  private final Path dir;
  private final Path volume;
  private final Vertx vertx;
  private final WorkspaceApi api;
  private final CommandService commands;
  private final AgentRuntime runtime;

  private ProviderStates(Path dir) throws Exception {
    this.dir = dir;
    GitFixtures.Estate estate = GitFixtures.estate(dir);
    volume = estate.base().getParent();
    Path claudeMount = Files.createDirectories(dir.resolve("claude"));
    AgentWorktrees worktrees =
        new AgentWorktrees(estate.base(), estate.agents(), GitFixtures.WRAPPER);
    worktrees.installGuards();
    vertx = Vertx.vertx();
    api = new WorkspaceApi();
    api.vertx = vertx;
    api.apiBasePath = Optional.of(BASE_PATH + "/");
    api.listen(vertx, "127.0.0.1", 0, TOKEN, worktrees, List::of)
        .toCompletionStage()
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);

    CommandStore store = new CommandStore();
    CommandLogService logs = new CommandLogService(store, null);
    CommandLifecycleService lifecycle = new CommandLifecycleService(store, null);
    AgentSessionStore sessions = new AgentSessionStore();
    CommandRegistry registry = new CommandRegistry(volume, 2_000);
    commands = new CommandService(store, registry, lifecycle, logs, WORKSPACE, new NoActions());
    AgentTranscriptService transcripts =
        new AgentTranscriptService(store, logs, sessions, claudeMount.toString(), null);
    AgentTranscriptTailService tail = new AgentTranscriptTailService(transcripts, logs);
    eu.wohlben.qits.agents.AgentAuthStatus auth =
        new eu.wohlben.qits.agents.AgentAuthStatus(PROCESSES, claudeMount.toString(), volume);
    AgentCommands shared = new StubHarness(new CommandsAgentCommands(commands, registry, store));
    AgentLaunchService launch =
        new AgentLaunchService(
            shared,
            auth,
            transcripts,
            tail,
            DEFAULTS,
            new WorkspaceMcpServers(
                ENDPOINTS, REPOSITORY_ID, WORKSPACE_ID, Optional.empty(), Optional.empty()),
            WORKSPACE,
            claudeMount.toString(),
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
                    DEFAULTS,
                    WorkspaceMcpServers.forAgent(
                        ENDPOINTS, REPOSITORY_ID, WORKSPACE_ID, Optional.empty(), seat),
                    WORKSPACE,
                    claudeMount.toString(),
                    HOOKS_PORT),
            claudeMount.toString());
    api.wireCommands(commands, registry, WORKSPACE);
    api.wireAgents(
        runtime,
        launch,
        new AgentSessionQueryService(store, sessions),
        new AgentPluginService(PROCESSES, claudeMount.toString(), volume, DEFAULTS),
        new PromptRefinementService(
            PROCESSES, WORKSPACE, DEFAULTS, claudeMount.toString(), volume),
        DEFAULTS,
        "contract",
        () -> List.of(HarnessCapabilities.shipped(AgentType.CLAUDE, "not probed here")));
  }

  private static final int HOOKS_PORT = 13337;

  /** A daemon in no state yet, listening. */
  static ProviderStates start() {
    try {
      return new ProviderStates(Files.createTempDirectory("qits-daemon-contract"));
    } catch (Exception e) {
      throw new IllegalStateException("could not start the daemon under contract", e);
    }
  }

  static Set<String> names() {
    return Set.of(SIGNED_IN, AGENT_RUNNING);
  }

  /** The params every state answers, sorted by name. */
  static Map<String, String> params() {
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ID);
    params.put("workspaceRowId", WORKSPACE_ROW_ID);
    return params;
  }

  /** Puts this daemon into {@code state} and answers its params. */
  Map<String, String> setUp(String state) {
    switch (state) {
      case AGENT_RUNNING -> startAgent();
      case SIGNED_IN -> {}
      default -> throw new IllegalArgumentException("No provider state named '" + state + "'");
    }
    return params();
  }

  int port() {
    return api.actualPort();
  }

  /** The temporary volume's path, which {@link #VOLUME} stands for in a recording. */
  Path volume() {
    return volume;
  }

  /** The agent started as qits-workspaces starts it: interactive, on the dispatch surface. */
  private void startAgent() {
    runtime.start(
        new AgentRuntime.Start(
            AGENT_ID,
            WORK_ID,
            ENTITY_ID,
            WRAPPER_BRANCH,
            AgentType.CLAUDE,
            null,
            Map.of(),
            "Work the ticket.",
            AgentSurface.TICKET_DISPATCH,
            AgentLaunchMode.INTERACTIVE,
            null));
  }

  @Override
  public void close() {
    for (Command running : commands.list(CommandStatus.RUNNING)) {
      try {
        commands.terminate(running.id());
      } catch (RuntimeException ignored) {
        // Already gone: nothing left to stop.
      }
    }
    api.close();
    runtime.close();
    try {
      vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("could not stop the daemon under contract", e);
    }
    delete(dir);
  }

  /** Best effort: a process that is still dying may write one more file into the tree. */
  private static void delete(Path dir) {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException | UncheckedIOException e) {
      // A temp directory left behind is harmless; a failed contract run over it would not be.
    }
  }

  /** This workspace declares no actions; agents are launched, not resolved from config. */
  private record NoActions() implements ActionResolver {
    @Override
    public Optional<ResolvedAction> resolve(String actionId) {
      return Optional.empty();
    }

    @Override
    public List<ResolvedAction> actions() {
      return List.of();
    }
  }
}
