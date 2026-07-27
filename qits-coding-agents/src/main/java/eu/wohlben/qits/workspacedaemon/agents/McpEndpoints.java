package eu.wohlben.qits.workspacedaemon.agents;

/**
 * Where the agent reaches qits' MCP servers from inside this container, and which project this
 * workspace belongs to.
 *
 * <p>On the host both answers took work. The URL came from {@code QitsHostResolver}, which exists
 * because a container cannot reach qits on {@code localhost} and the right address differs between a
 * shared docker network, plain Linux docker, and WSL2. The project id came from a {@code
 * RepositoryRepository} lookup in its own transaction.
 *
 * <p>Inside the container the project id is no puzzle: it is one of the environment values every
 * workspace container is created with. The address is less settled than it was. Each MCP server now
 * lives under its owning service's segment ({@code /projects/mcp}, {@code /observability/mcp}) on
 * that service's own host, while the daemon is handed exactly one url — the control socket, which
 * is a third service. The implementation lives in the daemon module because every part of the
 * answer comes from configuration; see {@code DaemonMcpEndpoints} for which parts are injected and
 * which are still derived.
 */
public interface McpEndpoints {

  /**
   * The base URL of one named MCP server, e.g. {@code http://qits-projects:8080/projects/mcp} for
   * {@code repository}. Callers append their own scope query parameters.
   *
   * <p>Throws rather than inventing a base for a server it has no address for: a fabricated URL
   * fails as a 404 the agent silently reports as a missing tool, which is indistinguishable from a
   * successful launch.
   */
  String mcpUrl(String server);

  /** The project this workspace's repository belongs to, as a UUID. */
  String projectId();
}
