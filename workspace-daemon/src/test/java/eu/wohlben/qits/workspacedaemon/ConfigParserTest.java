package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The in-container config parser's contract, mirroring the host {@code QitsConfigParser}:
 * kebab-case YAML → the {@link DaemonQitsConfig} tree → {@code QitsConfig}-shaped JSON with
 * camelCase keys, normalized enum spellings, and empty collections always present (so the backend's
 * {@code objectMapper.readValue(json, QitsConfig.class)} reconstructs an {@code equals} config).
 * True cross-boundary parity against {@code QitsConfigParser} on the shared fixture rides the
 * extended real-docker path; this pins the daemon side in isolation (no {@code domain} on the
 * classpath here).
 */
class ConfigParserTest {

  private static final String FULL_CONFIG =
      """
      version: 1
      repository:
        main-branch: main
        archetype: service
      frameworks:
        - kind: quarkus
          root: .
      actions:
        - name: build
          description: Build it
          execute: mvn -B verify
          environment:
            CI: "true"
      services:
        - name: dev
          start: mvn quarkus:dev
          ready-pattern: Listening on
          otel: true
          auto-start: true
          restart-policy: on-failure
          max-restarts: 3
          stop-signal: TERM
          environment:
            LOG_LEVEL: DEBUG
          web-view:
            port: 8080
            entry-path: /
          health-checks:
            - name: ready
              kind: http
              port: 8080
              path: /q/health
              interval-ms: 5000
      bootstrap:
        - name: install
          execute: mvn -o -B -DskipTests install
      """;

  private static JsonObject json(String yaml) {
    return new JsonObject(ConfigJson.toJson(ConfigParser.parse(yaml)));
  }

  @Test
  void fullConfigSerializesToQitsConfigShapedJson() {
    JsonObject root = json(FULL_CONFIG);

    assertEquals("main", root.getJsonObject("repository").getString("mainBranch"));
    assertEquals("SERVICE", root.getJsonObject("repository").getString("archetype"));

    JsonObject action = root.getJsonArray("actions").getJsonObject(0);
    assertEquals("build", action.getString("name"));
    assertEquals("mvn -B verify", action.getString("execute"));
    assertFalse(action.getBoolean("interactive"), "primitive interactive is always emitted");
    assertEquals("true", action.getJsonObject("environment").getString("CI"));

    JsonObject daemon = root.getJsonArray("services").getJsonObject(0);
    assertEquals("dev", daemon.getString("name"));
    assertEquals("Listening on", daemon.getString("readyPattern"));
    assertTrue(daemon.getBoolean("otel"));
    assertTrue(daemon.getBoolean("autoStart"));
    assertEquals(
        "ON_FAILURE", daemon.getString("restartPolicy"), "enum normalized upper + '-'→'_'");
    assertEquals(3, daemon.getInteger("maxRestarts"));
    assertEquals("TERM", daemon.getString("stopSignal"));
    assertEquals("DEBUG", daemon.getJsonObject("environment").getString("LOG_LEVEL"));
    assertEquals(8080, daemon.getJsonObject("webView").getInteger("port"));
    assertEquals("/", daemon.getJsonObject("webView").getString("entryPath"));

    JsonObject health = daemon.getJsonArray("healthChecks").getJsonObject(0);
    assertEquals("HTTP", health.getString("kind"));
    assertEquals(8080, health.getInteger("port"));
    assertEquals(5000L, health.getLong("intervalMs"));

    assertEquals("install", root.getJsonArray("bootstrap").getJsonObject(0).getString("name"));
  }

  @Test
  void nestedCollectionsAndEnvironmentAreAlwaysPresent() {
    // A daemon with no health-checks/env still emits them as []/{} so the round-trip into a nested
    // (non-normalizing) QitsConfig record is equals-exact.
    JsonObject daemon =
        json("version: 1\ndaemons:\n  - name: bare\n    start: run")
            .getJsonArray("services")
            .getJsonObject(0);
    assertTrue(daemon.getJsonObject("environment").isEmpty());
    assertTrue(daemon.getJsonArray("healthChecks").isEmpty());
    assertNull(daemon.getJsonObject("webView"), "an absent web-view is omitted (decodes to null)");
  }

