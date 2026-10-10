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

  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.A_CHAT_AGENT_RUNNING,
              "listCommands",
              "GET",
              PREFIX + "/commands",
              Map.of("status", "RUNNING"),
              null,
              200),
          new Interaction(
              ProviderStates.A_CHAT_AGENT_RUNNING,
              "deliverAgentTurn",
              "POST",
              PREFIX + "/agents/turn",
              Map.of(),
              new JsonObject().put("text", "Carry on."),
              200),
          new Interaction(
              ProviderStates.A_CHAT_AGENT_RUNNING,
              "setAgentBlocked",
              "POST",
              PREFIX + "/agents/blocked",
              Map.of(),
              new JsonObject().put("blocked", true),
              200),
          new Interaction(
              ProviderStates.A_CHAT_AGENT_RUNNING,
              "setAgentEntity",
              "POST",
              PREFIX + "/agents/entity",
              Map.of(),
              new JsonObject()
                  .put("title", "Fix the login")
                  .put("status", "IMPLEMENTING")
                  .put("blocked", false),
              200),
          new Interaction(
              ProviderStates.NO_AGENT_RUNNING,
              "launchAgent",
              "POST",
              PREFIX + "/agents",
              Map.of(),
              new JsonObject()
                  .put("scope", "REPOSITORY")
                  .put("surface", "ticket.dispatch")
                  .put("mode", "CHAT")
                  .put("initialContext", "Work the ticket.")
                  .put("deliverTaskPrompt", false),
              200));

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
                    "operations", new JsonArray(new ArrayList<>(indexOperations.get(slug).values())))));
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
      Freezer freezer = new Freezer();
      return new Recorded(freezer.freeze(new JsonObject(response.body())), params, freezer);
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
