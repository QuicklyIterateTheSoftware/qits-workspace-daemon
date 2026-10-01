package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.McpEndpoints;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.net.URI;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Resolves the agent's MCP base URLs — one per named server, each under its owning service's
 * segment.
 *
 * <p>On the host this needed {@code QitsHostResolver}: a container cannot reach qits on {@code
 * localhost}, and the address that does work differs between a shared docker network, plain Linux
 * docker, and WSL2 — so it probed {@code /proc/version}, opened a UDP socket to find the LAN
 * address, and cached the answer. None of that is needed from in here.
 *
 * <p><b>What changed, and what is now assumed.</b> There is no {@code /mcp/<server>} family any
 * more. The path convention is {@code /<segment>/mcp}, served by the owning service verbatim —
 * through the gateway <em>and</em> on {@code qits-net} — so each server has both its own path and
 * its own <b>host</b>:
 *
 * <ul>
 *   <li>{@code repository} → {@code /projects/mcp}, served by <b>qits-projects</b>. The server name
 *       stays {@code repository}: those tools are genuinely repository-scoped.
 *   <li>{@code observability} → {@code /observability/mcp}, served by <b>qits-observability</b>.
 *       Renamed from {@code repository}, which is the point of the exercise — two services declared
 *       a server by that name, so this daemon could only ever address one of them, and the
 *       telemetry half was the one it lost.
 *   <li>{@code actions} → <b>nowhere</b>. The action tools never left the monolith
 *       (migration-plan.md §9 item 6), so there is no segment to derive and no host to derive it
 *       from.
 *   <li>{@code qits} → {@code qits.platform-mcp.url} only (qits-630). The central
 *       platform-access MCP server is a wholly separate service from qits-workspaces, so, like
 *       {@code actions}, there is no fallback to derive. Unlike {@code actions} it is attached by
 *       default on every surface, so {@link #platformUrl} is the non-throwing way to ask whether it
 *       is there — see {@link WorkspaceMcpServers}.
 * </ul>
 *
 * <p>The container is handed exactly one address, {@code qits.workspace-daemon.url} — the control
 * socket, which is qits-workspaces. Reusing its authority for the MCP servers only holds where
 * <b>one</b> authority routes every segment, i.e. where that url points at the gateway. That
 * topology is not settled (migration-path-conventions.md §4 item 9), so the derivation stays as a
 * fallback and <b>says so</b> at wiring time; {@code qits.repository-mcp.url} and {@code
 * qits.observability-mcp.url} name the servers outright when they are not co-authoritative. The
 * overrides also keep their original job: pointing an agent at a different qits instance.
 */
final class DaemonMcpEndpoints implements McpEndpoints {

  private static final Logger LOG = Logger.getLogger(DaemonMcpEndpoints.class);

  /** The MCP server names this daemon knows how to address, and their owning segments. */
  static final String REPOSITORY_SERVER = "repository";

  static final String OBSERVABILITY_SERVER = "observability";
  static final String ACTIONS_SERVER = "actions";

  /**
   * The central platform-access MCP server (qits-630) — the {@code qits} CLI served over HTTP by
   * qits-platform-access-mcp-service. Unlike {@link #REPOSITORY_SERVER} and {@link
   * #OBSERVABILITY_SERVER} it shares no authority with this daemon's control socket, and unlike
   * {@link #ACTIONS_SERVER} it is attached by default on every surface (the document's {@code
   * AgentSurfaceDefaults} turns it on everywhere) rather than by an operator's deliberate choice —
   * so {@link WorkspaceMcpServers} checks {@link #platformUrl()} before attaching it instead of
   * calling {@link #mcpUrl} and treating an unconfigured host as a launch-stopping refusal.
   */
  static final String PLATFORM_SERVER = "qits";

  private final String httpBase;
  private final String projectId;
  private final Optional<String> actionsOverride;
  private final Optional<String> repositoryOverride;
  private final Optional<String> observabilityOverride;
  private final Optional<String> platformOverride;

  /**
   * @param daemonUrl the control-socket URL, {@code ws://host:port/workspaces/daemon/<id>}
   * @throws IllegalStateException if {@code daemonUrl} carries no usable authority — a daemon
   *     without one never connected, so it cannot be serving agent launches either
   */
  DaemonMcpEndpoints(
      String daemonUrl,
      String projectId,
      Optional<String> actionsOverride,
      Optional<String> repositoryOverride,
      Optional<String> observabilityOverride,
      Optional<String> platformOverride) {
    this.httpBase = httpBaseOf(daemonUrl);
    this.projectId = projectId;
    this.platformOverride = platformOverride;
    this.actionsOverride = actionsOverride;
    this.repositoryOverride = repositoryOverride;
    this.observabilityOverride = observabilityOverride;
    warnAboutDerivedHosts();
  }

  /**
   * @throws InvalidCommandRequestException when the requested server has no address — an unknown
   *     name, or {@code actions}, which no service in the split serves. Deliberately not a silent
   *     fallback: a made-up base fails later as a 404 the agent reports as "tool unavailable", and
   *     the launch reads as having worked. {@code InvalidCommandRequestException} because it is
   *     the one {@code WorkspaceApi} answers with the message attached (400) rather than
   *     swallowing into "Internal error".
   */
  @Override
  public String mcpUrl(String server) {
    return switch (server) {
      case REPOSITORY_SERVER -> configured(repositoryOverride).orElse(httpBase + "/projects/mcp");
      case OBSERVABILITY_SERVER ->
          configured(observabilityOverride).orElse(httpBase + "/observability/mcp");
      case ACTIONS_SERVER ->
          configured(actionsOverride)
              .orElseThrow(
                  () ->
                      new InvalidCommandRequestException(
                          "No address for the 'actions' MCP server: set qits.actions-mcp.url"
                              + " (QITS_ACTIONS_MCP_URL). No service serves it — the action tools"
                              + " are still monolith-only (migration-plan.md §9 item 6), so there"
                              + " is no segment to derive."));
      case PLATFORM_SERVER ->
          configured(platformOverride)
              .orElseThrow(
                  () ->
                      new InvalidCommandRequestException(
                          "No address for the 'qits' MCP server: set qits.platform-mcp.url"
                              + " (QITS_PLATFORM_MCP_URL). qits-platform-access-mcp-service shares"
                              + " no authority with this daemon's control socket, so there is no"
                              + " segment to derive. Callers that may attach this server by"
                              + " default should check platformUrl() instead of reaching this"
                              + " refusal — see WorkspaceMcpServers."));
      default ->
          throw new InvalidCommandRequestException(
              "Unknown MCP server '"
                  + server
                  + "': this daemon addresses "
                  + REPOSITORY_SERVER
                  + ", "
                  + OBSERVABILITY_SERVER
                  + ", "
                  + ACTIONS_SERVER
                  + " and "
                  + PLATFORM_SERVER
                  + ".");
    };
  }

  @Override
  public String projectId() {
    return projectId;
  }

  /**
   * Whether {@link #PLATFORM_SERVER} is configured, and its URL if so — the non-throwing twin of
   * {@link #mcpUrl} for exactly that one server.
   *
   * <p>Every other server here either derives a fallback or is attached only when an operator
   * deliberately asks for it, so {@link #mcpUrl} refusing a missing address is always the right
   * answer. {@code qits} is neither: it has no derivable host, like {@code actions}, but it is
   * also attached by default on <b>every</b> surface rather than by a deliberate choice — the
   * document's shipped defaults turn it on everywhere. A container whose host has not yet deployed
   * or configured the central server must not have every launch refuse over it, so {@link
   * WorkspaceMcpServers} asks here first and simply omits the server when this answers empty.
   */
  Optional<String> platformUrl() {
    return configured(platformOverride);
  }

  private static Optional<String> configured(Optional<String> override) {
    return override.map(String::trim).filter(value -> !value.isEmpty());
  }

  /**
   * Names, once at wiring time, every server whose host was assumed rather than told. The addresses
   * fail as connection errors or 404s if the assumption is wrong, and a 404 from an MCP server is
   * invisible in a running agent session — so it is stated where an operator reading the daemon's
   * boot log will see it.
   */
  private void warnAboutDerivedHosts() {
    StringBuilder derived = new StringBuilder();
    if (configured(repositoryOverride).isEmpty()) {
      derived.append(" ").append(REPOSITORY_SERVER).append(" (qits-projects)");
    }
    if (configured(observabilityOverride).isEmpty()) {
      derived.append(" ").append(OBSERVABILITY_SERVER).append(" (qits-observability)");
    }
    if (derived.isEmpty()) {
      return;
    }
    LOG.warnf(
        "MCP host derived from the control-socket authority %s for:%s. The control socket is"
            + " qits-workspaces, so this only holds if that authority routes every segment (a"
            + " gateway). Set qits.repository-mcp.url / qits.observability-mcp.url otherwise.",
        httpBase, derived);
  }

  /**
   * The authority of the dial-home url with an http scheme — {@code
   * ws://qits:8080/workspaces/daemon/x} → {@code http://qits:8080}. The <b>path</b> is
   * deliberately discarded: it addresses qits-workspaces' control socket and tells us nothing about
   * where any MCP server lives. What survives is the authority, and {@link #mcpUrl} appends each
   * server's own segment.
   */
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
