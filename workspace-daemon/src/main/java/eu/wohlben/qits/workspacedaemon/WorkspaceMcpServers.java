package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentMcpServers;
import eu.wohlben.qits.agents.LocalMcp;
import eu.wohlben.qits.agents.ScopedMcp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP servers a workspace session attaches: the central platform-access server {@code qits}, and
 * the browser the workspace image ships (qits-1174).
 *
 * <p>{@code qits} is scoped by the caller's own bearer, not by its url, so the launch scope changes
 * nothing. Its address is configured ({@code qits.platform-mcp.url}, {@code QITS_PLATFORM_MCP_URL})
 * and never derived. Without an address it is simply not attached.
 */
final class WorkspaceMcpServers implements AgentMcpServers {

  /** The key the central platform-access MCP server attaches under. */
  static final String PLATFORM_KEY = "qits";

  /**
   * {@link #PLATFORM_KEY}'s pre-approval list — empty, deliberately: the library pre-approves the
   * whole server on Claude itself ({@code mcp__qits__*}), because the caller's own bearer decides
   * what a call may do.
   */
  private static final List<String> PLATFORM_TOOLS = List.of();

  private final Optional<String> platformMcpUrl;

  /**
   * {@code Authorization: Bearer <token>}, or empty — the agent's own token ({@link
   * AgentRuntime.Seat#credential()}), never the workspace's. Not on {@link #BROWSER}: it runs inside
   * this container, not behind the edge.
   */
  private final Map<String, String> platformHeaders;

  WorkspaceMcpServers(Optional<String> platformMcpUrl, Optional<String> token) {
    this.platformMcpUrl =
        platformMcpUrl == null
            ? Optional.empty()
            : platformMcpUrl.map(String::trim).filter(value -> !value.isEmpty());
    this.platformHeaders = headersFor(token);
  }

  /**
   * The servers one agent's harness is wired with: the platform headers carry the agent's own token
   * ({@link AgentRuntime.Seat#credential()}), never the workspace's, so a harness never holds the
   * workspace credential (qits-1152). No token, no header.
   */
  static WorkspaceMcpServers forAgent(Optional<String> platformMcpUrl, AgentRuntime.Seat seat) {
    return new WorkspaceMcpServers(platformMcpUrl, seat.credential());
  }

  private static Map<String, String> headersFor(Optional<String> token) {
    return token == null
        ? Map.of()
        : token
            .filter(value -> !value.isBlank())
            .map(value -> Map.of("Authorization", "Bearer " + value.trim()))
            .orElse(Map.of());
  }

  /** {@code qits} when it has an address, else nothing — at every scope. */
  @Override
  public List<ScopedMcp> serversFor(AgentMcpScope scope) {
    return platformMcpUrl
        .map(url -> List.of(new ScopedMcp(PLATFORM_KEY, url, PLATFORM_TOOLS, platformHeaders)))
        .orElse(List.of());
  }

  /**
   * The browser the workspace image ships ({@code qits-browser-mcp}, Playwright's MCP server on the
   * image's own Chromium), attached to every Claude launch so an agent can open what it serves,
   * click through it, take screenshots and read the console and network. Every tool pre-approved:
   * the browser reaches only what this container can already reach, and a prompting surface should
   * not stop on its first look at a page.
   */
  static final LocalMcp BROWSER =
      new LocalMcp("browser", "qits-browser-mcp", List.of("mcp__browser__*"));

  @Override
  public List<LocalMcp> localServers() {
    return List.of(BROWSER);
  }

  /** {@code Authorization: Bearer <token>} when this agent has a token, empty otherwise. */
  @Override
  public Map<String, String> platformHeaders() {
    return platformHeaders;
  }
}
