package eu.wohlben.qits.workspacedaemon.commands;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.UUID;

/**
 * Owns every write to a {@link Command}, isolated here so the status transitions can be driven both
 * from request threads (create) and from the registry's reader threads (exit/terminate).
 * Transitions away from {@code RUNNING} are idempotent — first writer wins — so the
 * manual-terminate path and the reader-thread exit path can race without double-marking.
 *
 * <p>The host's version was this class plus a lot of transaction machinery: {@code @Transactional}
 * on every method and {@code @ActivateRequestContext} on the off-request ones, because a Panache
 * write from the registry's reader thread had no CDI request context to borrow. With an in-memory
 * store there is no session to activate and no transaction to demarcate; what is left is the state
 * machine, which is the part that was ever interesting.
 *
 * <p>{@code reconcileRunningAsInterrupted} does not come along. It existed because {@code RUNNING}
 * rows outlived the JVM that owned their processes, so a restart had to find and mark the orphans.
 * Here the store is created with the process — an empty store cannot contain an orphan.
 */
public final class CommandLifecycleService {

  private static final Logger LOG = System.getLogger(CommandLifecycleService.class.getName());

  private final CommandStore store;
  private final CommandChangeListener changeListener;

  public CommandLifecycleService(CommandStore store, CommandChangeListener changeListener) {
    this.store = store;
    this.changeListener = changeListener;
  }

  /**
   * Record a new RUNNING command and return it. {@code actionId} is null for launches not backed by
   * an action (e.g. an agent session). The id is caller-supplyable rather than generated, because an
   * agent launch renders it into the session-report hook URL before the command exists; null
   * generates a fresh one. {@code initialAgentSession} is the first entry of an agent launch's
   * session list, recorded with the command so the hook can never race it; null for everything that
   * is not an agent session.
   */
  public Command createRunning(
      String branch,
      String commitHash,
      String actionId,
      String actionName,
      String executeScript,
      boolean interactive,
      CommandKind kind,
      String commandId,
      AgentSessionRef initialAgentSession,
      String agentType) {
    Command command =
        Command.running(
            commandId != null ? commandId : UUID.randomUUID().toString(),
            kind,
            branch,
            commitHash,
            actionId,
            actionName,
            executeScript,
            interactive,
            agentType,
            Instant.now());
    if (initialAgentSession != null) {
      command = command.withSession(initialAgentSession);
    }
    store.put(command);
    fireChanged();
    return command;
  }

  /**
   * Record a harness-reported session identity (the SessionStart hook's payload) on a running
   * command. The first report normally confirms the pinned/resumed id — it only fills in the
   * authoritative {@code transcriptPath}. A report with a different id means the user switched
   * sessions inside the interactive TUI (e.g. {@code /resume}): a {@code SWITCHED} entry is
   * appended so the list stays the faithful order of sessions driven.
   */
  public Command recordAgentSessionReport(
      String commandId, String sessionId, String transcriptPath) {
    Command command =
        store
            .find(commandId)
            .orElseThrow(() -> new CommandNotFoundException("Command not found: " + commandId));
    if (!command.isRunning()) {
      throw new InvalidCommandRequestException("Command is not running: " + commandId);
    }
    AgentSessionRef current = command.currentSession();
    Command updated;
    if (current != null && current.sessionId().equals(sessionId)) {
      // Same session: the report's only new information is the authoritative transcript path, and
      // only if we did not already have one.
      if (current.transcriptPath() != null) {
        return command;
      }
      updated = replaceCurrentSession(command, withTranscript(current, transcriptPath));
    } else {
      if (current == null) {
        // Agent launches that cannot pin a session id (Kimi Code) start with an empty list; the
        // first hook report establishes the session.
        LOG.log(
            Level.DEBUG, () -> "Session report for command " + commandId + " without a pinned session");
      }
      updated =
          command.withSession(
              new AgentSessionRef(
                  sessionId,
                  current == null ? AgentSessionSource.REPORTED : AgentSessionSource.SWITCHED,
                  null,
                  transcriptPath,
                  Instant.now()));
    }
    Command stored = store.update(commandId, existing -> updated).orElse(updated);
    fireChanged();
    return stored;
  }

  private static AgentSessionRef withTranscript(AgentSessionRef session, String transcriptPath) {
    return new AgentSessionRef(
        session.sessionId(),
        session.source(),
        session.forkedFromSessionId(),
        transcriptPath,
        session.recordedAt());
  }

  /**
   * Swap the last session entry. {@link Command#withSession} appends rather than replaces (and
   * refuses to re-append the current id), so filling in a transcript path needs the list rebuilt.
   */
  private static Command replaceCurrentSession(Command command, AgentSessionRef replacement) {
    java.util.List<AgentSessionRef> sessions =
        new java.util.ArrayList<>(command.agentSessions());
    sessions.set(sessions.size() - 1, replacement);
    return new Command(
        command.id(),
        command.kind(),
        command.branch(),
        command.commitHash(),
        command.actionId(),
        command.actionName(),
        command.executeScript(),
        command.status(),
        command.exitCode(),
        command.interactive(),
        command.agentType(),
        command.launchedAt(),
        command.finishedAt(),
        sessions);
  }

  public void markExited(String commandId, int exitCode) {
    finishIfRunning(commandId, CommandStatus.EXITED, exitCode);
  }

  public void markTerminated(String commandId, int exitCode) {
    finishIfRunning(commandId, CommandStatus.TERMINATED, exitCode);
  }

  private void finishIfRunning(String commandId, CommandStatus status, int exitCode) {
    boolean[] transitioned = {false};
    store.update(
        commandId,
        command -> {
          if (!command.isRunning()) {
            return command; // idempotent: whoever transitions it first wins.
          }
          transitioned[0] = true;
          return command.finished(status, exitCode, Instant.now());
        });
    if (transitioned[0]) {
      fireChanged();
    }
  }

  /** Never let a listener's failure escape into a reader thread and kill the pump. */
  private void fireChanged() {
    if (changeListener == null) {
      return;
    }
    try {
      changeListener.commandsChanged();
    } catch (RuntimeException e) {
      LOG.log(Level.DEBUG, "Command change listener failed", e);
    }
  }
}
