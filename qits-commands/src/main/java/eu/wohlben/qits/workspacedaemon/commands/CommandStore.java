package eu.wohlben.qits.workspacedaemon.commands;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every command this container has run, and their logs. The whole of the host's persistence layer —
 * {@code CommandRepository}, {@code CommandLogLineRepository}, the {@code command} /
 * {@code command_log_line} / {@code command_agent_session} tables and Flyway V8, V9, V12, V13, V18,
 * V28, V29 and V32 — is replaced by this class.
 *
 * <p><b>Nothing here outlives the container.</b> That is the deliberate scope of the move, not an
 * oversight: the daemon is the container's process, so this store is created when the container
 * starts and is gone when it stops. A recreated workspace has no command history, no logs and no
 * agent-session lineage from before the recreate. On the host all three were rows that survived
 * anything short of deleting the repository. The transcripts themselves are unaffected — the
 * harness writes those under {@code /claude-home}, a volume shared across containers — so what is
 * lost is the index of them, not the conversations.
 *
 * <p>The queries lost their filters rather than gaining string ids. Each of {@code
 * findByRepository}, {@code findByRepositoryAndWorkspace}, {@code findByWorkspace},
 * {@code existsByWorkspaceAndSessionId}, {@code findAgentTypeByWorkspaceAndSessionId} and
 * {@code findRunningByKindAndWorkspace} narrowed by navigating {@code workspace.repository.id} and
 * {@code workspace.workspaceId}. Inside the container every command is this workspace's by
 * construction, so those predicates are constants and the methods collapse into the five below.
 *
 * <p>Retention: {@link #MAX_COMMANDS} finished commands are kept, oldest evicted first. Running
 * commands are never evicted — a live process must always be addressable by its id.
 */
public final class CommandStore {

  /**
   * How many finished commands to keep. The Commands list is a working view of what this container
   * has been doing, not an audit log — that role ended when the rows stopped being durable — so the
   * bound only has to cover a session's worth of work.
   */
  public static final int MAX_COMMANDS = 200;

  private final ConcurrentHashMap<String, Command> commands = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CommandLogBuffer> logs = new ConcurrentHashMap<>();
  private final int maxCommands;
  private final int logCapacity;
  private final AtomicLong evicted = new AtomicLong();

  public CommandStore() {
    this(MAX_COMMANDS, CommandLogBuffer.DEFAULT_CAPACITY);
  }

  public CommandStore(int maxCommands, int logCapacity) {
    this.maxCommands = maxCommands;
    this.logCapacity = logCapacity;
  }

  /** Record a freshly launched command. */
  public void put(Command command) {
    commands.put(command.id(), command);
    logs.computeIfAbsent(command.id(), id -> new CommandLogBuffer(logCapacity));
    evictIfNeeded();
  }

  public Optional<Command> find(String commandId) {
    return Optional.ofNullable(commands.get(commandId));
  }

  /**
   * Apply {@code change} to the stored command, if it is still there. Returns the new value, or
   * empty if the command has been evicted — which a late exit callback can legitimately hit.
   */
  public Optional<Command> update(String commandId, java.util.function.UnaryOperator<Command> change) {
    Command updated = commands.computeIfPresent(commandId, (id, current) -> change.apply(current));
    return Optional.ofNullable(updated);
  }

  /** All commands, most-recently-launched first — the Commands list order. */
  public List<Command> listByLaunchedAtDesc() {
    List<Command> all = new ArrayList<>(commands.values());
    all.sort(Comparator.comparing(Command::launchedAt).reversed());
    return all;
  }

  /** Commands in one lifecycle state, most-recent first. */
  public List<Command> findByStatus(CommandStatus status) {
    return listByLaunchedAtDesc().stream().filter(command -> command.status() == status).toList();
  }

  /**
   * RUNNING commands of {@code kind}, newest first — the server-side twin of the frontend's
   * newest-running-chat resolution rule.
   */
  public List<Command> findRunningByKind(CommandKind kind) {
    return listByLaunchedAtDesc().stream()
        .filter(command -> command.kind() == kind && command.isRunning())
        .toList();
  }

  /**
   * Whether any command drove {@code sessionId} — the ownership check behind resume and fork.
   *
   * <p>On the host this was scoped to the session's own workspace, which was a real check because
   * one database held every workspace's commands. Here the store only ever contains this
   * workspace's, so the scope is structural rather than asserted. It also means the check now
   * answers "no" for a session this workspace drove in an earlier container — a resume of such a
   * session is refused rather than allowed, which is the fail-closed direction.
   */
  public boolean ownsSession(String sessionId) {
    return commands.values().stream()
        .anyMatch(
            command ->
                command.agentSessions().stream()
                    .anyMatch(ref -> ref.sessionId().equals(sessionId)));
  }

  /**
   * The recorded harness of the command that drove {@code sessionId} — the source of truth for a
   * resume, which must keep the original session's harness (a Claude session cannot be resumed
   * under Kimi). Empty when no command here owns the session, in which case the caller falls back
   * to normal resolution.
   */
  public Optional<String> agentTypeForSession(String sessionId) {
    return commands.values().stream()
        .filter(
            command ->
                command.agentSessions().stream()
                    .anyMatch(ref -> ref.sessionId().equals(sessionId)))
        .findFirst()
        .map(Command::agentType);
  }

  /** The command's log buffer, created on demand so an import can precede any capture. */
  public CommandLogBuffer log(String commandId) {
    return logs.computeIfAbsent(commandId, id -> new CommandLogBuffer(logCapacity));
  }

  /** How many finished commands have been evicted for capacity since the container started. */
  public long evicted() {
    return evicted.get();
  }

  /**
   * Trim finished commands back to the bound, oldest first. Running commands are skipped whatever
   * their age: their process is alive and every attach, input and terminate resolves through this
   * map, so evicting one would strand a live process with no way to reach it.
   */
  private void evictIfNeeded() {
    if (commands.size() <= maxCommands) {
      return;
    }
    List<Command> finished =
        commands.values().stream()
            .filter(command -> !command.isRunning())
            .sorted(Comparator.comparing(command -> endedAt(command)))
            .toList();
    int excess = commands.size() - maxCommands;
    for (int i = 0; i < excess && i < finished.size(); i++) {
      String id = finished.get(i).id();
      commands.remove(id);
      logs.remove(id);
      evicted.incrementAndGet();
    }
  }

  /** Eviction order key: when the command ended, falling back to launch for anything odd. */
  private static Instant endedAt(Command command) {
    return command.finishedAt() != null ? command.finishedAt() : command.launchedAt();
  }
}
