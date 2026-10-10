package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.EntityFacts;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

/**
 * The agents this workspace hosts, and their harnesses (qits-1152, D5, D14, D16, D18).
 *
 * <p>A workspace hosts several agents. Each has its own agent worktree ({@link AgentWorktrees}),
 * its own harness process, its own launch service — so its own entity name — and its own
 * credential. Whether only one harness runs at a time is the host's decision (D14); this class
 * starts, stops and resumes whichever agent it is asked to.
 *
 * <p><b>Yield and resume.</b> Yielding stops the harness and keeps everything else. Resuming starts
 * it again with the agent's last session id, when that session's files are still there. They are
 * under the shared harness volume ({@code /claude-home}), keyed by the agent's working directory,
 * and the worktree is on the workspace volume, so both outlive a container restart.
 *
 * <p><b>What is remembered where.</b> The credential lives only in memory and only in the harness
 * process's environment (D18). The rest of an agent's description — work item, wrapper branch,
 * harness, session id — is written beside its worktree as {@code agent.json}, so a restarted daemon
 * can still list and clean up an agent the host has not started again. The host's row is the
 * authority; the file is a cache of it.
 */
final class AgentRuntime {

  private static final Logger LOG = Logger.getLogger(AgentRuntime.class);

  /** The file beside an agent's worktree that describes it. Holds no credential. */
  static final String METADATA_FILE = "agent.json";

  /** What the host asks for in {@code POST agent-worktrees}. */
  record Start(
      String agentId,
      String workId,
      String entityId,
      String wrapperBranch,
      AgentType harness,
      String sessionId,
      Map<String, String> env,
      String instruction,
      AgentSurface surface,
      AgentLaunchMode mode,
      EntityFacts entity) {}

  /** What {@link #start} did. */
  record Started(
      String agentId,
      Path path,
      String commandId,
      boolean launched,
      boolean resumed,
      String sessionId) {}

  /** What {@link #turn} did. */
  record Turn(boolean delivered, String commandId, String kind, boolean restarted) {}

  /** One agent as {@code GET agent-worktrees} reports it. */
  record View(
      String agentId,
      String workId,
      String wrapperBranch,
      Path path,
      boolean harnessRunning,
      String commandId,
      String sessionId,
      List<AgentWorktrees.RepoState> repositories) {}

  /**
   * What a launch service is built from: everything per agent, nothing shared. {@code credential}
   * is the agent's own token, for the platform MCP servers' header — never the workspace's.
   */
  record Seat(
      String agentId,
      String workId,
      String entityId,
      String wrapperBranch,
      Path directory,
      EntityFacts entity,
      AgentCommands commands,
      Optional<String> credential) {}

  /** A session id reaches a path and an argv: it is held to a safe shape. */
  private static final java.util.regex.Pattern SESSION_ID =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  /** The agent's own token, from its start's {@code env}; empty when it carried none. */
  static Optional<String> credential(Map<String, String> env) {
    String token = env == null ? null : env.get(OriginSync.AGENT_CREDENTIAL);
    return token == null || token.isBlank() ? Optional.empty() : Optional.of(token);
  }

  /** Builds an agent's launch service; {@link ControlSocket} holds the shared parts. */
  @FunctionalInterface
  interface LaunchFactory {
    AgentLaunchService create(Seat seat);
  }

  /** An agent this process knows, because the host started it since the daemon came up. */
  private static final class Agent {
    final String agentId;
    volatile String workId;
    volatile String entityId;
    volatile String wrapperBranch;
    volatile AgentType harness;
    volatile String sessionId;
    volatile Map<String, String> env = Map.of();
    volatile AgentSurface surface = AgentSurface.TICKET_DISPATCH;
    volatile AgentLaunchMode mode = AgentLaunchMode.CHAT;
    volatile String commandId;
    volatile Path directory;
    volatile AgentLaunchService launch;

    Agent(String agentId) {
      this.agentId = agentId;
    }
  }

  private final AgentWorktrees worktrees;
  private final CommandStore store;
  private final CommandRegistry registry;
  private final AgentCommands commands;
  private final LaunchFactory factory;
  private final String claudeMount;

  private final Map<String, Agent> agents = new ConcurrentHashMap<>();

  /** Which agent a command belongs to; filled before the spawn so an early hook finds it. */
  private final Map<String, String> commandAgents = new ConcurrentHashMap<>();

