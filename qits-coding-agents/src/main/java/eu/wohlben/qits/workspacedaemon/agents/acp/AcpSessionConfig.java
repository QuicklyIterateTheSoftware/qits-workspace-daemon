package eu.wohlben.qits.workspacedaemon.agents.acp;

import java.util.List;
import java.util.function.Consumer;

/**
 * The per-session inputs an {@link AcpChatProtocol} needs to open a Kimi ACP session: the working
 * directory, the scoped MCP servers (protocol-native — passed on {@code session/new}/{@code
 * session/resume}, no {@code mcp.json} file), the optional session to resume, and a sink that
 * receives the {@code session_<uuid>} learned from the {@code session/new} result (wired to the
 * command's session-report so a fresh, unpinnable kimi chat still records its identity).
 *
 * @param cwd the container working directory (always {@code /workspace})
 * @param mcpServers the scoped servers with per-server read-only allowlists
 * @param resumeSessionId a session to resume (no history replay — the chat session replays the
 *     transcript head itself), or {@code null} for a fresh session
 * @param onSessionId invoked once with the established session id
 */
public record AcpSessionConfig(
    String cwd,
    List<AcpMcpServer> mcpServers,
    String resumeSessionId,
    Consumer<String> onSessionId) {

  /**
   * One scoped MCP server as ACP carries it: a stable {@code name}, the HTTP {@code url}, and the
   * bare (prefix-stripped) tool names allowed on it.
   */
  public record AcpMcpServer(String name, String url, List<String> enabledTools) {}
}
