package eu.wohlben.qits.workspacedaemon.agents;

import eu.wohlben.qits.workspacedaemon.agents.acp.AcpChatProtocol;
import eu.wohlben.qits.workspacedaemon.agents.acp.AcpSessionConfig;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionRef;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionSource;
import eu.wohlben.qits.workspacedaemon.commands.ChatProtocolFactory;
import eu.wohlben.qits.workspacedaemon.commands.Command;
import eu.wohlben.qits.workspacedaemon.commands.CommandExitListener;
import eu.wohlben.qits.workspacedaemon.commands.InvalidCommandRequestException;
import eu.wohlben.qits.workspacedaemon.commands.WorkspaceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Launches a coding agent into this workspace as a first-class command: rendered by a {@link
 * CodingAgent}, spawned through {@link AgentCommands}, and returned as a {@link Command} — so an
 * agent session shows up in the Commands list and is attachable and terminable like any other.
 *
 * <p>Owns the MCP scope→URL construction (the read-only allowlists and the {@code ?repositoryId=} /
 * {@code ?projectId=} query parameters). Scope ids are validated as UUIDs before being interpolated
 * into the single-quoted launch args, since the agent renderer does no escaping of its own.
 *
 * <p><strong>What the move removed.</strong> The host version had twelve injected collaborators
 * spanning five sibling domains. Inside the container:
 *
 * <ul>
 *   <li>the repository and workspace ids leave every signature — they are ambient on {@link
 *       WorkspaceContext};
 *   <li>{@code WorkspaceService.ensureContainer} is gone at all three call sites: the daemon is the
 *       container, and a running daemon is proof of it;
 *   <li>{@code RepositoryRepository} is gone — the project id is an environment value, see {@link
 *       McpEndpoints};
 *   <li>{@code QitsHostResolver} is gone — the daemon dialled qits, so it knows the address;
 *   <li>{@code WorkspacePromptDraftService} is gone — there is no draft store here, so the request
 *       carries the prompt and {@code deliverTaskPrompt} is taken at its word;
 *   <li>{@code ServiceEventSpool} is gone, deliberately: chats used to open seeded with the service
 *       events that fired while nothing was listening. {@code ServiceSupervisor} is in this repo but
 *       has no spool, and building one is a feature rather than part of a move. Recorded as an open
 *       item;
 *   <li>{@code SettingsService} becomes {@link AgentDefaults};
 *   <li>the three {@code QuarkusTransaction.requiringNew()} wrappers are gone with the database.
 * </ul>
 *
 * <p>The hook port and the claude mount arrive as constructor arguments rather than configuration
 * reads. This module is framework-free so it could not read them anyway, but the reason it matters is
 * that {@code ControlSocket} passes the very same values to {@code HookWebhook} and to the transcript
 * service: two readers of one setting is how a launch ends up rendering hooks at a port nothing is
 * listening on, which fails invisibly — the agent runs, and simply never reports lineage or activity.
 */
public final class AgentLaunchService {