  AgentRuntime(
      AgentWorktrees worktrees,
      CommandStore store,
      CommandRegistry registry,
      AgentCommands commands,
      LaunchFactory factory,
      String claudeMount) {
    this.worktrees = worktrees;
    this.store = store;
    this.registry = registry;
    this.commands = commands;
    this.factory = factory;
    this.claudeMount = claudeMount;
  }

  /**
   * Make the agent's worktrees if missing and start its harness, unless it is already running.
   * Idempotent: a second call with the same agent changes nothing but the facts it carries, and
   * starts a harness that has stopped.
   */
  synchronized Started start(Start request) {
    if (request.workId() == null || request.workId().isBlank()) {
      throw new AgentWorktrees.AgentWorktreeException("workId is required");
    }
    if (request.sessionId() != null
        && !request.sessionId().isBlank()
        && !SESSION_ID.matcher(request.sessionId()).matches()) {
      throw new AgentWorktrees.AgentWorktreeException("Invalid sessionId");
    }
    Path directory = worktrees.ensure(request.agentId(), request.wrapperBranch());
    Agent agent = agents.computeIfAbsent(request.agentId(), Agent::new);
    Optional<JsonObject> stored = readMetadata(request.agentId());
    agent.workId = request.workId();
    agent.entityId = request.entityId();
    agent.wrapperBranch = request.wrapperBranch();
    agent.directory = directory;
    Optional<String> previousCredential = credential(agent.env);
    agent.env = request.env() == null ? Map.of() : Map.copyOf(request.env());
    if (request.surface() != null) {
      agent.surface = request.surface();
    }
    if (request.mode() != null) {
      agent.mode = request.mode();
    }
    if (request.harness() != null) {
      agent.harness = request.harness();
    } else if (agent.harness == null) {
      agent.harness =
          stored.map(m -> m.getString("harness")).flatMap(AgentType::parse).orElse(null);
    }
    if (request.sessionId() != null && !request.sessionId().isBlank()) {
      agent.sessionId = request.sessionId();
    } else if (agent.sessionId == null) {
      agent.sessionId = stored.map(m -> m.getString("sessionId")).orElse(null);
    }
    // The launch service carries the agent's token in its MCP headers, so a new token needs a new
    // service. A running harness keeps the one it started with until it stops.
    if (agent.launch == null
        || (!credential(agent.env).equals(previousCredential) && running(agent) == null)) {
      agent.launch = factory.create(seat(agent, request.entity()));
    }
    if (request.entity() != null) {
      agent.launch.setEntity(request.entity());
    }
    writeMetadata(agent);
    Command running = running(agent);
    if (running != null) {
      return new Started(agent.agentId, directory, running.id(), false, false, agent.sessionId);
    }
    return launch(agent, request.instruction());
  }

  private Seat seat(Agent agent, EntityFacts entity) {
    AgentCommands scoped =
        new AgentScopedCommands(
            commands,
            agent.directory,
            () -> agent.env,
            () -> agent.sessionId,
            () -> agent.harness == null ? null : agent.harness.name(),
            (commandId, command) -> commandAgents.put(commandId, agent.agentId));
    return new Seat(
        agent.agentId,
        agent.workId,
        agent.entityId,
        agent.wrapperBranch,
        agent.directory,
        entity,
        scoped,
        credential(agent.env));
  }

  /**
   * Start the harness: resuming the agent's session when its files are there (D5), fresh otherwise.
   * {@code opening} is the first turn either way.
   */
  private Started launch(Agent agent, String opening) {
    String session = agent.sessionId;
    AgentType harness = agent.harness;
    boolean resume = session != null && sessionFilesExist(harness, agent.directory, session);
    if (session != null && !resume) {
      LOG.infof(
          "agent %s: no files for session %s under %s, starting a new session",
          agent.agentId, session, claudeMount);
    }
    Command command =
        agent.launch.launch(
            new AgentLaunchRequest(
                AgentMcpScope.REPOSITORY,
                agent.surface,
                agent.mode,
                opening,
                resume ? session : null,
                false,
                false,
                harness));
    agent.commandId = command.id();
    commandAgents.put(command.id(), agent.agentId);
    AgentType.parse(command.agentType()).ifPresent(type -> agent.harness = type);
    AgentSessionRef current = command.currentSession();
    if (current != null && current.sessionId() != null) {
      agent.sessionId = current.sessionId();
    }
    writeMetadata(agent);
    return new Started(agent.agentId, agent.directory, command.id(), true, resume, agent.sessionId);
  }

