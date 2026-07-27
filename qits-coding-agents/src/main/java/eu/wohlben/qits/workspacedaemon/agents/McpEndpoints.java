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
 * <p>Inside the container neither is a puzzle: the daemon already dialled qits to open its control
 * socket, so it knows the address, and the project id is one of the environment values every
 * workspace container is created with. The implementation lives in the daemon module because both
 * come from configuration.
 */
public interface McpEndpoints {

  /**
   * The base URL of one MCP server, e.g. {@code http://qits:8080/mcp/repository}. Callers append
   * their own scope query parameters.
   */
  String mcpUrl(String server);

  /** The project this workspace's repository belongs to, as a UUID. */
  String projectId();
}
