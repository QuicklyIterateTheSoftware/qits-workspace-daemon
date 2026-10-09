package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.commands.ActionResolver;
import io.vertx.core.json.JsonObject;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SIGHUP reload's semantics, against a real file: an edit is visible to action resolution after
 * a reload, a broken edit keeps the last good config and surfaces only a warning, and fixing the
 * file clears it.
 */
class ConfigHolderTest {

  private static final String ONE_ACTION =
      """
      version: 1
      actions:
        - name: build
          execute: mvn -B verify
      bootstrap:
        - name: install
          execute: mvn -o -B -DskipTests install
      """;

  private static final String TWO_ACTIONS =
      """
      version: 1
      actions:
        - name: build
          execute: mvn -B verify
        - name: lint
          execute: npm run lint
      bootstrap:
        - name: install
          execute: mvn -o -B -DskipTests install
      """;

  @TempDir Path dir;

  private File file() {
    return dir.resolve("repository.yml").toFile();
  }

  private ConfigHolder holder() {
    return new ConfigHolder(() -> ConfigReader.read(file()));
  }

  private void write(String content) throws Exception {
    Files.writeString(file().toPath(), content);
  }

  @Test
  void anActionAddedToTheFileIsResolvableAfterAReload() throws Exception {
    write(ONE_ACTION);
    ConfigHolder holder = holder();
    holder.reload();
    ActionResolver resolver = new ConfigActionResolver(holder::config);
    assertTrue(resolver.resolve("build").isPresent());
    assertTrue(resolver.resolve("lint").isEmpty());

    write(TWO_ACTIONS);
    assertTrue(resolver.resolve("lint").isEmpty(), "nothing changes until the reload");
    holder.reload();

    assertEquals("npm run lint", resolver.resolve("lint").orElseThrow().executeScript());
    assertNull(holder.state().warning());
    assertEquals(
        2,
        new JsonObject(holder.state().configJson()).getJsonArray("actions").size(),
        "the config view moves with the config");
  }

  @Test
  void brokenYamlKeepsTheLastGoodConfigAndSetsAWarning() throws Exception {
    write(ONE_ACTION);
    ConfigHolder holder = holder();
    holder.reload();
    ConfigReader.State good = holder.state();

    write("version: 1\nactions: [unclosed\n");
    ConfigReader.State after = holder.reload();

    assertNotNull(after.warning());
    assertTrue(new ConfigActionResolver(holder::config).resolve("build").isPresent());
    assertEquals(good.config(), after.config());
    assertEquals(good.configJson(), after.configJson());
  }

  @Test
  void fixingTheFileClearsTheWarningAndReflectsTheNewContent() throws Exception {
    write(ONE_ACTION);
    ConfigHolder holder = holder();
    holder.reload();
    write("version: 2\n");
    assertNotNull(holder.reload().warning());

    write(TWO_ACTIONS);
    ConfigReader.State fixed = holder.reload();

    assertNull(fixed.warning());
    assertEquals(
        List.of("build", "lint"),
        new ConfigActionResolver(holder::config)
            .actions().stream().map(ActionResolver.ResolvedAction::id).toList());
  }

  @Test
  void aFileBrokenSinceBootYieldsTheEmptyConfigPlusTheWarning() throws Exception {
    // No good read held: the boot semantics, not a kept placeholder dressed up as a good config.
    write("version: 1\nactions: [unclosed\n");
    ConfigReader.State boot = holder().reload();

    assertNotNull(boot.warning());
    assertEquals(DaemonQitsConfig.EMPTY, boot.config());
  }

  @Test
  void anAbsentFileIsAGoodEmptyConfigThatABrokenEditDoesNotReplace() throws Exception {
    ConfigHolder holder = holder();
    assertNull(holder.reload().warning());

    write("version: 2\n");
    ConfigReader.State after = holder.reload();

    assertNotNull(after.warning());
    assertTrue(after.config().actions().isEmpty());
  }

  @Test
  void reloadCannotRunTheBootstrapChain() throws Exception {
    // Structural, and checked on the bytecode rather than on intent: BootstrapRunner is the only
    // thing that runs the chain, and ConfigHolder's class file does not reference it at all — no
    // call, no field, no import survives compilation without a constant-pool entry. The chain
    // stays declared: a reload changes what a later RunBootstrap would run, never runs it.
    String runner = BootstrapRunner.class.getName().replace('.', '/');
    try (InputStream in = ConfigHolder.class.getResourceAsStream("ConfigHolder.class")) {
      String bytecode = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
      assertTrue(bytecode.contains(ConfigReader.class.getName().replace('.', '/')), "sanity");
      assertFalse(bytecode.contains(runner), "ConfigHolder must not reach " + runner);
    }

    write(ONE_ACTION);
    ConfigHolder holder = holder();
    holder.reload();
    assertEquals("install", holder.config().bootstrap().get(0).name());
  }
}
