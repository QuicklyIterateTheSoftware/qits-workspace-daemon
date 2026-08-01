package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps {@code docs/openapi.yml} honest.
 *
 * <p>The document is hand-written, because there is nothing annotation-shaped here to generate it
 * from — no JAX-RS, no Jackson, no {@code smallrye-openapi}, all of which are absent by the same
 * native-image budget that shapes the rest of this repo. Hand-written also means nothing stops it
 * from drifting away from the {@code switch} ladder it describes, and a contract nobody enforces is
 * worse than none: a consumer written from a stale one fails at runtime, in the browser, far from
 * the change that broke it.
 *
 * <p>So the two mechanical properties are asserted here, and the interesting one — that each
 * documented <em>field name</em> is real — stays where it already lives, as literal-string
 * assertions in {@code WorkspaceApiTest}, {@code CommandsApiTest}, {@code AgentsApiTest}, {@code
 * ServicesApiTest} and {@code BootstrapApiTest}. This class deliberately does not try to check
 * those: it would have to reimplement the serializers to do it, and the API tests already exercise
 * the real ones over a real socket.
 *
 * <p>Uses SnakeYAML, which the module already carries for {@code ConfigParser}. No new dependency,
 * and none for a parser that would only ever run in a test.
 */
class OpenApiContractTest {

  /** Every route the dispatch ladder answers, in the templated spelling the document uses. */
  private static final Set<String> ROUTES =
      new LinkedHashSet<>(
          List.of(
              WorkspaceApi.FILES_PATH,
              WorkspaceApi.CONTENT_PATH,
              WorkspaceApi.DETECTION_PATH,
              WorkspaceApi.COMPONENT_MAP_PATH,
              WorkspaceApi.FAST_FORWARD_PATH,
              WorkspaceApi.UPDATE_FROM_PARENT_PATH,
              WorkspaceApi.COMMANDS_PATH,
              WorkspaceApi.COMMAND_ACTIONS_PATH,
              WorkspaceApi.COMMANDS_PATH + "/{commandId}",
              WorkspaceApi.COMMANDS_PATH + "/{commandId}/log",
              WorkspaceApi.COMMANDS_PATH + "/{commandId}/terminate",
              WorkspaceApi.AGENTS_PATH,
              WorkspaceApi.AGENTS_AVAILABLE_PATH,
              WorkspaceApi.AGENT_SESSIONS_PATH,
              WorkspaceApi.AGENT_PLUGINS_PATH,
              WorkspaceApi.AGENT_PLUGINS_PATH + "/{pluginId}/install",
              WorkspaceApi.PROMPT_REFINEMENTS_PATH,
              WorkspaceApi.SERVICES_PATH,
              WorkspaceApi.SERVICES_PATH + "/{name}/start",
              WorkspaceApi.SERVICES_PATH + "/{name}/signal",
              WorkspaceApi.BOOTSTRAP_COMMANDS_PATH,
              WorkspaceApi.BOOTSTRAP_COMMANDS_PATH + "/run",
              WorkspaceApi.BOOTSTRAP_COMMANDS_PATH + "/{name}/run"));

  @Test
  void everyRouteTheDispatchLadderAnswersIsDocumented() throws Exception {
    Set<String> documented = document().keySet();

    List<String> undocumented = new ArrayList<>(ROUTES);
    undocumented.removeAll(documented);
    assertTrue(undocumented.isEmpty(), "routes with no entry in docs/openapi.yml: " + undocumented);
  }

  @Test
  void theDocumentInventsNoRouteTheDaemonDoesNotServe() throws Exception {
    List<String> invented = new ArrayList<>(document().keySet());
    invented.removeAll(ROUTES);
    assertTrue(invented.isEmpty(), "documented but not in the dispatch ladder: " + invented);
  }

  @Test
  void bothSocketsAreDocumentedAtThePathsTheyAreServedOn() throws Exception {
    Map<String, Object> sockets = channels();

    // Compared against the prefixes the handshake actually matches on, so a rename of either is
    // caught here rather than by a client that silently never connects.
    assertEquals(CommandSockets.TERMINAL_PREFIX + "{commandId}", pathOf(sockets, "terminal"));
    assertEquals(CommandSockets.CHAT_PREFIX + "{commandId}", pathOf(sockets, "chat"));
  }

  @Test
  void everyInternalReferenceResolves() throws Exception {
    Map<String, Object> root = load();
    Set<String> refs = new TreeSet<>();
    Matcher matcher = Pattern.compile("#/([A-Za-z0-9/]+)").matcher(Files.readString(location()));
    while (matcher.find()) {
      refs.add(matcher.group(1));
    }
    assertFalse(refs.isEmpty(), "the document should use component references");

    List<String> dangling = new ArrayList<>();
    for (String ref : refs) {
      Object node = root;
      for (String segment : ref.split("/")) {
        if (node instanceof Map<?, ?> map && map.containsKey(segment)) {
          node = map.get(segment);
        } else {
          dangling.add(ref);
          break;
        }
      }
    }
    assertTrue(dangling.isEmpty(), "dangling $refs: " + dangling);
  }

  // --- helpers ---------------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Map<String, Object> document() throws Exception {
    return (Map<String, Object>) load().get("paths");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> channels() throws Exception {
    Map<String, Object> sockets = (Map<String, Object>) load().get("x-websockets");
    return (Map<String, Object>) sockets.get("channels");
  }

  @SuppressWarnings("unchecked")
  private static String pathOf(Map<String, Object> channels, String name) {
    return (String) ((Map<String, Object>) channels.get(name)).get("path");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> load() throws Exception {
    return new Yaml().load(Files.readString(location()));
  }

  /**
   * Surefire runs with the module directory as its working directory, so the document — which
   * belongs to the repository rather than to this module — is one level up. The fallback covers
   * running the test from the repository root.
   */
  private static Path location() {
    Path fromModule = Path.of("..", "docs", "openapi.yml");
    return Files.isRegularFile(fromModule) ? fromModule : Path.of("docs", "openapi.yml");
  }
}
