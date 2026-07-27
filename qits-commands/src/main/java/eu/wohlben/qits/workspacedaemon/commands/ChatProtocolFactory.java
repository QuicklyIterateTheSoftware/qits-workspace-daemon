package eu.wohlben.qits.workspacedaemon.commands;

/**
 * Builds the {@link ChatProtocol} bound to a freshly spawned chat process. Passed down the chat
 * launch path ({@code AgentLaunchService} → {@code CommandService.launchChat} → {@link
 * CommandRegistry#spawnChat}) so {@code qits-coding-agents} can supply Kimi's ACP client without
 * this module depending on it. A {@code null} factory means the default {@link
 * StreamJsonChatProtocol}, so every existing caller is unaffected.
 */
@FunctionalInterface
public interface ChatProtocolFactory {

  /** Creates the protocol that drives {@code process}'s stdin/stdout. */
  ChatProtocol create(Process process);
}