  @Test
  void legacyDaemonsKeyIsAcceptedAsServices() {
    // TEMPORARY back-compat: committed fixtures still use `daemons:`; the parser accepts it as the
    // `services:` key until the fixtures' submodule round-trip lands. Remove with the alias.
    JsonObject service =
        json("version: 1\ndaemons:\n  - name: dev\n    start: run")
            .getJsonArray("services")
            .getJsonObject(0);
    assertEquals("dev", service.getString("name"));
  }

  @Test
  void idIsParsedAndDefaultsToName() {
    // The explicit `id:` is parsed and emitted; an absent one defaults to the entry's name (the
    // stopgap that keeps id-less fixtures working until they declare real ids).
    JsonObject root =
        json(
            """
            version: 1
            actions:
              - id: build-backend
                name: build
                execute: mvn -B verify
            services:
              - name: dev
                start: run
            bootstrap:
              - name: install
                execute: ./install.sh
            """);
    JsonObject action = root.getJsonArray("actions").getJsonObject(0);
    assertEquals("build-backend", action.getString("id"), "explicit id round-trips");
    assertEquals("build", action.getString("name"));
    assertEquals(
        "dev",
        root.getJsonArray("services").getJsonObject(0).getString("id"),
        "id defaults to name");
    assertEquals(
        "install",
        root.getJsonArray("bootstrap").getJsonObject(0).getString("id"),
        "id defaults to name");

    DaemonQitsConfig parsed = ConfigParser.parse(FULL_CONFIG);
    assertEquals("build", parsed.actions().get(0).id());
    assertEquals("dev", parsed.services().get(0).id());
    assertEquals("install", parsed.bootstrap().get(0).id());
  }

  @Test
  void emptyContentIsTheEmptyConfig() {
    assertEquals(DaemonQitsConfig.EMPTY, ConfigParser.parse(""));
    assertEquals(DaemonQitsConfig.EMPTY, ConfigParser.parse(null));
    JsonObject empty = new JsonObject(ConfigJson.empty());
    assertNull(empty.getJsonObject("repository"));
    assertTrue(empty.getJsonArray("actions").isEmpty());
    assertTrue(empty.getJsonArray("services").isEmpty());
    assertTrue(empty.getJsonArray("bootstrap").isEmpty());
    assertTrue(empty.getJsonArray("frameworks").isEmpty());
  }

  @Test
  void structurallyInvalidThrows() {
    assertThrows(ConfigParser.ConfigException.class, () -> ConfigParser.parse("version: 2"));
    assertThrows(ConfigParser.ConfigException.class, () -> ConfigParser.parse("actions: notalist"));
    assertThrows(
        ConfigParser.ConfigException.class,
        () -> ConfigParser.parse("version: 1\nactions:\n  - {}"));
  }

  @Test
  void readerReturnsEmptyForAbsentFile() throws Exception {
    Path missing = Files.createTempDirectory("cfg").resolve("nope.yml");
    ConfigReader.State state = ConfigReader.read(missing.toFile());
    assertNull(state.warning());
    assertEquals(ConfigJson.empty(), state.configJson());
  }

  @Test
  void readerDegradesInvalidFileToEmptyPlusWarning() throws Exception {
    File file = Files.createTempFile("cfg", ".yml").toFile();
    Files.writeString(file.toPath(), "version: 2\n");
    ConfigReader.State state = ConfigReader.read(file);
    assertNotNull(state.warning());
    assertEquals(ConfigJson.empty(), state.configJson());
  }

  @Test
  void readerParsesAValidFile() throws Exception {
    File file = Files.createTempFile("cfg", ".yml").toFile();
    Files.writeString(file.toPath(), FULL_CONFIG);
    ConfigReader.State state = ConfigReader.read(file);
    assertNull(state.warning());
    assertEquals(
        "build",
        new JsonObject(state.configJson())
            .getJsonArray("actions")
            .getJsonObject(0)
            .getString("name"));
  }
}
