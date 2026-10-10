package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.commands.AgentLaunchMetadata;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.GuardedInput;
import java.util.Map;
import java.util.Optional;

/**
 * The real {@link AgentCommands}, with every launched script swapped for a stub, so no test can
 * start a real {@code claude} or {@code kimi}.
 *
 * <p>A developer machine has the harness on {@code PATH}. Without this, a test launch there runs
 * the real agent, with permissions skipped, against the operator's credentials. Everything else —
 * the name, the environment, the session, the protocol, the launch record — goes through unchanged:
 * what is under test is the launch, not the harness.
 *
 * <p>The stub reads its input and discards it. It stays up until it is terminated or its input
 * closes, which happens at the latest when the test JVM exits, so a launch reads as {@code RUNNING}
 * for as long as a test needs it.
 */
final class StubHarness implements AgentCommands {

  static final String SCRIPT = "exec cat > /dev/null";

  private final AgentCommands real;

  StubHarness(AgentCommands real) {
    this.real = real;
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
      AgentLaunchMetadata agent) {
    return real.launchAgent(
        name, SCRIPT, interactive, environment, commandId, agentSession, onExit, agent);
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
      AgentLaunchMetadata agent) {
    return real.launchChat(
        name, SCRIPT, environment, commandId, agentSession, onExit, protocolFactory, agent);
  }

  @Override
  public boolean chatSend(String commandId, String text) {
    return real.chatSend(commandId, text);
  }

  @Override
  public boolean chatRename(String commandId, String name) {
    return real.chatRename(commandId, name);
  }

  @Override
  public boolean sendKeystrokes(String commandId, String text) {
    return real.sendKeystrokes(commandId, text);
  }

  @Override
  public boolean hasDraft(String commandId) {
    return real.hasDraft(commandId);
  }

  @Override
  public GuardedInput sendKeystrokesUnlessDraft(String commandId, String text) {
    return real.sendKeystrokesUnlessDraft(commandId, text);
  }

  @Override
  public void reportAgentSession(String commandId, String sessionId, String transcriptPath) {
    real.reportAgentSession(commandId, sessionId, transcriptPath);
  }

  @Override
  public boolean ownsSession(String sessionId) {
    return real.ownsSession(sessionId);
  }

  @Override
  public Optional<String> agentTypeForSession(String sessionId) {
    return real.agentTypeForSession(sessionId);
  }
}