  /** Stop the agent's harness and keep everything else (D16). False when none was running. */
  boolean yield(String agentId) {
    Agent agent = known(agentId);
    Command running = running(agent);
    if (running == null) {
      return false;
    }
    registry.terminate(running.id());
    return true;
  }

  /**
   * Deliver a user turn. A stopped harness is started again with its session first, with the turn
   * as its opening turn.
   */
  synchronized Turn turn(String agentId, String text) {
    Agent agent = known(agentId);
    Command running = running(agent);
    if (running != null) {
      boolean delivered =
          running.kind() == CommandKind.CHAT
              ? registry.chatSend(running.id(), text)
              : registry.input(running.id(), (text + "\r").getBytes(StandardCharsets.UTF_8));
      if (delivered) {
        return new Turn(true, running.id(), running.kind().name(), false);
      }
    }
    Started started = launch(agent, text);
    Command command = store.find(started.commandId()).orElse(null);
    return new Turn(
        true, started.commandId(), command == null ? null : command.kind().name(), true);
  }

  /** {@code POST …/entity}: the agent's entity facts, and a rename of its live session. */
  int setEntity(String agentId, EntityFacts facts) {
    return known(agentId).launch.setEntity(facts);
  }

  /** {@code POST …/blocked}: the blocked flag alone. */
  int setBlocked(String agentId, boolean blocked, String blockSource) {
    return known(agentId).launch.setBlocked(blocked, blockSource);
  }

  /** The agent's entity facts as its launch service holds them. */
  EntityFacts entity(String agentId) {
    return known(agentId).launch.entity();
  }

  /** The cleanup check for one agent. */
  AgentWorktrees.CleanupCheck cleanupCheck(String agentId) {
    requireExists(agentId);
    return worktrees.cleanupCheck(agentId);
  }

  /**
   * Remove the agent: stop its harness, remove its worktrees and local branches, forget it. Refused
   * while there is work that would be lost, unless {@code force}.
   *
   * @return the cleanup check that refused it, or null when it was removed
   */
  synchronized AgentWorktrees.CleanupCheck remove(String agentId, boolean force) {
    requireExists(agentId);
    if (!force) {
      AgentWorktrees.CleanupCheck check = worktrees.cleanupCheck(agentId);
      if (!check.clean()) {
        return check;
      }
    }
    Agent agent = agents.get(agentId);
    if (agent != null) {
      Command running = running(agent);
      if (running != null) {
        registry.terminate(running.id());
      }
    }
    worktrees.remove(agentId);
    agents.remove(agentId);
    commandAgents.values().removeIf(agentId::equals);
    return null;
  }

  /** Every agent with a worktree here, known to this process or not. */
  List<View> list() {
    List<View> out = new ArrayList<>();
    for (String agentId : worktrees.agentIds()) {
      if (!worktrees.exists(agentId)) {
        continue;
      }
      out.add(view(agentId));
    }
    return out;
  }

  View view(String agentId) {
    requireExists(agentId);
    Agent agent = agents.get(agentId);
    Optional<JsonObject> stored = readMetadata(agentId);
    Command running = agent == null ? null : running(agent);
    return new View(
        agentId,
        agent != null ? agent.workId : stored.map(m -> m.getString("workId")).orElse(null),
        agent != null
            ? agent.wrapperBranch
            : stored.map(m -> m.getString("wrapperBranch")).orElse(null),
        worktrees.wrapperDir(agentId),
        running != null,
        agent == null ? null : agent.commandId,
        agent != null ? agent.sessionId : stored.map(m -> m.getString("sessionId")).orElse(null),
        worktrees.status(agentId));
  }

  /** The agent a command runs for, or null. */
  String agentOf(String commandId) {
    return commandId == null ? null : commandAgents.get(commandId);
  }

  /** The agent's credential, while this process knows it. */
  Optional<Map<String, String>> environment(String agentId) {
    Agent agent = agents.get(agentId);
    return agent == null ? Optional.empty() : Optional.of(agent.env);
  }

