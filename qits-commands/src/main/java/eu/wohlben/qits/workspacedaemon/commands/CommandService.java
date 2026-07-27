package eu.wohlben.qits.workspacedaemon.commands;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Launching, inspecting and terminating commands — the entry point the daemon's HTTP API sits on.
 *
 * <h2>What the move removed</h2>
 *
 * <p>The host's {@code prepare} did five things before it could spawn anything, all of them
 * consequences of launching into a container from outside it: reject path traversal in the
 * workspace id, read the branch out of the database in its own transaction, {@code ensureContainer}
 * to re-materialize a possibly-missing target, compute the container's name, and {@code docker exec
 * git rev-parse HEAD} to snapshot the commit. All five collapse into {@link WorkspaceContext},
 * which the daemon answers from what it already knows.
 *
 * <p>Five public methods are gone, not moved: {@code launchService}, {@code beginServiceRun},
 * {@code followService}, {@code launchAndAwait} and {@code launchScriptAndAwait}.
 * migration-plan.md §3.3 listed them as dead code to drop — residue of the pre-daemon host-exec
 * service supervisor — and §3.3 is emphatic about actually dropping them, because the last
 * extraction left that list in place and an imported socket promptly called back into it, turning
 * host-side service supervision back on after it had been deliberately moved into the daemon. They
 * had no production callers, only tests. Services are {@code ServiceSupervisor}'s, in the daemon
 * module, and stay that way.
 */
public final class CommandService {

  /**
   * A session id must be a UUID (Claude) or the harness's own shape (Kimi) before it can become a
   * transcript filename. The hook endpoint is unauthenticated, like the git host, so this is
   * validated rather than trusted.
   */
  private static final String UUID_PATTERN =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  private static final String KIMI_SESSION_PATTERN = "[A-Za-z0-9_-]{1,64}";

  private final CommandStore store;
  private final CommandRegistry registry;
  private final CommandLifecycleService lifecycle;
  private final CommandLogService commandLogService;
  private final WorkspaceContext workspace;
  private final ActionResolver actions;
  private final OtelEnvironment otelEnvironment;

  public CommandService(
      CommandStore store,
      CommandRegistry registry,
      CommandLifecycleService lifecycle,
      CommandLogService commandLogService,
      WorkspaceContext workspace,
      ActionResolver actions,
      OtelEnvironment otelEnvironment) {
    this.store = store;
    this.registry = registry;
    this.lifecycle = lifecycle;
    this.commandLogService = commandLogService;
    this.workspace = workspace;
    this.actions = actions;
    this.otelEnvironment = otelEnvironment;
  }

  /**
   * What a launch needs regardless of where it came from: an action or a coding agent. {@code
   * actionId} is null for agent launches (they are not backed by an action). {@code otel} injects
   * the OTLP exporter environment. {@code commandId} is a caller-chosen id (agent launches render
   * it into the session-report hook URL before the command exists; null generates one) and {@code
   * agentSession} the first entry of an agent launch's session list. {@code agentType} is the
   * coding-agent harness recorded on the command (null for non-agent launches).
   */
  private record LaunchDescriptor(
      String actionId,
      String name,
      String script,
      boolean interactive,
      Map<String, String> environment,
      CommandKind kind,
      boolean otel,
      String commandId,
      AgentSessionRef agentSession,
      String agentType) {

    static LaunchDescriptor of(ActionResolver.ResolvedAction action) {
      return new LaunchDescriptor(
          action.id(),
          action.name(),
          action.executeScript(),
          action.interactive(),
          action.environment(),
          CommandKind.TERMINAL,
          false,
          null,
          null,
          null);
    }
  }

  /** A recorded RUNNING command plus the environment its process needs. */
  private record Prepared(Command command, Map<String, String> env) {}

  /** Launch a declared action as a registry command and return it; a terminal attaches by its id. */
  public Command launch(String actionId) {
    ActionResolver.ResolvedAction action =
        actions
            .resolve(actionId)
            .orElseThrow(
                () ->
                    new InvalidCommandRequestException(
                        "No action declared in .qits-config.yml with id: " + actionId));
    Prepared prepared = prepare(LaunchDescriptor.of(action));
    registry.spawn(
        prepared.command().id(),
        prepared.command().executeScript(),
        prepared.env(),
        this::onExit,
        commandLogService);
    return prepared.command();
  }

  /**
   * Launch a coding-agent session (rendered by {@code qits-coding-agents}) as a registry command.
   * Like {@link #launch} but not backed by an action — the caller supplies the display name, the
   * rendered script and an environment overlay. {@code commandId} pre-names the command (it is
   * rendered into the script's session-report hook URL), {@code agentSession} becomes the first
   * entry of its session list, and {@code extraExitListener} runs after the status write (the
   * transcript sweep). The first three may be null.
   */
  public Command launchAgent(
      String name,
      String script,
      boolean interactive,
      Map<String, String> environment,
      String commandId,
      AgentSessionRef agentSession,
      CommandExitListener extraExitListener,
      String agentType) {
    Prepared prepared =
        prepare(
            new LaunchDescriptor(
                null,
                name,
                script,
                interactive,
                environment,
                CommandKind.TERMINAL,
                false,
                commandId,
                agentSession,
                agentType));
    registry.spawn(
        prepared.command().id(),
        prepared.command().executeScript(),
        prepared.env(),
        compose(extraExitListener),
        commandLogService);
    return prepared.command();
  }

