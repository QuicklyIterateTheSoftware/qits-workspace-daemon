package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.ActionDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.BootstrapDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.FrameworkDecl;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Map;

/**
 * Serializes a {@link DaemonQitsConfig} to a {@code JsonObject} whose field names match the host's
 * {@code QitsConfig} record tree, so {@code objectMapper.readValue(json, QitsConfig.class)} on the
 * backend reconstructs an equal config (the single wire schema behind {@link
 * eu.wohlben.qits.workspacedaemon.protocol.ConfigView}). Built by hand — no Jackson databind in the
 * native daemon — mirroring the host parser's non-null defaults: collections and environment maps
 * are always emitted (as {@code []}/{@code {}}) and the primitive {@code interactive} is always
 * present, so the round-trip is {@code equals}-exact; other absent optionals are omitted (decode to
 * {@code null}).
 */
public final class ConfigJson {

  private ConfigJson() {}

  /** The empty-config JSON (absent/blank file): {@code repository} null, three empty lists. */
  public static String empty() {
    return toJson(DaemonQitsConfig.EMPTY);
  }

  public static String toJson(DaemonQitsConfig config) {
    JsonObject root = new JsonObject();
    if (config.repository() != null) {
      JsonObject repo = new JsonObject();
      putIfPresent(repo, "mainBranch", config.repository().mainBranch());
      putIfPresent(repo, "archetype", config.repository().archetype());
      root.put("repository", repo);
    }
    JsonArray frameworks = new JsonArray();
    for (FrameworkDecl f : config.frameworks()) {
      JsonObject o = new JsonObject();
      putIfPresent(o, "kind", f.kind());
      putIfPresent(o, "root", f.root());
      frameworks.add(o);
    }
    root.put("frameworks", frameworks);

    JsonArray actions = new JsonArray();
    for (ActionDecl a : config.actions()) {
      JsonObject o = new JsonObject();
      putIfPresent(o, "id", a.id());
      putIfPresent(o, "name", a.name());
      putIfPresent(o, "description", a.description());
      putIfPresent(o, "execute", a.execute());
      putIfPresent(o, "check", a.check());
      o.put("interactive", a.interactive());
      o.put("environment", strMap(a.environment()));
      actions.add(o);
    }
    root.put("actions", actions);

    JsonArray bootstrap = new JsonArray();
    for (BootstrapDecl b : config.bootstrap()) {
      JsonObject o = new JsonObject();
      putIfPresent(o, "id", b.id());
      putIfPresent(o, "name", b.name());
      putIfPresent(o, "description", b.description());
      putIfPresent(o, "execute", b.execute());
      putIfPresent(o, "check", b.check());
      o.put("environment", strMap(b.environment()));
      bootstrap.add(o);
    }
    root.put("bootstrap", bootstrap);

    return root.encode();
  }

  private static JsonObject strMap(Map<String, String> map) {
    JsonObject o = new JsonObject();
    if (map != null) {
      map.forEach(o::put);
    }
    return o;
  }

  private static void putIfPresent(JsonObject o, String key, Object value) {
    if (value != null) {
      o.put(key, value);
    }
  }
}
