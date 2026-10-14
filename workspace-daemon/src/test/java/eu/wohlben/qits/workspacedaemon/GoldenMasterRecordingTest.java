package eu.wohlben.qits.workspacedaemon;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * <b>Records this daemon's provider golden masters</b> (ticket qits-1149) — {@code golden-masters/}
 * at the repository root, published as {@code eu.wohlben.qits:qits-workspace-daemon-golden-masters}
 * and {@code @qits/workspace-daemon-golden-masters} for consumers to write their pacts against. The
 * format is qits-projects': one {@code golden-masters/<state-slug>/<operationId>.json} per (state,
 * operation), and {@code index.json} describing them.
 *
 * <p>Each operation records its route as {@code path}, its query apart as {@code query}, and the
 * request body it was called with as {@code body} — none for a call that sends none.
 *
 * <p>It compares by default and fails with a diff per differing file, including a committed file no
 * interaction records any more. {@code -Dgolden.update=true} (or {@code QITS_GOLDEN_UPDATE=true})
 * rewrites instead.
 */
@EnabledOnOs(OS.LINUX)
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  /** The provider as the index names it: the application, which here is the repository name. */
  static final String PROVIDER = "qits-workspace-daemon";

  /** Where every route is mounted: the base path, with the row id as a template param. */
  static final String PREFIX = "/workspaces/container/{workspaceRowId}";

  /**
   * One recorded call.
   *
   * @param query the query parameters, in order; empty for none
   * @param body the request body, or null for a call that sends none
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      Map<String, String> query,
      JsonObject body,
      int status) {}

  /** Where everything about one agent sits. */
  static final String AGENT = PREFIX + "/agent-worktrees/{agentId}";

  /**
   * Every recorded call: {@link #serviceCalls} and {@link #landingCalls}. The index keys a
   * recording by state and operation, so one operation is recorded at most once per state.
   */
  static final List<Interaction> INTERACTIONS =
      Stream.concat(serviceCalls().stream(), landingCalls().stream()).toList();

  /**
   * The calls qits-workspaces' {@code DaemonAgentClient} makes (qits-1152), with the bodies it
   * sends.
   */
  private static List<Interaction> serviceCalls() {
    return List.of(
        new Interaction(
            ProviderStates.SIGNED_IN,
            "startAgentWorktree",
            "POST",
            PREFIX + "/agent-worktrees",
            Map.of(),
            new JsonObject()
                .put("agentId", ProviderStates.AGENT_ID)
                .put("workId", ProviderStates.WORK_ID)
                .put("entityId", ProviderStates.ENTITY_ID)
                .put("wrapperBranch", ProviderStates.WRAPPER_BRANCH)
                .put("harness", "CLAUDE")
                .put("env", new JsonObject())
                .put("surface", "ticket.dispatch")
                .put("mode", "INTERACTIVE")
                .put("instruction", "Work the ticket."),
            200),
        new Interaction(
            ProviderStates.AGENT_RUNNING,
            "deliverAgentTurn",
            "POST",
            AGENT + "/turn",
            Map.of(),
            new JsonObject().put("text", "Carry on."),
            200),
        new Interaction(
            ProviderStates.AGENT_RUNNING,
            "setAgentEntity",
            "POST",
            AGENT + "/entity",
            Map.of(),
            new JsonObject()
                .put("title", "Fix the login")
                .put("status", "IMPLEMENTING")
                .put("blocked", false),
            200),
        new Interaction(
            ProviderStates.AGENT_RUNNING,
            "agentCleanupCheck",
            "GET",
            AGENT + "/cleanup-check",
            Map.of(),
            null,
            200),
        new Interaction(
            ProviderStates.AGENT_RUNNING,
            "yieldAgent",
            "POST",
            AGENT + "/yield",
            Map.of(),
            new JsonObject(),
            200),
        new Interaction(
            ProviderStates.AGENT_RUNNING,
            "removeAgentWorktree",
            "DELETE",
            AGENT,
            Map.of(),
            null,
            200));
  }

  /**
   * The calls the landing app makes through qits-workspaces' container proxy (epic qits-1230), as
   * the workspaces UI it inherits makes them: the path verbatim under {@code
   * /workspaces/container/{workspaceRowId}}, the query and body it sends. The proxy rewrites
   * nothing but the bearer, so these are the daemon's own doors.
   *
   * <ul>
   *   <li><b>Terminal</b> (qits-1240): the agent's worktree for its {@code commandId}, the command
   *       list to find it {@code RUNNING} as a {@code TERMINAL}, the harnesses' sign-in state. The
   *       socket itself ({@code WS /terminal/commands/{commandId}}) cannot be recorded here.
   *   <li><b>Chat</b> (qits-1241): the command list for a {@code CHAT} session, its transcript, the
   *       terminate that ends it, and the prompt refinement. The chat socket ({@code WS
   *       /chat/commands/{commandId}}) cannot be recorded; drafts and delivery are the service's.
   *   <li><b>Files</b> (qits-1242): the eager root listing, one lazy (ignored) level, a file's
   *       content and the missing-file refusal, detection with its test links. Filters, the ignore
   *       list and test links are drawn client-side from those answers.
   *   <li><b>Write actions</b> (qits-1243): the sign-in terminal, the plugin store and its install.
   * </ul>
   */
  private static List<Interaction> landingCalls() {
    return List.of(
        // --- terminal ------------------------------------------------------------------------
        new Interaction(
            ProviderStates.AGENT_RUNNING, "getAgentWorktree", "GET", AGENT, Map.of(), null, 200),
        new Interaction(
            ProviderStates.SIGNED_IN, "getAgentWorktree", "GET", AGENT, Map.of(), null, 404),
        new Interaction(
            ProviderStates.AGENT_RUNNING,
            "listCommands",
            "GET",
            PREFIX + "/commands",
            Map.of(),
            null,
            200),
        new Interaction(
            ProviderStates.SIGNED_IN,
            "availableAgents",
            "GET",
            PREFIX + "/agents/available",
            Map.of(),
            null,
            200),
        // --- chat ----------------------------------------------------------------------------
        new Interaction(
            ProviderStates.AGENT_CHATTING,
            "listCommands",
            "GET",
            PREFIX + "/commands",
            Map.of(),
            null,
            200),
        new Interaction(
            ProviderStates.AGENT_CHATTING,
            "terminateCommand",
            "POST",
            PREFIX + "/commands/{commandId}/terminate",
            Map.of(),
            new JsonObject(),
            200),
        new Interaction(
            ProviderStates.AGENT_WITH_TRANSCRIPT,
            "getCommandLog",
            "GET",
            PREFIX + "/commands/{commandId}/log",
            Map.of("channel", "TRANSCRIPT"),
            null,
            200),
        new Interaction(
            ProviderStates.SIGNED_IN,
            "refinePrompt",
            "POST",
            PREFIX + "/prompt-refinements",
            Map.of(),
            new JsonObject()
                .put("transcript", "uh so the login um it fails when the name has spaces")
                .put("preamble", "Fix the login"),
            200),
        // --- files ---------------------------------------------------------------------------
        new Interaction(
            ProviderStates.AGENT_WITH_FILES,
            "listFiles",
            "GET",
            AGENT + "/files",
            Map.of(),
            null,
            200),
        new Interaction(
            ProviderStates.AGENT_WITH_IGNORED_DIRECTORY,
            "listFiles",
            "GET",
            AGENT + "/files",
            Map.of("path", "node_modules"),
            null,
            200),
        new Interaction(
            ProviderStates.AGENT_WITH_FILES,
            "readFile",
            "GET",
            AGENT + "/files/content",
            Map.of("path", "src/main/java/app/Login.java"),
            null,
            200),
        new Interaction(
            ProviderStates.AGENT_WITH_IGNORED_DIRECTORY,
            "readFile",
            "GET",
            AGENT + "/files/content",
            Map.of("path", "node_modules/missing.js"),
            null,
            404),
        new Interaction(
            ProviderStates.AGENT_WITH_FILES,
            "detect",
            "GET",
            AGENT + "/detection",
            Map.of(),
            null,
            200),
        // --- write actions -------------------------------------------------------------------
        new Interaction(
            ProviderStates.SIGNED_OUT,
            "availableAgents",
            "GET",
            PREFIX + "/agents/available",
            Map.of(),
            null,
            200),
        new Interaction(
            ProviderStates.SIGNED_OUT,
            "openSignInTerminal",
            "POST",
            PREFIX + "/agents/sign-in",
            Map.of(),
            new JsonObject().put("agentType", "CLAUDE"),
            200),
        new Interaction(
            ProviderStates.PLUGINS_INSTALLED,
            "listPlugins",
            "GET",
            PREFIX + "/agent-plugins",
            Map.of(),
            null,
            200),
        new Interaction(
            ProviderStates.PLUGINS_INSTALLED,
            "installPlugin",
            "POST",
            PREFIX + "/agent-plugins/pyright-lsp/install",
            Map.of(),
            new JsonObject(),
            200));
  }

  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)}");

  /** The state's name as a directory: lower case, runs of anything else as one dash. */
  static String slug(String state) {
    return state
        .toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-|-$", "");
  }

  @Test
  void goldenMastersMatchTheProvider() throws Exception {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();
    Map<String, JsonObject> indexStates = new TreeMap<>();
    Map<String, Map<String, JsonObject>> indexOperations = new TreeMap<>();

    for (Interaction interaction : INTERACTIONS) {
      Recorded recorded = record(interaction);
      String slug = slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      indexStates.computeIfAbsent(
          slug,
          k -> {
            JsonObject params = new JsonObject();
            recorded.params().forEach(params::put);
            return new JsonObject()
                .put("name", interaction.state())
                .put("slug", slug)
                .put("params", params)
                .put("dependsOn", new JsonArray());
          });

      JsonObject operation =
          new JsonObject()
              .put("operationId", interaction.operationId())
              .put("method", interaction.method())
              .put("path", interaction.path());
      if (!interaction.query().isEmpty()) {
        JsonObject query = new JsonObject();
        interaction.query().forEach(query::put);
        operation.put("query", query);
      }
      if (interaction.body() != null) {
        operation.put("body", interaction.body().copy());
      }
      operation
          .put("status", interaction.status())
          .put("file", file)
          .put(
              "frozen",
              new JsonObject()
                  .put("ids", new JsonArray(recorded.freezer().idPaths()))
                  .put("instants", new JsonArray(recorded.freezer().instantPaths()))
                  .put("strings", new JsonArray(recorded.freezer().stringPaths()))
                  .put("numbers", new JsonArray())
                  .putNull("listFilteredTo"));
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenFiles.render(recorded.body()), update, failures);
    }

    JsonArray states = new JsonArray();
    indexStates.forEach(
        (slug, state) ->
            states.add(
                state.put(
                    "operations",
                    new JsonArray(new ArrayList<>(indexOperations.get(slug).values())))));
    JsonObject index =
        new JsonObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("provider", PROVIDER)
            .put("states", states);
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenFiles.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures)
      throws IOException {
    String failure = GoldenFiles.check(golden, actual, update);
    if (failure != null) {
      failures.add(failure);
    }
  }

  record Recorded(Object body, Map<String, String> params, Freezer freezer) {}

  private static Recorded record(Interaction interaction) throws Exception {
    try (ProviderStates daemon = ProviderStates.start()) {
      Map<String, String> params = daemon.setUp(interaction.state());
      HttpResponse<String> response = send(daemon.port(), interaction, params);
      Freezer freezer =
          new Freezer(
              Map.of(
                  daemon.volume().toString(),
                  ProviderStates.VOLUME,
                  daemon.claudeMount().toString(),
                  ProviderStates.CLAUDE_HOME,
                  ProviderStates.COMMIT,
                  ProviderStates.COMMIT));
      Map<String, String> frozenParams = freezer.freezeParams(params);
      if (response.statusCode() != interaction.status()) {
        throw new AssertionError(
            interaction.method()
                + " "
                + interaction.path()
                + " in state '"
                + interaction.state()
                + "' answered "
                + response.statusCode()
                + ": "
                + response.body());
      }
      return new Recorded(freezer.freeze(new JsonObject(response.body())), frozenParams, freezer);
    }
  }

  /** The call as qits-workspaces' proxy makes it: the full mounted path, the daemon token. */
  static HttpResponse<String> send(int port, Interaction interaction, Map<String, String> params)
      throws Exception {
    String query =
        interaction.query().entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    URI uri =
        URI.create(
            "http://127.0.0.1:"
                + port
                + expand(interaction.path(), params)
                + (query.isEmpty() ? "" : "?" + query));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + ProviderStates.TOKEN);
    if (interaction.body() == null) {
      request.method(interaction.method(), HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(
              interaction.method(), HttpRequest.BodyPublishers.ofString(interaction.body().encode()));
    }
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String expand(String template, Map<String, String> params) {
    Matcher m = TEMPLATE_PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            "Path " + template + " names {" + m.group(1) + "}, which the state does not return");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