  /**
   * Launch a chat session as a registry command (kind {@code CHAT}). Like {@link #launchAgent} but
   * the process is driven over plain pipes and rendered as a conversation; the command is
   * re-attachable and its events are captured as its log. {@code protocolFactory} null means the
   * default stream-json transport; Kimi passes its ACP client.
   */
  public Command launchChat(
      String name,
      String script,
      Map<String, String> environment,
      String commandId,
      AgentSessionRef agentSession,
      CommandExitListener extraExitListener,
      ChatProtocolFactory protocolFactory,
      String agentType) {
    Prepared prepared =
        prepare(
            new LaunchDescriptor(
                null,
                name,
                script,
                false,
                environment,
                CommandKind.CHAT,
                false,
                commandId,
                agentSession,
                agentType));
    registry.spawnChat(
        prepared.command().id(),
        prepared.command().executeScript(),
        prepared.env(),
        protocolFactory,
        compose(extraExitListener),
        commandLogService,
        commandLogService);
    return prepared.command();
  }

  /** The status write first, then the extra listener — so e.g. the transcript sweep sees it ended. */
  private CommandExitListener compose(CommandExitListener extra) {
    if (extra == null) {
      return this::onExit;
    }
    return (commandId, exitCode, terminatedManually) -> {
      onExit(commandId, exitCode, terminatedManually);
      extra.onExit(commandId, exitCode, terminatedManually);
    };
  }

  /** Record a RUNNING command and resolve its environment — but don't spawn. */
  private Prepared prepare(LaunchDescriptor descriptor) {
    // Recorded first. If the spawn fails afterwards the registry marks it via the exit listener, so
    // a command is never left dangling in RUNNING with no process.
    Command command =
        lifecycle.createRunning(
            workspace.branch(),
            workspace.commitHash(),
            descriptor.actionId(),
            descriptor.name(),
            descriptor.script(),
            descriptor.interactive(),
            descriptor.kind(),
            descriptor.commandId(),
            descriptor.agentSession(),
            descriptor.agentType());

    Map<String, String> env = new HashMap<>();
    env.put("TERM", "xterm-256color");
    if (descriptor.otel() && otelEnvironment != null) {
      // The command id exists here — each (re)launch exports with its own qits.command.id. The
      // descriptor's overlay stays last so an explicit user OTEL_* var wins.
      env.putAll(
          otelEnvironment.forLaunch(
              workspace.repoId(), workspace.workspaceId(), command.id(), descriptor.name()));
    }
    env.putAll(descriptor.environment());
    return new Prepared(command, env);
  }

  /** The single bridge from a process ending to its recorded status. */
  private void onExit(String commandId, int exitCode, boolean terminatedManually) {
    if (terminatedManually) {
      lifecycle.markTerminated(commandId, exitCode);
    } else {
      lifecycle.markExited(commandId, exitCode);
    }
  }

  /** Terminate a running command; returns its (now finished) state. No-op if already finished. */
  public Command terminate(String commandId) {
    get(commandId); // validates existence
    registry.terminate(commandId); // kills + joins the reader, whose exit listener marks TERMINATED
    return get(commandId); // fresh read reflecting the transition
  }

  public Command get(String commandId) {
    return store
        .find(commandId)
        .orElseThrow(() -> new CommandNotFoundException("Command not found: " + commandId));
  }

  /** A command's captured per-line log, optionally severity-filtered. */
  public List<CommandLogLine> log(String commandId, LogSeverity severity) {
    return log(commandId, severity, null);
  }

  /**
   * A command's captured per-line log, optionally severity- and channel-filtered. The channel filter
   * separates intercepted stdio ({@code OUTPUT}) from the imported agent transcript ({@code
   * TRANSCRIPT}) so neither view double-renders the other. For a chat, {@code TRANSCRIPT} means "the
   * whole conversation": the transcript merged with its captured error results, or the full {@code
   * OUTPUT} stream while the transcript has not been imported yet — scoped to {@code CHAT} so a
   * terminal agent's transcript view never falls back to raw terminal bytes.
   */
  public List<CommandLogLine> log(String commandId, LogSeverity severity, LogChannel channel) {
    Command command = get(commandId); // validates existence (404 if unknown)
    if (channel == LogChannel.TRANSCRIPT && command.kind() == CommandKind.CHAT) {
      return commandLogService.chatLog(commandId, severity);
    }
    return commandLogService.log(commandId, severity, channel);
  }

  /**
   * Ingest a SessionStart hook report from the agent running in this workspace. The hook endpoint is
   * reachable without the API token — it is loopback-only, and the agent's own process is what calls
   * it — so everything is validated: the id must look like a session id (it later becomes a
   * transcript filename) and the command must exist and be running.
   */
  public Command reportAgentSession(String commandId, String sessionId, String transcriptPath) {
    if (sessionId == null
        || (!sessionId.matches(UUID_PATTERN) && !sessionId.matches(KIMI_SESSION_PATTERN))) {
      throw new InvalidCommandRequestException("Invalid session id: " + sessionId);
    }
    return lifecycle.recordAgentSessionReport(commandId, sessionId, transcriptPath);
  }

  /**
   * The commands this container has run, newest first, optionally narrowed by status.
   *
   * <p>The host's version took {@code repoId} and {@code workspaceId} filters and rejected a
   * workspace filter without a repo, because workspace slugs are only unique within a repository
   * and one database held every workspace's commands. Both filters are gone: this store holds one
   * workspace's commands and nothing else.
   */
  public List<Command> list(CommandStatus status) {
    return status == null ? store.listByLaunchedAtDesc() : store.findByStatus(status);
  }

  /** Every action the checkout declares — what {@code POST /commands} will accept as an id. */
  public List<ActionResolver.ResolvedAction> availableActions() {
    return actions.actions();
  }
}