  /** Repository and project ids are generated UUIDs; only hex and dashes ever appear. */
  private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F-]{36}");

  /**
   * The one-sentence bootstrap turn pushed in place of the composed prompt: it carries the user's
   * authority ("do this"), while the {@code taskPrompt} MCP tool carries the content (the refined
   * markdown + attached images). Trivially deliverable in every launch shape — argv for interactive
   * and autonomous, a stream-json turn for chat — which is the whole point of the push→fetch
   * inversion (an image can't ride an argv or a PTY keystroke, but it rides a tool result).
   */
  public static final String TASK_PROMPT_BOOTSTRAP =
      "Fetch the current task prompt for this workspace with the taskPrompt tool, then implement what"
          + " it describes.";

  /**
   * The read-only tools of the {@code actions} MCP server, pre-approved so the session can
   * list/inspect actions without a permission prompt. The mutating tools are left out so the agent
   * still prompts before changing anything. Names are the agent's MCP tool ids: {@code
   * mcp__<server>__<tool>}.
   */
  private static final List<String> READ_ONLY_ACTION_TOOLS =
      List.of(
          "mcp__actions__listGlobalActions",
          "mcp__actions__getGlobalAction",
          "mcp__actions__listRepositoryActions",
          "mcp__actions__getRepositoryAction");

  /** The read-only tools of the {@code repository} MCP server, pre-approved the same way. */
  private static final List<String> READ_ONLY_REPOSITORY_TOOLS =
      List.of(
          "mcp__repository__listRepositories",
          "mcp__repository__listBranches",
          "mcp__repository__listWorkspaces",
          "mcp__repository__listCommits",
          "mcp__repository__listCommitChanges",
          "mcp__repository__getCommitFileDiff",
          "mcp__repository__listActions",
          "mcp__repository__taskPrompt");

  /**
   * The read-only tools of the {@code observability} MCP server — the five telemetry reads, which
   * used to be listed above as {@code mcp__repository__telemetry*}.
   *
   * <p>They moved because the server did. qits-observability and qits-projects both declared a
   * server called {@code repository}, so one MCP url could only ever reach one of them; the
   * telemetry half is now {@code observability}, served at {@code /observability/mcp}. Under the old
   * names these five entries were an allowlist for tools no reachable server declared — present, and
   * doing nothing.
   */
  private static final List<String> READ_ONLY_OBSERVABILITY_TOOLS =
      List.of(
          "mcp__observability__telemetryErrors",
          "mcp__observability__telemetryTrace",
          "mcp__observability__telemetrySlowSpans",
          "mcp__observability__telemetrySearchLogs",
          "mcp__observability__telemetryMetrics");

  /** Kimi session ids are opaque {@code session_}-prefixed path-safe slugs. */
  private static final String KIMI_SESSION_PATTERN = "session_[A-Za-z0-9_-]{1,128}";

  private final AgentCommands commands;
  private final AgentAuthStatus authStatus;
  private final AgentTranscriptService transcripts;
  private final AgentTranscriptTailService transcriptTail;
  private final AgentDefaults defaults;
  private final McpEndpoints endpoints;
  private final WorkspaceContext workspace;
  private final String claudeMount;
  private final int hooksPort;

  /**
   * @param claudeMount where the shared credential volume mounts. Agent launches point {@code HOME}
   *     here so the in-container {@code claude} reads the operator's one-time OAuth login off the
   *     volume instead of a per-session secret — the one credential that crosses into the sandbox.
   *     Blank leaves {@code HOME} at the image default.
   * @param hooksPort the loopback port this daemon's own hook webhook binds. Must be the value
   *     {@code HookWebhook} was given, not a second read of the same key.
   */
  public AgentLaunchService(
      AgentCommands commands,
      AgentAuthStatus authStatus,
      AgentTranscriptService transcripts,
      AgentTranscriptTailService transcriptTail,
      AgentDefaults defaults,
      McpEndpoints endpoints,
      WorkspaceContext workspace,
      String claudeMount,
      int hooksPort) {
    this.commands = commands;
    this.authStatus = authStatus;
    this.transcripts = transcripts;
    this.transcriptTail = transcriptTail;
    this.defaults = defaults;
    this.endpoints = endpoints;
    this.workspace = workspace;
    this.claudeMount = claudeMount;
    this.hooksPort = hooksPort;
  }

  /** Dispatches to {@link #launchChat} or {@link #launchInteractive} on the request's mode. */
  public Command launch(AgentLaunchRequest request) {
    if (request == null || request.scope() == null) {
      throw new InvalidCommandRequestException("scope is required");
    }
    if (request.fork() && (request.resumeSessionId() == null || request.resumeSessionId().isBlank())) {
      throw new InvalidCommandRequestException("fork requires resumeSessionId");
    }
    return request.modeOrDefault() == AgentLaunchMode.INTERACTIVE
        ? launchInteractive(request)
        : launchChat(request);
  }

  /**
   * Launches the coding agent as a <strong>chat</strong> command, with the MCP server(s) for the
   * request's scope attached. Claude drives it over stream-json; Kimi has no stdin chat mode, so its
   * chat rides an in-JVM ACP client with the scoped servers carried on {@code session/new}. Either
   * way the session is rendered as one conversation and tracked in the command registry
   * (re-attachable, logged). Tools run auto-approved.
   */
  public Command launchChat(AgentLaunchRequest request) {
    // Resolve the harness once and thread it through every helper, so auth, render, transport and
    // the recorded command all agree. A resume keeps the resumed session's original harness (you
    // cannot resume a Claude session under Kimi); otherwise explicit choice → default → CLAUDE.
    AgentType type = resolveHarness(request.resumeSessionId(), request.agentType());

    // The agent cannot authenticate until an operator has signed in on the shared credential
    // volume. When it hasn't, launch an interactive agent REPL terminal instead — the caller
    // redirects to its command page (a real PTY), the operator finishes OAuth through the REPL
    // onboarding there, and the next launch (this workspace or any other, same volume) proceeds.
    if (!authStatus.isLoggedIn(type)) {
      return launchLogin(type);
    }

    PinnedSession pinned = pinSession(request.resumeSessionId(), request.fork(), type);
    LaunchSpec spec = renderChat(request.scope(), pinned, type);
    // Claude drives chat over stream-json (null ⇒ the default transport); Kimi has no stdin chat,
    // so its chat rides an in-JVM ACP client with the scoped MCP servers carried on session/new.
    ChatProtocolFactory protocolFactory =
        type == AgentType.KIMI
            ? process ->
                new AcpChatProtocol(process, buildAcpSessionConfig(request.scope(), pinned))
            : null;

    Command command =
        commands.launchChat(
            nameFor(request.scope(), type),
            spec.script(),
            spec.environment(),
            pinned.commandId(),
            pinned.ref(),
            chatTranscriptSweep(),
            protocolFactory,
            type.name());
    // The live transcript import: the durable head a mid-run re-attach replays from.
    transcriptTail.startTail(command.id(), type);
    String seed = request.deliverTaskPrompt() ? TASK_PROMPT_BOOTSTRAP : request.initialContext();
    if (seed != null && !seed.isBlank()) {
      // Seed the conversation as the first user turn. A stream-json chat only speaks over stdin,
      // so the seed can't be a CLI argument; the pipe buffers it until the harness starts reading.
      commands.chatSend(command.id(), seed);
    }
    return command;
  }

  /**
   * Spawns an autonomous agent run as a <strong>chat</strong> command that <strong>fetches</strong>
   * its task over MCP: the narrowed repository server is attached (read-only marked) and the seed
   * turn is the {@link #TASK_PROMPT_BOOTSTRAP}, so the run reads the workspace's composed draft via
   * {@code taskPrompt}. Riding the chat pipeline — instead of a one-shot {@code claude -p}, which
   * printed nothing until it exited — renders the run as a live conversation on its command page, and
   * a human can follow up in the same session once the autonomous turn finishes.
   */
  public Command launchAutonomous(String name) {
    // Composed flows carry no per-launch choice, so they resolve the default harness.
    AgentType type = defaults.defaultAgentType();
    if (!authStatus.isLoggedIn(type)) {
      return launchLogin(type);
    }

    PinnedSession pinned = pinSession(null, false, type);
    LaunchSpec spec = renderAutonomousChat(AgentMcpScope.REPOSITORY, pinned, type);
    ChatProtocolFactory protocolFactory =
        type == AgentType.KIMI
            ? process ->
                new AcpChatProtocol(
                    process, buildAcpSessionConfig(AgentMcpScope.REPOSITORY, pinned, true))
            : null;
    Command command =
        commands.launchChat(
            name,
            spec.script(),
            spec.environment(),
            pinned.commandId(),
            pinned.ref(),
            chatTranscriptSweep(),
            protocolFactory,
            type.name());
    transcriptTail.startTail(command.id(), type);
    // The bootstrap rides stdin as the first user turn (a chat only speaks over stdin); the agent
    // then pulls the real composed prompt back over MCP via taskPrompt.
    commands.chatSend(command.id(), TASK_PROMPT_BOOTSTRAP);
    return command;
  }

  /**
   * Launches the full interactive agent TUI (the plain {@code claude} or {@code kimi} REPL in
   * xterm.js, kind {@code TERMINAL}) as a first-class agent session: same MCP scope servers,
   * credential overlay and skip-permissions as chat, plus a session id and the session-report hook —
   * so the run is resumable, forkable (Claude only), and its transcript is imported on exit like any
   * chat. The PTY byte stream stays terminal-only; the structured conversation comes from the
   * transcript.
   */
  public Command launchInteractive(AgentLaunchRequest request) {
    AgentType type = resolveHarness(request.resumeSessionId(), request.agentType());
    if (type == AgentType.KIMI && request.fork()) {
      throw new InvalidCommandRequestException("fork is not supported by Kimi Code");
    }
    if (!authStatus.isLoggedIn(type)) {
      return launchLogin(type);
    }

    String seed =
        request.deliverTaskPrompt() ? TASK_PROMPT_BOOTSTRAP : request.initialContext();
    PinnedSession pinned = pinSession(request.resumeSessionId(), request.fork(), type);
    LaunchSpec spec = renderInteractive(request.scope(), seed, pinned, type);
    return commands.launchAgent(
        interactiveNameFor(request.scope(), type),
        spec.script(),
        true,
        spec.environment(),
        pinned.commandId(),
        pinned.ref(),
        transcriptSweep(),
        type.name());
  }

  /**
   * Launches an interactive agent login terminal (a normal PTY command, kind {@code TERMINAL}) so an
   * operator can complete the one-time sign-in (Claude: OAuth through the REPL onboarding; Kimi: the
   * device-code flow). Writes to the shared credential volume, so it signs in every workspace at
   * once. Returned by the launch paths when the agent isn't signed in yet; the caller redirects to
   * its terminal.
   */
  public Command launchLogin(AgentType agentType) {
    LaunchSpec spec = renderLogin(agentType);
    String name =
        switch (agentType) {
          case CLAUDE -> "Claude sign-in";
          case KIMI -> "Kimi sign-in";
        };
    return commands.launchAgent(
        name, spec.script(), true, spec.environment(), null, null, null, agentType.name());
  }

  /** Renders the interactive login command with the shared-volume credential overlay. */
  LaunchSpec renderLogin(AgentType agentType) {
    Map<String, String> env = new HashMap<>();
    if (claudeMount != null && !claudeMount.isBlank()) {
      switch (agentType) {
        case CLAUDE -> env.put("HOME", claudeMount);
        // Kimi uses KIMI_CODE_HOME, set at the container level; login must run against the real
        // volume home (no per-launch mktemp farm) so credential writes survive.
        case KIMI -> env.put("KIMI_CODE_HOME", claudeMount + "/.kimi-code");
      }
    }
    return switch (agentType) {
      case CLAUDE ->
          // Run the `claude` REPL, NOT the `claude auth login` subcommand. The REPL's first-run
          // onboarding renders a paste-the-code prompt over the PTY and reads it from stdin, so an
          // operator can complete sign-in in the terminal. The `auth login` subcommand blocks on a
          // loopback HTTP callback the host browser can never reach.
          new LaunchSpec("exec claude", true, env);
      case KIMI ->
          // Kimi login is a device-code flow that prints the verification URL + user code and
          // polls, so it works plainly over a TTY.
          new LaunchSpec("exec kimi login", true, env);
    };
  }

  /**
   * A launch's session identity, generated before anything exists: the command id (rendered into the
   * session-report hook URL) and the first {@link AgentSessionRef} of its session list.
   */
  record PinnedSession(String commandId, AgentSessionRef ref) {}

  /**
   * The harness for this launch. A resume is pinned to the resumed session's recorded harness (a
   * Claude session can't be resumed under Kimi, and its transcript layout and auth probe are
   * harness-specific), overriding any explicit choice or default. A fresh launch resolves normally:
   * explicit choice → the configured default → CLAUDE.
   */
  private AgentType resolveHarness(String resumeSessionId, AgentType explicit) {
    if (resumeSessionId != null) {
      Optional<AgentType> resumed =
          commands.agentTypeForSession(resumeSessionId).flatMap(AgentType::parse);
      if (resumed.isPresent()) {
        return resumed.get();
      }
    }
    return defaults.resolve(explicit);
  }

  /**
   * Pins the launch's session identity. Fresh launches pin a brand-new UUID ({@code PINNED}); Kimi
   * Code cannot pin a fresh session id, so its fresh launches return a {@code null} ref and the id is
   * learned from the harness's SessionStart hook. Resume reuses {@code resumeSessionId} in place
   * ({@code RESUMED}); fork branches it into a fresh pin ({@code FORKED}, with the origin recorded).
   *
   * <p>Resume and fork require the session to belong to <em>this container</em>. That is narrower
   * than the host's "belongs to this workspace" and it fails closed: a session driven before a
   * container recreate is refused, because {@code CommandStore} did not survive to vouch for it.
   */
  PinnedSession pinSession(String resumeSessionId, boolean fork, AgentType agentType) {
    String commandId = UUID.randomUUID().toString();
    if (resumeSessionId == null) {
      if (fork) {
        throw new InvalidCommandRequestException("fork requires resumeSessionId");
      }
      if (agentType == AgentType.KIMI) {
        // Kimi cannot pin a new session id; the SessionStart hook will report it later.
        return new PinnedSession(commandId, null);
      }
      return new PinnedSession(
          commandId,
          new AgentSessionRef(
              UUID.randomUUID().toString(), AgentSessionSource.PINNED, null, null, Instant.now()));
    }
    requireSessionId(resumeSessionId, "session id", agentType);
    if (!commands.ownsSession(resumeSessionId)) {
      throw new InvalidCommandRequestException(
          "Session "
              + resumeSessionId
              + " was not started in this container and cannot be resumed here");
    }
    if (fork) {
      if (agentType == AgentType.KIMI) {
        throw new InvalidCommandRequestException("fork is not supported by Kimi Code");
      }
      return new PinnedSession(
          commandId,
          new AgentSessionRef(
              UUID.randomUUID().toString(),
              AgentSessionSource.FORKED,
              resumeSessionId,
              null,
              Instant.now()));
    }
    return new PinnedSession(
        commandId,
        new AgentSessionRef(resumeSessionId, AgentSessionSource.RESUMED, null, null, Instant.now()));
  }

  private void requireSessionId(String value, String label, AgentType agentType) {
    if (agentType == AgentType.KIMI) {
      if (value == null || !value.matches(KIMI_SESSION_PATTERN)) {
        throw new InvalidCommandRequestException("Invalid " + label + ": " + value);
      }
      return;
    }
    requireUuid(value, label);
  }

  /** Configures the agent's session flags + report hook from the pinned identity. */
  private CodingAgent withSession(CodingAgent agent, PinnedSession pinned) {
    AgentSessionRef ref = pinned.ref();
    if (ref != null) {
      switch (ref.source()) {
        case PINNED -> agent.sessionId(ref.sessionId());
        case RESUMED -> agent.resume(ref.sessionId());
        case FORKED -> agent.resume(ref.forkedFromSessionId()).fork(ref.sessionId());
        case SWITCHED, REPORTED ->
            throw new IllegalStateException(
                "SWITCHED/REPORTED are hook-reported, never a launch source");
      }
    }
    return agent
        .activityTracking(defaults.activityTrackingEnabled())
        .sessionReporting(sessionReportUrl(pinned.commandId()));
  }

  /**
   * The loopback hook endpoint for {@code commandId} — the agent's lifecycle hooks POST their stdin
   * JSON here, to this daemon's own webhook, which relays it home over the control socket. It targets
   * {@code 127.0.0.1}, so no resolver host is baked into the hook command. The {@code commandId}
   * rides as a query parameter so the webhook stays a dumb forwarder.
   */
  public String sessionReportUrl(String commandId) {
    return "http://127.0.0.1:" + hooksPort + "/hooks/claude-code?commandId=" + commandId;
  }

  /** The post-exit transcript import, composed onto the registry exit listener at spawn. */
  private CommandExitListener transcriptSweep() {
    // onCommandExit swallows its own failures, so the sweep can never break exit handling.
    return (commandId, exitCode, terminatedManually) -> transcripts.onCommandExit(commandId);
  }

  /**
   * The chat exit chain: stop the live tail first (so no tail write can race the sweep), then the
   * reconciling sweep, which waits for the harness's JSONL flush to catch up with what the tail
   * already imported before its delete-and-reimport.
   */
  private CommandExitListener chatTranscriptSweep() {
    return (commandId, exitCode, terminatedManually) -> {
      long importedLive = transcriptTail.stopAndDrain(commandId);
      transcripts.onChatExit(commandId, importedLive);
    };
  }

  /**
   * Renders the stream-json chat launch for {@code scope} with its MCP servers attached and {@code
   * HOME} pointed at the shared credential volume. Package-visible so the credential overlay is
   * assertable without spawning anything.
   */
  LaunchSpec renderChat(AgentMcpScope scope, PinnedSession pinned, AgentType agentType) {
    CodingAgent agent = CodingAgentFactory.ofType(agentType);
    for (ScopedMcp server : serversFor(scope)) {
      agent.mcpServer(server.key(), McpServers.httpMcp(server.url()));
    }
    return withSession(withAgentHome(agent, agentType), pinned).skipPermissions().chat();
  }

  /**
   * Builds the ACP session inputs for a Kimi chat: the same scoped MCP servers {@link #serversFor}
   * produces (carried protocol-native on {@code session/new}, no {@code mcp.json}), with per-server
   * bare {@code enabledTools} (the shared prefix-strip); the resumed session id when this launch
   * resumes; and the session-id sink that records the id kimi returns from {@code session/new} on the
   * command (kimi can't pin a fresh id, so the id is learned, not pinned).
   */
  AcpSessionConfig buildAcpSessionConfig(AgentMcpScope scope, PinnedSession pinned) {
    return buildAcpSessionConfig(scope, pinned, false);
  }

  /**
   * {@link #buildAcpSessionConfig} with the autonomous read-only marking: {@code readOnly} appends
   * the {@code agentReadOnly} marker to each server URL so the mutating repository tools are fenced —
   * the ACP counterpart of {@link #renderAutonomousChat}'s URL marking.
   */
  AcpSessionConfig buildAcpSessionConfig(
      AgentMcpScope scope, PinnedSession pinned, boolean readOnly) {
    List<AcpSessionConfig.AcpMcpServer> servers = new ArrayList<>();
    for (ScopedMcp server : serversFor(scope)) {
      servers.add(
          new AcpSessionConfig.AcpMcpServer(
              server.key(),
              readOnly ? readOnlyMarked(server.url()) : server.url(),
              KimiCodeAgent.stripServerPrefix(server.key(), server.allowedTools())));
    }
    AgentSessionRef ref = pinned.ref();
    String resumeSessionId =
        ref != null && ref.source() == AgentSessionSource.RESUMED ? ref.sessionId() : null;
    String commandId = pinned.commandId();
    return new AcpSessionConfig(
        AgentTranscriptService.CONTAINER_CWD,
        servers,
        resumeSessionId,
        id -> commands.reportAgentSession(commandId, id, null));
  }

  /**
   * Renders the autonomous run as a stream-json chat: the {@code scope} MCP servers attached (so
   * {@code taskPrompt} is reachable), the credential overlay and skip-permissions — like {@link
   * #renderChat}, but with each server URL read-only marked.
   */
  LaunchSpec renderAutonomousChat(AgentMcpScope scope, PinnedSession pinned, AgentType agentType) {
    CodingAgent agent = CodingAgentFactory.ofType(agentType);
    for (ScopedMcp server : serversFor(scope)) {
      // Unattended first turn under skip-permissions: mark the server read-only so the host's
      // ReadOnlyRepositoryToolFilter hides the mutating repository tools
      // (createWorkspace/integrateBranch/…). The run still gets taskPrompt + the read-only tools;
      // its own git work happens inside this container, not via host-side MCP mutations.
      agent.mcpServer(server.key(), McpServers.httpMcp(readOnlyMarked(server.url())));
    }
    return withSession(withAgentHome(agent, agentType), pinned).skipPermissions().chat();
  }

  /**
   * Appends the read-only marker query parameter to an MCP URL. The name must match {@code
   * ReadOnlyRepositoryToolFilter.READ_ONLY_PARAM} on the host.
   */
  private static String readOnlyMarked(String url) {
    return url + (url.contains("?") ? "&" : "?") + "agentReadOnly=true";
  }

  /**
   * Renders the interactive TUI launch: the same scope servers and overlays as chat, but the full
   * REPL ({@code start()}) with an optional seed prompt embedded. No {@code flatOutput} — xterm.js
   * renders the TUI; the readable conversation is the imported transcript.
   */
  LaunchSpec renderInteractive(
      AgentMcpScope scope, String initialContext, PinnedSession pinned, AgentType agentType) {
    CodingAgent agent = CodingAgentFactory.ofType(agentType);
    for (ScopedMcp server : serversFor(scope)) {
      agent.mcpServer(server.key(), McpServers.httpMcp(server.url()));
    }
    if (initialContext != null && !initialContext.isBlank()) {
      agent.initialContext(initialContext);
    }
    return withSession(withAgentHome(agent, agentType), pinned).skipPermissions().start();
  }

  /**
   * Points the agent's {@code HOME} at the shared credential volume so the in-container {@code
   * claude} reads the operator's one-time OAuth login. CWD stays {@code /workspace}, so project
   * detection (the repo's own {@code .claude/}, {@code CLAUDE.md}) is unaffected. Kimi Code uses the
   * container-level {@code KIMI_CODE_HOME} and its own per-launch symlink farm, so no {@code HOME}
   * overlay is needed.
   */
  private CodingAgent withAgentHome(CodingAgent agent, AgentType agentType) {
    if (agentType == AgentType.CLAUDE && claudeMount != null && !claudeMount.isBlank()) {
      agent.environment("HOME", claudeMount);
    }
    return agent;
  }

  /** A scoped MCP server: the key it is registered under, its scoped URL, and its read-only tools. */
  public record ScopedMcp(String key, String url, List<String> allowedTools) {}

  /**
   * The scoped MCP servers for {@code scope}, with their read-only allowlists.
   *
   * <p>{@code repository} and {@code observability} are two servers on two services (qits-projects
   * and qits-observability), not one server with two halves. They are listed together wherever the
   * session is narrowed to a workspace, because that is the pairing the old single {@code
   * repository} server presented.
   */
  List<ScopedMcp> serversFor(AgentMcpScope scope) {
    String repo = requireUuid(workspace.repoId(), "repository id");
    String projectId = requireUuid(endpoints.projectId(), "project id");
    // Project-scoped, then narrowed to this one repository so a per-subtree session only sees its
    // own repo, not its siblings in the project.
    ScopedMcp narrowedRepositoryServer =
        new ScopedMcp(
            "repository",
            endpoints.mcpUrl("repository")
                + "?projectId="
                + projectId
                + "&repositoryId="
                + repo
                + "&workspaceId="
                + workspace.workspaceId(),
            READ_ONLY_REPOSITORY_TOOLS);
    // Telemetry is bucketed per workspace, and qits-observability's tool filter hides the tools
    // outright unless both narrowings are present — so this server is only worth listing where they
    // are, and carries exactly the two scopes that service reads (no projectId: it has no notion of
    // one).
    ScopedMcp observabilityServer =
        new ScopedMcp(
            "observability",
            endpoints.mcpUrl("observability")
                + "?repositoryId="
                + repo
                + "&workspaceId="
                + workspace.workspaceId(),
            READ_ONLY_OBSERVABILITY_TOOLS);
    return switch (scope) {
      case ACTIONS ->
          // The "configure this repository" session: the actions server for the action library,
          // plus the (narrowed) repository server for the repository reads (branches, workspaces,
          // commits) — the session needs both to configure the repository fully.
          List.of(
              new ScopedMcp(
                  "actions",
                  endpoints.mcpUrl("actions") + "?repositoryId=" + repo,
                  READ_ONLY_ACTION_TOOLS),
              narrowedRepositoryServer,
              observabilityServer);
      case REPOSITORY -> List.of(narrowedRepositoryServer, observabilityServer);
      case PROJECT ->
          // Project scope only, no repository narrowing — the session sees every repository in the
          // project. It still runs in this repository's workspace (the terminal needs a checkout).
          // No observability server: without the repository/workspace narrowing its tools are
          // filtered away at the far end, so offering it would advertise a dead end.
          List.of(
              new ScopedMcp(
                  "repository",
                  endpoints.mcpUrl("repository") + "?projectId=" + projectId,
                  READ_ONLY_REPOSITORY_TOOLS));
    };
  }

  private String nameFor(AgentMcpScope scope, AgentType agentType) {
    return harnessName(
        scope,
        switch (agentType) {
          case CLAUDE -> "Claude Code";
          case KIMI -> "Kimi Code";
        });
  }

  private String interactiveNameFor(AgentMcpScope scope, AgentType agentType) {
    return harnessName(
        scope,
        switch (agentType) {
          case CLAUDE -> "Claude Code terminal";
          case KIMI -> "Kimi Code terminal";
        });
  }

  private static String harnessName(AgentMcpScope scope, String harnessLabel) {
    return switch (scope) {
      case ACTIONS -> harnessLabel + " (actions + repository MCP)";
      case REPOSITORY -> harnessLabel + " (repository MCP)";
      case PROJECT -> harnessLabel + " (project MCP)";
    };
  }

  private String requireUuid(String value, String label) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) {
      throw new InvalidCommandRequestException("Invalid " + label + ": " + value);
    }
    return value;
  }
}