  /**
   * Name the agent on an {@link AgentActivity} frame, and keep the agent's session id current: a
   * Kimi session's id is learned from its first hook, and an in-session {@code /resume} switches
   * it.
   */
  DaemonMessage tag(DaemonMessage message) {
    if (!(message instanceof AgentActivity activity) || activity.agentId() != null) {
      return message;
    }
    String agentId = agentOf(activity.commandId());
    if (agentId == null) {
      return message;
    }
    Agent agent = agents.get(agentId);
    if (agent != null
        && activity.sessionId() != null
        && !activity.sessionId().equals(agent.sessionId)) {
      agent.sessionId = activity.sessionId();
      writeMetadata(agent);
    }
    return activity.withAgentId(agentId);
  }

  /** Hand a stored activity state to the agent's launch service (rename retries, session end). */
  void onActivity(String commandId, String state) {
    String agentId = agentOf(commandId);
    Agent agent = agentId == null ? null : agents.get(agentId);
    if (agent != null && agent.launch != null) {
      agent.launch.onActivity(commandId, state);
    }
  }

  /**
   * Whether the harness keeps files for {@code session} under the agent's working directory. Claude
   * keys them by the escaped working directory; Kimi by a key of its own, so its session directory
   * is looked up by name.
   */
  boolean sessionFilesExist(AgentType harness, Path directory, String session) {
    if (claudeMount == null || claudeMount.isBlank() || !session.matches("[A-Za-z0-9._-]+")) {
      return false;
    }
    if (harness == AgentType.KIMI) {
      Path sessions = Path.of(claudeMount, ".kimi-code", "sessions");
      if (!Files.isDirectory(sessions)) {
        return false;
      }
      try (Stream<Path> walk = Files.walk(sessions, 2)) {
        return walk.anyMatch(
            p -> Files.isDirectory(p) && p.getFileName().toString().equals(session));
      } catch (IOException e) {
        return false;
      }
    }
    String escaped = directory.toString().replaceAll("[^A-Za-z0-9]", "-");
    return Files.isRegularFile(
        Path.of(claudeMount, ".claude", "projects", escaped, session + ".jsonl"));
  }

  private Command running(Agent agent) {
    String commandId = agent.commandId;
    if (commandId == null) {
      return null;
    }
    return store.find(commandId).filter(Command::isRunning).orElse(null);
  }

  private Agent known(String agentId) {
    Agent agent = agentId == null ? null : agents.get(agentId);
    if (agent == null || agent.launch == null) {
      throw new UnknownAgentException(agentId);
    }
    return agent;
  }

  private void requireExists(String agentId) {
    if (!worktrees.exists(agentId)) {
      throw new UnknownAgentException(agentId);
    }
  }

  private Optional<JsonObject> readMetadata(String agentId) {
    Path file = worktrees.agentDir(agentId).resolve(METADATA_FILE);
    if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try {
      return Optional.of(new JsonObject(Files.readString(file)));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Everything about the agent but its credential, which never reaches disk (D18). */
  private void writeMetadata(Agent agent) {
    JsonObject json =
        new JsonObject()
            .put("agentId", agent.agentId)
            .put("workId", agent.workId)
            .put("entityId", agent.entityId)
            .put("wrapperBranch", agent.wrapperBranch)
            .put("harness", agent.harness == null ? null : agent.harness.name())
            .put("sessionId", agent.sessionId);
    // Written beside and moved over, so a symbolic link an agent put in its place is replaced,
    // never
    // followed: the daemon writes this file and no other.
    Path dir = worktrees.agentDir(agent.agentId);
    Path temporary = dir.resolve(METADATA_FILE + ".tmp");
    try {
      Files.deleteIfExists(temporary);
      Files.writeString(
          temporary,
          json.encodePrettily(),
          java.nio.file.StandardOpenOption.CREATE_NEW,
          java.nio.file.StandardOpenOption.WRITE);
      Files.move(
          temporary,
          dir.resolve(METADATA_FILE),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      LOG.warnf(
          "could not write %s for agent %s: %s", METADATA_FILE, agent.agentId, e.getMessage());
    }
  }

  /** The agent is not known here: no worktree, or not started since the daemon came up. */
  static final class UnknownAgentException extends RuntimeException {
    UnknownAgentException(String agentId) {
      super(
          "Agent " + agentId + " is not started in this workspace; POST agent-worktrees starts it");
    }
  }
}
