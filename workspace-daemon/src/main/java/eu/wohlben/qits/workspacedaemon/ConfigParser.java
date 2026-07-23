package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.ActionDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.BootstrapDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.DaemonDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.FrameworkDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.HealthCheckDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.ObserverDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.RepositorySection;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.SourceDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.WebViewDecl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The in-container {@code .qits-config.yml} parser — the daemon's own copy of the host's {@code
 * QitsConfigParser.parse} (the {@code workspace-daemon} module can't depend on {@code domain}).
 * Structurally identical: SnakeYAML's {@link SafeConstructor} (plain maps/lists, never instantiates
 * classes from repository content), a required {@code version: 1}, kebab-case keys mapped onto the
 * {@link DaemonQitsConfig} record tree (whose components mirror the host's), collections defaulted
 * to empty (never null), and enum-ish values <b>normalized</b> ({@code upper}, {@code -}→{@code _})
 * to the host enum constant spelling so the JSON round-trips into a host {@code QitsConfig}. Parity
 * with the host parser is locked by {@code ConfigParserTest}.
 *
 * <p>Enum <em>validation</em> is deliberately left to the backend's {@code
 * objectMapper.readValue(json, QitsConfig.class)}: an unknown value normalizes to a string that no
 * host enum matches, so deserialization fails there and the workspace surfaces a config warning —
 * the same "invalid file ⇒ warning, never block" end state as a host-side structural error here.
 * The daemon holds this parsed tree for parts 3/4 (bootstrap/daemons run from it).
 */
public final class ConfigParser {

  /** A structural problem in {@code .qits-config.yml} — surfaced to qits as a workspace warning. */
  public static final class ConfigException extends RuntimeException {
    public ConfigException(String message) {
      super(message);
    }

    public ConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private ConfigParser() {}

  /**
   * Parse YAML content into the config record tree. Blank/{@code null} content and a bare {@code
   * null} document both yield {@link DaemonQitsConfig#EMPTY}; anything structurally invalid (bad
   * YAML, wrong {@code version}, missing required field, wrong shape) throws {@link
   * ConfigException}.
   */
  public static DaemonQitsConfig parse(String content) {
    if (content == null || content.isBlank()) {
      return DaemonQitsConfig.EMPTY;
    }
    Object root;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      root = yaml.load(content);
    } catch (Exception e) {
      throw new ConfigException("Invalid YAML: " + e.getMessage(), e);
    }
    if (root == null) {
      return DaemonQitsConfig.EMPTY;
    }
    Map<String, Object> map = asMap(root, "document root");

    Object version = map.get("version");
    if (!(version instanceof Number n) || n.intValue() != 1) {
      throw new ConfigException("Unsupported or missing 'version' (expected 1): " + version);
    }

    return new DaemonQitsConfig(
        repositorySection(map.get("repository")),
        frameworks(map.get("frameworks")),
        actions(map.get("actions")),
        daemons(map.get("daemons")),
        bootstrap(map.get("bootstrap")));
  }

  private static RepositorySection repositorySection(Object raw) {
    if (raw == null) {
      return null;
    }
    Map<String, Object> m = asMap(raw, "repository");
    return new RepositorySection(str(m, "main-branch"), enumOf(m.get("archetype")));
  }

