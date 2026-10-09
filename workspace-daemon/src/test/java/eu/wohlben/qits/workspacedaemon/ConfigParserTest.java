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

    assertEquals("install", root.getJsonArray("bootstrap").getJsonObject(0).getString("name"));
  }

  @Test
  void nestedEnvironmentIsAlwaysPresent() {
    // A step with no env still emits it as {} so the round-trip into a nested (non-normalizing)
    // QitsConfig record is equals-exact.
    JsonObject step =
        json("version: 1\nbootstrap:\n  - name: bare\n    execute: run")
            .getJsonArray("bootstrap")
            .getJsonObject(0);
    assertTrue(step.getJsonObject("environment").isEmpty());
  }

  @Test
  void aLegacyServicesOrDaemonsBlockIsIgnoredAndTheRestStillParses() {
    // Workspace services were removed (qits-947). A checkout that still declares them — under
    // `services:` or the older `daemons:`, well-formed or not — must keep its actions and bootstrap
    // chain: the block is not read at all, so it can neither warn nor degrade the config.
    for (String legacy :
        java.util.List.of(
            "services:\n  - name: dev\n    start: run\n    web-view:\n      port: 8080\n",
            "daemons:\n  - name: dev\n    start: run\n",
            "services: notalist\n",
            "daemons:\n  - start: no-name\n")) {
      String yaml =
          "version: 1\n"
              + legacy
              + "actions:\n  - name: build\n    execute: mvn -B verify\n"
              + "bootstrap:\n  - name: install\n    execute: ./install.sh\n";
      DaemonQitsConfig parsed = ConfigParser.parse(yaml);
      assertEquals("build", parsed.actions().get(0).name(), legacy);
      assertEquals("install", parsed.bootstrap().get(0).name(), legacy);

      JsonObject root = json(yaml);
      assertFalse(root.containsKey("services"), "no services key is emitted: " + legacy);
      assertFalse(root.containsKey("daemons"), legacy);
      assertEquals("build", root.getJsonArray("actions").getJsonObject(0).getString("name"));
      assertEquals("install", root.getJsonArray("bootstrap").getJsonObject(0).getString("name"));
    }
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
            bootstrap:
              - name: install
                execute: ./install.sh
            """);
    JsonObject action = root.getJsonArray("actions").getJsonObject(0);
    assertEquals("build-backend", action.getString("id"), "explicit id round-trips");
    assertEquals("build", action.getString("name"));
    assertEquals(
        "install",
        root.getJsonArray("bootstrap").getJsonObject(0).getString("id"),
        "id defaults to name");

    DaemonQitsConfig parsed = ConfigParser.parse(FULL_CONFIG);
    assertEquals("build", parsed.actions().get(0).id());
    assertEquals("install", parsed.bootstrap().get(0).id());
  }

  @Test
  void emptyContentIsTheEmptyConfig() {
    assertEquals(DaemonQitsConfig.EMPTY, ConfigParser.parse(""));
    assertEquals(DaemonQitsConfig.EMPTY, ConfigParser.parse(null));
    JsonObject empty = new JsonObject(ConfigJson.empty());
    assertNull(empty.getJsonObject("repository"));
    assertTrue(empty.getJsonArray("actions").isEmpty());
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
  void readerPrefersDefaultLocationOverLegacy() throws Exception {
    Path dir = Files.createTempDirectory("cfg");
    Path preferred = dir.resolve(".config/qits/repository.yml");
    Files.createDirectories(preferred.getParent());
    Files.writeString(preferred, FULL_CONFIG);
    Path legacy = dir.resolve(".qits-config.yml");
    Files.writeString(legacy, "version: 1\nactions:\n  - name: legacy-only\n    execute: ls\n");
    ConfigReader.State state = ConfigReader.read(preferred.toFile(), legacy.toFile());
    assertNull(state.warning());
    assertEquals(
        "build",
        new JsonObject(state.configJson())
            .getJsonArray("actions")
            .getJsonObject(0)
            .getString("name"));
  }

  @Test
  void readerFallsBackToLegacyLocation() throws Exception {
    Path dir = Files.createTempDirectory("cfg");
    Path legacy = dir.resolve(".qits-config.yml");
    Files.writeString(legacy, FULL_CONFIG);
    ConfigReader.State state =
        ConfigReader.read(dir.resolve(".config/qits/repository.yml").toFile(), legacy.toFile());
    assertNull(state.warning());
    assertEquals(
        "build",
        new JsonObject(state.configJson())
            .getJsonArray("actions")
            .getJsonObject(0)
            .getString("name"));
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
