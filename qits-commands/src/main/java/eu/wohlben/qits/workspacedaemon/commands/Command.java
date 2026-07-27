package eu.wohlben.qits.workspacedaemon.commands;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One process launched into this workspace: what it was, what it ran, and how it ended.
 *
 * <p>The host's version of this was a JPA entity whose only real foreign key was to {@code
 * Workspace}, with the repository reachable through it — {@code CommandRepository} navigated {@code
 * workspace.repository.id} and {@code workspace.workspaceId} in every query. None of that survives
 * the move: inside the container there is exactly one workspace, the daemon already knows which one
 * (it is told at provisioning), and a command that is not this workspace's cannot exist. So the
 * relation does not become a string id the way migration-plan.md §8 step 4 would require for a
 * cross-context split — it leaves the model entirely, and the queries that used to filter on it
 * collapse to filters on {@link #status} and {@link #kind}.
 *
 * <p>Immutable, with {@code with*} copies for the two transitions a command actually undergoes
 * (finishing, and gaining a session). {@link CommandStore} holds them; nothing else may mutate one.
 *
 * @param id the durable command id, and the key everything else is attached by
 * @param kind how the process is driven and rendered
 * @param branch the branch checked out at launch
 * @param commitHash the full commit SHA checked out at launch
 * @param actionId the resolved action's id; null for launches not backed by a declared action
 * @param actionName the display name shown on the Commands list
 * @param executeScript the rendered script the shell ran
 * @param status the lifecycle state
 * @param exitCode the process exit code once finished; null while running
 * @param interactive whether a human attaches a terminal to it
 * @param agentType the coding-agent harness this launch drove; null for non-agent commands
 * @param launchedAt when the process was spawned
 * @param finishedAt when it ended; null while running
 * @param agentSessions the ordered agent-session lineage; empty for non-agent commands
 */
public record Command(
    String id,
    CommandKind kind,
    String branch,
    String commitHash,
    String actionId,
    String actionName,
    String executeScript,
    CommandStatus status,
    Integer exitCode,
    boolean interactive,
    String agentType,
    Instant launchedAt,
    Instant finishedAt,
    List<AgentSessionRef> agentSessions) {

  public Command {
    agentSessions =
        agentSessions == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(agentSessions));
  }

  /** A freshly spawned command: RUNNING, no exit code, no end time, no sessions yet. */
  public static Command running(
      String id,
      CommandKind kind,
      String branch,
      String commitHash,
      String actionId,
      String actionName,
      String executeScript,
      boolean interactive,
      String agentType,
      Instant launchedAt) {
    return new Command(
        id,
        kind,
        branch,
        commitHash,
        actionId,
        actionName,
        executeScript,
        CommandStatus.RUNNING,
        null,
        interactive,
        agentType,
        launchedAt,
        null,
        List.of());
  }

  /** The same command, ended. */
  public Command finished(CommandStatus endState, Integer code, Instant at) {
    return new Command(
        id,
        kind,
        branch,
        commitHash,
        actionId,
        actionName,
        executeScript,
        endState,
        code,
        interactive,
        agentType,
        launchedAt,
        at,
        agentSessions);
  }

  /**
   * The same command with {@code session} appended. Appending the session that is already last is a
   * no-op, so a hook that re-reports the current session cannot grow the list without bound — the
   * SessionStart hook fires on every resume, including ones that change nothing.
   */
  public Command withSession(AgentSessionRef session) {
    if (!agentSessions.isEmpty()
        && agentSessions.getLast().sessionId().equals(session.sessionId())) {
      return this;
    }
    List<AgentSessionRef> grown = new ArrayList<>(agentSessions);
    grown.add(session);
    return new Command(
        id,
        kind,
        branch,
        commitHash,
        actionId,
        actionName,
        executeScript,
        status,
        exitCode,
        interactive,
        agentType,
        launchedAt,
        finishedAt,
        grown);
  }

  /** The command's current session — the last entry — or null for a non-agent command. */
  public AgentSessionRef currentSession() {
    return agentSessions.isEmpty() ? null : agentSessions.getLast();
  }

  public boolean isRunning() {
    return status == CommandStatus.RUNNING;
  }
}
