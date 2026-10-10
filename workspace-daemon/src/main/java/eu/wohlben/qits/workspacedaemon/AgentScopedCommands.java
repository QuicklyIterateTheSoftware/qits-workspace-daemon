package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.commands.AgentLaunchMetadata;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.GuardedInput;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * One agent's view of the shared commands layer: what makes a harness launch this agent's.
 *
 * <p>The harness library launches every command in one directory, the commands layer's root, and
 * vouches only for sessions this container started. An agent worktree needs three things on top,
 * and this seam gives them without a library change:
 *
 * <ul>
 *   <li><b>Its own working directory.</b> The script is prefixed with a {@code cd} into the agent's
 *       wrapper worktree, so the harness runs there and its session files are keyed by that path.
 *   <li><b>Its own credential, in its own process only.</b> The agent's environment is laid under
 *       the launch's own; it reaches the harness process and nothing else, and is never written to
 *       disk (D18). The workspace's secrets are blanked, so the harness never holds them.
 *   <li><b>Its own sessions across restarts.</b> The command store does not outlive the container,
 *       so it cannot vouch for a session started before a restart. The host can: it stores the
 *       agent's session id and hands it back. So a session the agent is known to own is resumable
 *       here even though the store has never seen it (D5).
 * </ul>
 *
 * <p>A turn the library types into the agent's terminal goes through {@code typed}, so the daemon
 * can hold it until the harness has started ({@link TerminalTurnGate}).
 */
final class AgentScopedCommands implements AgentCommands {

  private final AgentCommands delegate;
  private final Path workingDirectory;
  private final Supplier<Map<String, String>> environment;
  private final Supplier<String> ownedSession;
  private final Supplier<String> harness;
  private final BiConsumer<String, Command> launched;
  private final BiPredicate<String, String> typed;

  /** Types turns straight into the delegate; for tests that type nothing. */
  AgentScopedCommands(
      AgentCommands delegate,
      Path workingDirectory,
      Supplier<Map<String, String>> environment,
      Supplier<String> ownedSession,
      Supplier<String> harness,
      BiConsumer<String, Command> launched) {
    this(
        delegate,
        workingDirectory,
        environment,
        ownedSession,
        harness,
        launched,
        (commandId, text) -> delegate.sendKeystrokes(commandId, text));
  }

  /**
   * @param ownedSession the session id the host stored for this agent, or null
   * @param harness the harness that session was driven with, or null
   * @param launched told {@code (commandId, null)} before a spawn, so a hook that fires before the
   *     launch returns already finds its agent, and {@code (commandId, command)} after it
   * @param typed types a turn into a terminal, {@code (commandId, text)}; false when it is not
   *     running
   */
  AgentScopedCommands(
      AgentCommands delegate,
      Path workingDirectory,
      Supplier<Map<String, String>> environment,
      Supplier<String> ownedSession,
      Supplier<String> harness,
      BiConsumer<String, Command> launched,
      BiPredicate<String, String> typed) {
    this.delegate = delegate;
    this.workingDirectory = workingDirectory;
    this.environment = environment;
    this.ownedSession = ownedSession;
    this.harness = harness;
    this.launched = launched;
    this.typed = typed;
  }

  /** The script, run from the agent's wrapper worktree. Package-private for the test. */
  String inWorkingDirectory(String script) {
    return "cd -- " + quote(workingDirectory.toString()) + " || exit 1\n" + script;
  }

  /**
   * The harness's environment: every workspace secret blanked, then the agent's own environment,
   * then the launch's (the library's {@code HOME} still wins).
   *
   * <p>The commands layer lays this map over the daemon's environment and cannot remove a name, so
   * a secret is set to the empty string instead. That keeps the workspace token, the commissioned
   * client and the daemon's API token out of the harness: without the API token a harness cannot
   * drive another agent through this daemon's routes, and without the workspace token its git can
   * push only with the agent's own (an empty {@code QITS_TOKEN} reads as absent to the image's
   * credential helper). Package-private for the test.
   */
  Map<String, String> environmentFor(Map<String, String> launch) {
    Map<String, String> merged = new HashMap<>();
    for (String name : System.getenv().keySet()) {
      if (GitExec.secret(name)) {
        merged.put(name, "");
      }
    }
    for (String name : GitExec.SECRETS) {
      merged.put(name, "");
    }
    merged.putAll(environment.get());
    if (launch != null) {
      merged.putAll(launch);
    }
    return merged;
  }

  @Override
  public Command launchAgent(
      String name,
      String script,
      boolean interactive,
      Map<String, String> env,
      String commandId,
      AgentSessionRef agentSession,
      CommandExitListener onExit,
      AgentLaunchMetadata agent) {
    if (commandId != null) {
      launched.accept(commandId, null);
    }
    Command command =
        delegate.launchAgent(
            name,
            inWorkingDirectory(script),
            interactive,
            environmentFor(env),
            commandId,
            agentSession,
            onExit,
            agent);
    launched.accept(command.id(), command);
    return command;
  }

  @Override
  public Command launchChat(
      String name,
      String script,
      Map<String, String> env,
      String commandId,
      AgentSessionRef agentSession,
      CommandExitListener onExit,
      ChatProtocolFactory protocolFactory,
      AgentLaunchMetadata agent) {
    if (commandId != null) {
      launched.accept(commandId, null);
    }
    Command command =
        delegate.launchChat(
            name,
            inWorkingDirectory(script),
            environmentFor(env),
            commandId,
            agentSession,
            onExit,
            protocolFactory,
            agent);
    launched.accept(command.id(), command);
    return command;
  }

  @Override
  public boolean chatSend(String commandId, String text) {
    return delegate.chatSend(commandId, text);
  }

  @Override
  public boolean chatRename(String commandId, String name) {
    return delegate.chatRename(commandId, name);
  }

  @Override
  public boolean sendKeystrokes(String commandId, String text) {
    return typed.test(commandId, text);
  }

  @Override
  public boolean hasDraft(String commandId) {
    return delegate.hasDraft(commandId);
  }

  @Override
  public GuardedInput sendKeystrokesUnlessDraft(String commandId, String text) {
    return delegate.sendKeystrokesUnlessDraft(commandId, text);
  }

  @Override
  public void reportAgentSession(String commandId, String sessionId, String transcriptPath) {
    delegate.reportAgentSession(commandId, sessionId, transcriptPath);
  }

  @Override
  public boolean ownsSession(String sessionId) {
    return delegate.ownsSession(sessionId)
        || (sessionId != null && sessionId.equals(ownedSession.get()));
  }

  @Override
  public Optional<String> agentTypeForSession(String sessionId) {
    Optional<String> known = delegate.agentTypeForSession(sessionId);
    if (known.isPresent() || sessionId == null || !sessionId.equals(ownedSession.get())) {
      return known;
    }
    return Optional.ofNullable(harness.get());
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