  private static List<FrameworkDecl> frameworks(Object raw) {
    List<FrameworkDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "frameworks")) {
      Map<String, Object> m = asMap(item, "frameworks[]");
      out.add(new FrameworkDecl(reqStr(m, "kind", "frameworks[]"), str(m, "root")));
    }
    return out;
  }

  private static List<ActionDecl> actions(Object raw) {
    List<ActionDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "actions")) {
      Map<String, Object> m = asMap(item, "actions[]");
      out.add(
          new ActionDecl(
              reqStr(m, "name", "actions[]"),
              str(m, "description"),
              str(m, "execute"),
              str(m, "check"),
              bool(m, "interactive", false),
              strMap(m.get("environment"), "actions[].environment")));
    }
    return out;
  }

  private static List<BootstrapDecl> bootstrap(Object raw) {
    List<BootstrapDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "bootstrap")) {
      Map<String, Object> m = asMap(item, "bootstrap[]");
      out.add(
          new BootstrapDecl(
              reqStr(m, "name", "bootstrap[]"),
              str(m, "description"),
              str(m, "execute"),
              str(m, "check"),
              strMap(m.get("environment"), "bootstrap[].environment")));
    }
    return out;
  }

  private static List<DaemonDecl> daemons(Object raw) {
    List<DaemonDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "daemons")) {
      Map<String, Object> m = asMap(item, "daemons[]");
      out.add(
          new DaemonDecl(
              reqStr(m, "name", "daemons[]"),
              str(m, "description"),
              str(m, "start"),
              str(m, "ready-pattern"),
              boolOrNull(m.get("otel")),
              boolOrNull(m.get("auto-start")),
              enumOf(m.get("restart-policy")),
              intOrNull(m.get("max-restarts"), "max-restarts"),
              str(m, "stop-signal"),
              strMap(m.get("environment"), "daemons[].environment"),
              webView(m.get("web-view")),
              observers(m.get("observers")),
              sources(m.get("sources")),
              healthChecks(m.get("health-checks"))));
    }
    return out;
  }

  private static WebViewDecl webView(Object raw) {
    if (raw == null) {
      return null;
    }
    Map<String, Object> m = asMap(raw, "web-view");
    return new WebViewDecl(
        intOrNull(m.get("port"), "web-view.port"), str(m, "entry-path"), str(m, "base-path"));
  }

  private static List<ObserverDecl> observers(Object raw) {
    List<ObserverDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "observers")) {
      Map<String, Object> m = asMap(item, "observers[]");
      out.add(
          new ObserverDecl(enumOf(m.get("kind")), str(m, "pattern"), enumOf(m.get("severity"))));
    }
    return out;
  }

  private static List<SourceDecl> sources(Object raw) {
    List<SourceDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "sources")) {
      Map<String, Object> m = asMap(item, "sources[]");
      out.add(new SourceDecl(reqStr(m, "path", "sources[]"), str(m, "label")));
    }
    return out;
  }

  private static List<HealthCheckDecl> healthChecks(Object raw) {
    List<HealthCheckDecl> out = new ArrayList<>();
    for (Object item : asList(raw, "health-checks")) {
      Map<String, Object> m = asMap(item, "health-checks[]");
      out.add(
          new HealthCheckDecl(
              reqStr(m, "name", "health-checks[]"),
              enumOf(m.get("kind")),
              intOrNull(m.get("port"), "health-checks[].port"),
              str(m, "path"),
              str(m, "expect-status"),
              str(m, "command"),
              longOrNull(m.get("interval-ms"), "interval-ms"),
              longOrNull(m.get("timeout-ms"), "timeout-ms"),
              intOrNull(m.get("healthy-threshold"), "healthy-threshold"),
              intOrNull(m.get("unhealthy-threshold"), "unhealthy-threshold"),
              longOrNull(m.get("initial-delay-ms"), "initial-delay-ms")));
    }
    return out;
  }

  // ---- typed extraction helpers (ported from QitsConfigParser) --------------------------------

  private static Map<String, Object> asMap(Object raw, String where) {
    if (raw instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    throw new ConfigException("Expected a mapping at " + where + ", got: " + typeOf(raw));
  }

  private static List<Object> asList(Object raw, String where) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof List<?> l) {
      return new ArrayList<>(l);
    }
    throw new ConfigException("Expected a list at " + where + ", got: " + typeOf(raw));
  }

  private static String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null) {
      return null;
    }
    String s = String.valueOf(v);
    return s.isBlank() ? null : s;
  }

  private static String reqStr(Map<String, Object> m, String key, String where) {
    String v = str(m, key);
    if (v == null) {
      throw new ConfigException("Missing required '" + key + "' at " + where);
    }
    return v;
  }

  private static boolean bool(Map<String, Object> m, String key, boolean fallback) {
    Boolean v = boolOrNull(m.get(key));
    return v == null ? fallback : v;
  }

  private static Boolean boolOrNull(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Boolean b) {
      return b;
    }
    throw new ConfigException("Expected a boolean, got: " + typeOf(v));
  }

  private static Integer intOrNull(Object v, String where) {
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.intValue();
    }
    throw new ConfigException("Expected an integer at " + where + ", got: " + typeOf(v));
  }

  private static Long longOrNull(Object v, String where) {
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return n.longValue();
    }
    throw new ConfigException("Expected a number at " + where + ", got: " + typeOf(v));
  }

  private static Map<String, String> strMap(Object raw, String where) {
    if (raw == null) {
      return Map.of();
    }
    Map<String, Object> m = asMap(raw, where);
    Map<String, String> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
    return out;
  }

  /**
   * Normalize an enum-ish scalar to the host enum constant spelling ({@code log-level} → {@code
   * LOG_LEVEL}); {@code null} passes through. No validation here — an unknown value is caught by
   * the backend's typed deserialize (see the class doc).
   */
  private static String enumOf(Object v) {
    if (v == null) {
      return null;
    }
    return String.valueOf(v).trim().toUpperCase().replace('-', '_');
  }

  private static String typeOf(Object v) {
    return v == null ? "null" : v.getClass().getSimpleName();
  }
}
