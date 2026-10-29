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
import eu.wohlben.qits.commands.LogChannel;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
 * agent the host names in {@code /agent-worktrees/{agentId}}, always {@value #AGENT_ID}. A state
 * with an agent started answers a third, {@code commandId}: its harness's command, the id the
 * {@code /commands/{commandId}} doors and both sockets take. That one is minted by the launch and
 * is <em>not</em> fixed — a pact takes it from the state, and the recording freezes it.
 *
 * <p><b>No real harness ever starts.</b> Every launch goes through {@link StubHarness}, which swaps
 * the harness script for a stub that reads its input and stays up. What is under contract is the
 * route and the answer, not the harness. Nor does any other binary run: {@link #processes} stands
 * in for {@code claude auth status}, {@code claude plugin install} and the refinement call.
 *
 * <p>The states the landing app's doors need (epic qits-1230) sit beside the service's: the harness
 * signed out, an agent chatting, an agent that has taken turns, a worktree with files, plugins on
 * the credential volume.
 *
 * <p>Close it after each interaction: it terminates what it launched and stops its Vert.x.
 */
final class ProviderStates implements AutoCloseable {

  /** No agent yet, and the harness signed in: a start launches. */
  static final String SIGNED_IN = "a workspace daemon with a signed-in harness";

  /**
   * {@link #AGENT_ID} started, its interactive harness running in its worktree, its {@code
   * SessionStart} tagged so {@code interruptAgent} (qits-1667) finds it already started.
   */
  static final String AGENT_RUNNING = "a workspace daemon with an agent running";

  /**
   * No agent yet, and nobody signed in on the credential volume: {@code claude auth status} fails,
   * the capability report says so, and the sign-in terminal is the way out.
   */
  static final String SIGNED_OUT = "a workspace daemon with a harness nobody signed in";

  /** {@link #AGENT_ID} started in chat mode: its harness a {@code CHAT} command, running. */
  static final String AGENT_CHATTING = "a workspace daemon with an agent chatting";

  /**
   * {@link #AGENT_ID} running in its terminal, its transcript holding two turns: a request, a tool
   * call and its result, and a reply ({@link #TRANSCRIPT}).
   */
  static final String AGENT_WITH_TRANSCRIPT =
      "a workspace daemon with an agent that has taken turns";

  /**
   * {@link #AGENT_ID} running, its wrapper worktree holding a Maven project — a source file and the
   * test that reaches it — and a gitignored {@code node_modules/} ({@link #writeFiles}).
   */
  static final String AGENT_WITH_FILES =
      "a workspace daemon with an agent whose worktree holds files";

  /**
   * The same worktree as {@link #AGENT_WITH_FILES}, named for what its recordings open: the ignored
   * directory, one level at a time. A state of its own only because the index keys a recording by
   * state and operation, and {@code listFiles} is recorded at the root there.
   */
  static final String AGENT_WITH_IGNORED_DIRECTORY =
      "a workspace daemon with an agent whose worktree has an ignored directory";

  /** No agent; the credential volume records one plugin enabled and one switched off. */
  static final String PLUGINS_INSTALLED = "a workspace daemon with plugins installed";

  /**
   * {@link #AGENT_ID} running, its wrapper worktree holding uncommitted files ({@link
   * #writeFiles}): the cleanup check says what would be lost, and a removal that is not forced is
   * refused with it (qits-1149: what qits-workspaces' cleanup and discard branch on).
   */
  static final String AGENT_WITH_LEFTOVER_WORK =
      "a workspace daemon with an agent whose worktree holds uncommitted work";

  /**
   * {@link #AGENT_ID} running with a clean worktree, its harness still working by its hooks (a
   * background subagent after the main turn ended, qits-1233): the cleanup check says {@code busy}
   * and a removal that is not forced is refused.
   */
  static final String AGENT_BUSY = "a workspace daemon with an agent busy in the background";

  /** Every state, in one place, for the verification's guard. */
  private static final Set<String> NAMES =
      Set.of(
          SIGNED_IN,
          SIGNED_OUT,
          AGENT_RUNNING,
          AGENT_CHATTING,
          AGENT_WITH_TRANSCRIPT,
          AGENT_WITH_FILES,
          AGENT_WITH_IGNORED_DIRECTORY,
          PLUGINS_INSTALLED,
          AGENT_WITH_LEFTOVER_WORK,
          AGENT_BUSY);

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

  /** Where a real daemon's harness home is mounted ({@code qits.workspace.claude-mount}). */
  static final String CLAUDE_HOME = "/claude-home";

  /** The marketplace {@code claude plugin install} appends, as the volume records it. */
  static final String MARKETPLACE = "@claude-plugins-official";

  /** What the stand-in for the refinement call answers. */
  static final String REFINED_PROMPT =
      "Fix the login form so a user name with surrounding spaces still signs in.";

  /** What every capability report in these states carries, beside the sign-in state. */
  static final String HARNESS_VERSION = "2.1.0 (Claude Code)";

  /**
   * The transcript {@link #AGENT_WITH_TRANSCRIPT} holds: Claude's own JSONL lines, as the tail
   * imports them, with nothing in them that moves between runs.
   */
  static final List<String> TRANSCRIPT =
      List.of(
          transcriptLine("user", text("Fix the login")),
          transcriptLine(
              "assistant",
              text("Reading the login service first."),
              new JsonObject()
                  .put("type", "tool_use")
                  .put("id", "toolu_01")
                  .put("name", "Read")
                  .put("input", new JsonObject().put("file_path", "src/main/java/app/Login.java"))),
          transcriptLine(
              "user",
              new JsonObject()
                  .put("type", "tool_result")
                  .put("tool_use_id", "toolu_01")
                  .put("content", "class Login {}")
                  .put("is_error", false)),
          transcriptLine("assistant", text("Fixed: the user name is trimmed before the lookup.")));

  /** One transcript line: a message of {@code role} carrying {@code blocks}. */
  private static String transcriptLine(String role, JsonObject... blocks) {
    return new JsonObject()
        .put("type", role)
        .put(
            "message",
            new JsonObject().put("role", role).put("content", new JsonArray(List.of(blocks))))
        .encode();
  }

  private static JsonObject text(String text) {
    return new JsonObject().put("type", "text").put("text", text);
  }

  /**
   * Where the imported transcript's sequences start — the harness library's {@code
   * AgentTranscriptService.TRANSCRIPT_SEQ_BASE} (2^40), which is package-private there. Above every
   * output sequence, so a transcript line never collides with one.
   */
  static final long TRANSCRIPT_SEQUENCE_BASE = 1L << 40;

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

  private final Path dir;
  private final Path volume;
  private final Path claudeMount;
  private final Vertx vertx;
  private final WorkspaceApi api;
  private final AgentWorktrees worktrees;
  private final CommandService commands;
  private final CommandLogService logs;
  private final AgentRuntime runtime;

  /**
   * Whether anybody is signed in on the credential volume. Read by {@link #processes} on every
   * {@code claude auth status} and by the capability report on every {@code /agents/available}; a
   * state that signs the harness out flips it before anything is asked.
   */
  private volatile boolean signedIn = true;

  /**
   * Whether the running harness has work in flight by its hooks — {@code HookWebhook#inFlight} in a
   * real daemon. {@link #AGENT_BUSY} sets it; every other state leaves it false.
   */
  private volatile boolean harnessBusy = false;

  /**
   * Every binary the daemon shells, played rather than run. {@code claude auth status} answers what
   * {@link #signedIn} says; {@code claude plugin install <id>@<marketplace>} records the plugin on
   * the volume's {@code settings.json} the way the CLI does; anything else — the refinement call —
   * succeeds with {@link #REFINED_PROMPT}.
   */
  private final ProcessRunner processes =
      (command, cwd, env, timeout) -> {
        if (command.equals(List.of("claude", "auth", "status"))) {
          return signedIn
              ? new ProcessRunner.Result(0, "{\"loggedIn\":true}", "", false)
              : new ProcessRunner.Result(1, "{\"loggedIn\":false}", "Not logged in", false);
        }
        if (command.size() == 4
            && command.subList(0, 3).equals(List.of("claude", "plugin", "install"))) {
          enablePlugin(command.get(3), true);
          return new ProcessRunner.Result(0, "Installed " + command.get(3), "", false);
        }
        return new ProcessRunner.Result(0, REFINED_PROMPT, "", false);
      };

  /** The boot-time capability report, as the probe would have left it for {@link #signedIn}. */
  private final Supplier<List<HarnessCapabilities>> capabilities =
      () ->
          List.of(
              new HarnessCapabilities(
                  AgentType.CLAUDE,
                  HARNESS_VERSION,
                  HarnessCapabilities.CLAUDE_MODELS,
                  false,
                  true,
                  HarnessCapabilities.CLAUDE_EFFORT_LEVELS,
                  signedIn,
                  signedIn
                      ? "Signed in on the shared credential volume."
                      : "Nobody is signed in on the shared credential volume. Open the sign-in"
                          + " terminal to sign in once for every container.",
                  false,
                  null));

  private ProviderStates(Path dir) throws Exception {
    this.dir = dir;
    GitFixtures.Estate estate = GitFixtures.estate(dir);
    volume = estate.base().getParent();
    claudeMount = Files.createDirectories(dir.resolve("claude"));
    worktrees = new AgentWorktrees(estate.base(), estate.agents(), GitFixtures.WRAPPER);
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
    logs = new CommandLogService(store, null);
    CommandLifecycleService lifecycle = new CommandLifecycleService(store, null);
    AgentSessionStore sessions = new AgentSessionStore();
    CommandRegistry registry = new CommandRegistry(volume, 2_000);
    commands = new CommandService(store, registry, lifecycle, logs, WORKSPACE, new NoActions());
    AgentTranscriptService transcripts =
        new AgentTranscriptService(store, logs, sessions, claudeMount.toString(), null);
    AgentTranscriptTailService tail = new AgentTranscriptTailService(transcripts, logs);
    eu.wohlben.qits.agents.AgentAuthStatus auth =
        new eu.wohlben.qits.agents.AgentAuthStatus(processes, claudeMount.toString(), volume);
    AgentCommands shared = new StubHarness(new CommandsAgentCommands(commands, registry, store));
    AgentLaunchService launch =
        new AgentLaunchService(
            shared,
            auth,
            transcripts,
            tail,
            DEFAULTS,
            new WorkspaceMcpServers(Optional.empty(), Optional.empty()),
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
                    WorkspaceMcpServers.forAgent(Optional.empty(), seat),
                    WORKSPACE,
                    claudeMount.toString(),
                    HOOKS_PORT),
            claudeMount.toString(),
            TerminalTurnGate.DEFAULT_TIMEOUT,
            commandId -> harnessBusy);
    api.wireCommands(commands, registry, WORKSPACE);
    api.wireAgents(
        runtime,
        launch,
        new AgentSessionQueryService(store, sessions),
        new AgentPluginService(processes, claudeMount.toString(), volume, DEFAULTS),
        new PromptRefinementService(processes, WORKSPACE, DEFAULTS, claudeMount.toString(), volume),
        DEFAULTS,
        "contract",
        capabilities);
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
    return NAMES;
  }

  /** The params every state answers, sorted by name. */
  static Map<String, String> params() {
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ID);
    params.put("workspaceRowId", WORKSPACE_ROW_ID);
    return params;
  }

  /** {@link #params()}, and the started agent's harness command. */
  private static Map<String, String> params(String commandId) {
    Map<String, String> params = params();
    params.put("commandId", commandId);
    return params;
  }

  /** Puts this daemon into {@code state} and answers its params. */
  Map<String, String> setUp(String state) {
    return switch (state) {
      case SIGNED_IN -> params();
      case SIGNED_OUT -> {
        signedIn = false;
        yield params();
      }
      case PLUGINS_INSTALLED -> {
        enablePlugin("jdtls-lsp" + MARKETPLACE, true);
        enablePlugin("typescript-lsp" + MARKETPLACE, false);
        yield params();
      }
      case AGENT_RUNNING -> {
        String commandId = startAgent(AgentLaunchMode.INTERACTIVE);
        // interruptAgent (qits-1667) is refused until the harness's own SessionStart has arrived
        // (TerminalTurnGate); mark it here so the state is a started TERMINAL harness, the same
        // as every other AGENT_RUNNING recording, none of which looks at that flag.
        runtime.tag(new AgentActivity(commandId, null, "IDLE", "SessionStart", "startup", null, 1L));
        yield params(commandId);
      }
      case AGENT_CHATTING -> params(startAgent(AgentLaunchMode.CHAT));
      case AGENT_WITH_TRANSCRIPT -> {
        String commandId = startAgent(AgentLaunchMode.INTERACTIVE);
        importTranscript(commandId);
        yield params(commandId);
      }
      case AGENT_BUSY -> {
        String commandId = startAgent(AgentLaunchMode.INTERACTIVE);
        harnessBusy = true;
        yield params(commandId);
      }
      case AGENT_WITH_FILES, AGENT_WITH_IGNORED_DIRECTORY, AGENT_WITH_LEFTOVER_WORK -> {
        String commandId = startAgent(AgentLaunchMode.INTERACTIVE);
        writeFiles(worktrees.wrapperDir(AGENT_ID));
        yield params(commandId);
      }
      default -> throw new IllegalArgumentException("No provider state named '" + state + "'");
    };
  }

  int port() {
    return api.actualPort();
  }

  /** The temporary volume's path, which {@link #VOLUME} stands for in a recording. */
  Path volume() {
    return volume;
  }

  /** The temporary harness home's path, which {@link #CLAUDE_HOME} stands for in a recording. */
  Path claudeMount() {
    return claudeMount;
  }

  /**
   * The agent started as qits-workspaces starts it, on the dispatch surface, in {@code mode}.
   * Answers its harness command's id.
   */
  private String startAgent(AgentLaunchMode mode) {
    return runtime
        .start(
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
                mode,
                null))
        .commandId();
  }

  /**
   * {@link #TRANSCRIPT} on the command's {@code TRANSCRIPT} channel, as the tail leaves it. Written
   * straight to the log rather than through a JSONL file the tail polls, because the tail is
   * asynchronous and a recording must not depend on a poll landing in time; what the door answers
   * is the log either way.
   */
  private void importTranscript(String commandId) {
    for (int i = 0; i < TRANSCRIPT.size(); i++) {
      logs.append(
          commandId,
          TRANSCRIPT_SEQUENCE_BASE + i,
          LogChannel.TRANSCRIPT,
          TRANSCRIPT.get(i),
          Instant.parse(Freezer.FROZEN_INSTANT));
    }
  }

  /**
   * A Maven project with one source file and the test that reaches it, and a gitignored {@code
   * node_modules/} holding a file and a directory — what the file browser's tree, viewer, test
   * links and ignored rows are drawn from. Left uncommitted: the routes read the working tree.
   */
  private static void writeFiles(Path root) {
    try {
      Files.writeString(root.resolve(".gitignore"), "node_modules/\n");
      Files.writeString(
          root.resolve("pom.xml"),
          "<project><artifactId>app</artifactId><!-- quarkus --></project>\n");
      Files.createDirectories(root.resolve("src/main/java/app"));
      Files.createDirectories(root.resolve("src/test/java/app"));
      Files.writeString(
          root.resolve("src/main/java/app/Login.java"),
          "package app;\n\n"
              + "class Login {\n"
              + "  String user(String name) {\n"
              + "    return name.strip();\n"
              + "  }\n"
              + "}\n");
      Files.writeString(
          root.resolve("src/test/java/app/LoginTest.java"), "package app;\n\nclass LoginTest {}\n");
      Files.createDirectories(root.resolve("node_modules/left-pad"));
      Files.writeString(root.resolve("node_modules/.package-lock.json"), "{}\n");
      Files.writeString(root.resolve("node_modules/left-pad/index.js"), "module.exports = 1;\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Records {@code pluginId} on the volume's {@code settings.json}, as the CLI does. */
  private void enablePlugin(String pluginId, boolean enabled) {
    Path settings = claudeMount.resolve(".claude/settings.json");
    try {
      JsonObject json =
          Files.isRegularFile(settings)
              ? new JsonObject(Files.readString(settings))
              : new JsonObject();
      JsonObject plugins = json.getJsonObject("enabledPlugins", new JsonObject());
      json.put("enabledPlugins", plugins.put(pluginId, enabled));
      Files.createDirectories(settings.getParent());
      Files.writeString(settings, json.encode());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
