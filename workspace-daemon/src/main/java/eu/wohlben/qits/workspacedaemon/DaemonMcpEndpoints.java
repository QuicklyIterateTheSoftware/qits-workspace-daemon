package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.agents.McpEndpoints;
import java.net.URI;
import java.util.Optional;

/**
 * Derives the agent's MCP base URLs from the control-socket URL this daemon already dialled.
 *
 * <p>On the host this needed {@code QitsHostResolver}: a container cannot reach qits on {@code
 * localhost}, and the address that does work differs between a shared docker network, plain Linux
 * docker, and WSL2 — so it probed {@code /proc/version}, opened a UDP socket to find the LAN
 * address, and cached the answer. None of that is needed from in here. The container was handed
 * {@code qits.workspace-daemon.url} at creation and has an open socket proving it resolves, so the
 * same authority with an {@code http} scheme is the answer.
 *
 * <p>The explicit overrides stay, for pointing an agent at a different qits instance.
 */
final class DaemonMcpEndpoints implements McpEndpoints {

  private final String httpBase;
  private final String projectId;
  private final Optional<String> actionsOverride;
  private final Optional<String> repositoryOverride;

  /**
   * @param daemonUrl the control-socket URL, {@code ws://host:port/api/workspace-daemon/<id>}
   * @throws IllegalStateException if {@code daemonUrl} carries no usable authority — a daemon
   *     without one never connected, so it cannot be serving agent launches either
   */
  DaemonMcpEndpoints(
      String daemonUrl,
      String projectId,
      Optional<String> actionsOverride,
      Optional<String> repositoryOverride) {
    this.httpBase = httpBaseOf(daemonUrl);
    this.projectId = projectId;
    this.actionsOverride = actionsOverride;
    this.repositoryOverride = repositoryOverride;
  }

  @Override
  public String mcpUrl(String server) {
    Optional<String> override =
        switch (server) {
          case "actions" -> actionsOverride;
          case "repository" -> repositoryOverride;
          default -> Optional.<String>empty();
        };
    return override.filter(value -> !value.isBlank()).orElse(httpBase + "/mcp/" + server);
  }

  @Override
  public String projectId() {
    return projectId;
  }

  /** {@code ws://qits:8080/api/workspace-daemon/x} → {@code http://qits:8080}. */
  static String httpBaseOf(String daemonUrl) {
    if (daemonUrl == null || daemonUrl.isBlank()) {
      throw new IllegalStateException(
          "No qits.workspace-daemon.url configured — the agent MCP endpoints cannot be derived");
    }
    URI uri = URI.create(daemonUrl.trim());
    String scheme = "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalStateException("Malformed qits.workspace-daemon.url: " + daemonUrl);
    }
    return uri.getPort() < 0 ? scheme + "://" + host : scheme + "://" + host + ":" + uri.getPort();
  }
}
