package eu.wohlben.qits.workspacedaemon.agents;

/**
 * The common facade for obtaining a coding-agent harness: {@link #ofType} hands back a fresh {@link
 * CodingAgent} for the requested type, which the caller then configures fluently and renders with
 * {@code start()}/{@code run()}. The factory only selects the implementation — all configuration and
 * command-building lives on the agent itself.
 *
 * <pre>{@code
 * LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE)
 *     .mcpServer("actions", McpServers.httpMcp(scopedUrl))
 *     .allowedTools(READ_ONLY_ACTION_TOOLS)
 *     .start();
 * }</pre>
 */
public final class CodingAgentFactory {

  private CodingAgentFactory() {}

  /** A fresh, unconfigured agent of {@code type}. */
  public static CodingAgent ofType(AgentType type) {
    return switch (type) {
      case CLAUDE -> new ClaudeCodeAgent();
      case KIMI -> new KimiCodeAgent();
    };
  }
}
