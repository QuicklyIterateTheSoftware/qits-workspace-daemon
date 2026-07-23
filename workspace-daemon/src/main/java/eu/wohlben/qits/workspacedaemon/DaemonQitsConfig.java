package eu.wohlben.qits.workspacedaemon;

import java.util.List;
import java.util.Map;

/**
 * The daemon's framework-free representation of a parsed {@code .qits-config.yml}. Its component
 * names <b>mirror the host's {@code QitsConfig} record tree 1:1</b> so that {@link ConfigJson}'s
 * serialization ({@code JsonObject} with these exact keys) round-trips into a host {@code
 * QitsConfig} via Jackson — the single wire schema for {@link
 * eu.wohlben.qits.workspacedaemon.protocol.ConfigView}. The {@code workspace-daemon} module cannot
 * depend on {@code domain} (native-image leanness), so this is a deliberate, parity-tested copy;
 * enum-typed host fields ({@code archetype}, {@code restartPolicy}, observer {@code kind}/{@code
 * severity}, health-check {@code kind}) degrade to normalized {@code String}s (uppercase, {@code
 * -}→{@code _}) matching the host enum constant names.
 *
 * <p>Collections are non-null (empty when absent), matching the host parser's defaults so the
 * round-trip is {@code equals}-exact even for nested records with no config-declared entries.
 */
public record DaemonQitsConfig(
    RepositorySection repository,
    List<FrameworkDecl> frameworks,
    List<ActionDecl> actions,
    List<DaemonDecl> daemons,
    List<BootstrapDecl> bootstrap) {

  /** An absent/blank file — the empty config that keeps a config-free branch a no-op. */
  public static final DaemonQitsConfig EMPTY =
      new DaemonQitsConfig(null, List.of(), List.of(), List.of(), List.of());

  public record RepositorySection(String mainBranch, String archetype) {}

  public record FrameworkDecl(String kind, String root) {}

  public record ActionDecl(
      String name,
      String description,
      String execute,
      String check,
      boolean interactive,
      Map<String, String> environment) {}

  public record DaemonDecl(
      String name,
      String description,
      String start,
      String readyPattern,
      Boolean otel,
      Boolean autoStart,
      String restartPolicy,
      Integer maxRestarts,
      String stopSignal,
      Map<String, String> environment,
      WebViewDecl webView,
      List<ObserverDecl> observers,
      List<SourceDecl> sources,
      List<HealthCheckDecl> healthChecks) {}

  public record BootstrapDecl(
      String name,
      String description,
      String execute,
      String check,
      Map<String, String> environment) {}

  public record WebViewDecl(Integer port, String entryPath, String basePath) {}

  public record ObserverDecl(String kind, String pattern, String severity) {}

  public record SourceDecl(String path, String label) {}

  public record HealthCheckDecl(
      String name,
      String kind,
      Integer port,
      String path,
      String expectStatus,
      String command,
      Long intervalMs,
      Long timeoutMs,
      Integer healthyThreshold,
      Integer unhealthyThreshold,
      Long initialDelayMs) {}
}
