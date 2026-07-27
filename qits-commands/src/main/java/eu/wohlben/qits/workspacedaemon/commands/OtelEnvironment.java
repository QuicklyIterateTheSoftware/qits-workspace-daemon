package eu.wohlben.qits.workspacedaemon.commands;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composes the {@code OTEL_*} environment a launch gets when its definition has the {@code otel}
 * toggle: the exporter endpoint pointing at qits' OTLP receiver, the protocol pinned to {@code
 * http/protobuf} (the receiver is protobuf-only; this normalizes SDKs that still default to gRPC),
 * and the {@code qits.*} resource attributes that let the telemetry store bucket everything by
 * workspace — the correlation backbone of the observability feature.
 *
 * <p>On the host the endpoint host came from {@code QitsHostResolver}, which existed to answer
 * "what address does a process <em>inside a container</em> reach qits on" from outside that
 * container. The daemon is inside it, and is already told where qits is — it dialled the control
 * socket there — so the resolver does not come along; the base URL is supplied by the daemon
 * module. SDKs append {@code /v1/<signal>} to the endpoint themselves, including its {@code
 * /api/otel} path prefix.
 */
public final class OtelEnvironment {

  private final String qitsBaseUrl;

  /**
   * @param qitsBaseUrl the container-reachable qits origin, e.g. {@code http://qits:8080} — no
   *     trailing slash
   */
  public OtelEnvironment(String qitsBaseUrl) {
    this.qitsBaseUrl = stripTrailingSlash(qitsBaseUrl);
  }

  /**
   * The env overlay for a launch of {@code serviceName} in {@code workspaceId} of {@code repoId},
   * running as registry command {@code commandId}. All ids are UUIDs/slugs, so the comma/equals
   * syntax of {@code OTEL_RESOURCE_ATTRIBUTES} needs no escaping.
   */
  public Map<String, String> forLaunch(
      String repoId, String workspaceId, String commandId, String serviceName) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("OTEL_EXPORTER_OTLP_ENDPOINT", qitsBaseUrl + "/api/otel");
    env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
    env.put("OTEL_SERVICE_NAME", serviceName);
    env.put(
        "OTEL_RESOURCE_ATTRIBUTES",
        "qits.workspace.id="
            + workspaceId
            + ",qits.repository.id="
            + repoId
            + ",qits.command.id="
            + commandId);
    return env;
  }

  /**
   * The capture ingest URL a service's backend relays to its SPA (the config.json {@code capture}
   * section) — composed like the OTLP endpoint above, but independent of the {@code otel} toggle:
   * injected for every service.
   */
  public String captureEndpoint() {
    return qitsBaseUrl + "/api/capture";
  }

  private static String stripTrailingSlash(String url) {
    return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
