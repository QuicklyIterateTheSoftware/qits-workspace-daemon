package eu.wohlben.qits.workspacedaemon;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Freezes one recorded answer</b> so every recording is byte-identical — qits-workspaces'
 * freezing rules, on Vert.x JSON:
 *
 * <ul>
 *   <li><b>Ids.</b> A UUID already in frozen form ({@code 00000000-0000-4000-8000-…}) stays as it
 *       is: the states use such ids on purpose. Any other UUID becomes {@code
 *       00000000-0000-4000-8000-0000000001NN}, numbered by first appearance. Listed in {@link
 *       #idPaths}.
 *   <li><b>Instants.</b> Every ISO-8601 instant becomes {@value #FROZEN_INSTANT}. Listed in {@link
 *       #instantPaths}.
 *   <li><b>Strings carrying an id</b> get it replaced in place. Listed in {@link #stringPaths}.
 *   <li><b>Strings carrying a temporary path</b> get it replaced by the path it stands for, as
 *       the state names it ({@link #Freezer(Map)}). Listed in {@link #stringPaths} too.
 *   <li><b>Commit hashes.</b> A string that is a whole 40-hex object name becomes {@code 000…01NN},
 *       numbered by first appearance — git's own hashes move with every run, because the fixture's
 *       {@code .gitmodules} names a temporary origin. A hash the state names as a literal (its
 *       fixed workspace commit) stays as the state names it. Listed in {@link #stringPaths}, not
 *       among the ids: a consumer reads an id as a UUID.
 * </ul>
 *
 * <p>A state's params go through {@link #freezeParams} first, so an id a state mints at runtime — a
 * command id — is frozen in the index to the same value the answer carries.
 */
final class Freezer {

  static final Pattern UUID =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  static final Pattern FROZEN_UUID = Pattern.compile("00000000-0000-4000-8000-[0-9a-f]{12}");

  static final Pattern INSTANT =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");

  static final String FROZEN_INSTANT = "2026-01-01T00:00:00Z";

  static final Pattern COMMIT_HASH = Pattern.compile("[0-9a-f]{40}");

  /** Literal text to replace, and what replaces it: a state's temporary paths. */
  private final Map<String, String> literals;

  private final Map<String, String> ids = new HashMap<>();
  private final Map<String, String> hashes = new HashMap<>();
  private final Set<String> idPaths = new LinkedHashSet<>();
  private final Set<String> instantPaths = new LinkedHashSet<>();
  private final Set<String> stringPaths = new LinkedHashSet<>();

  Freezer() {
    this(Map.of());
  }

  Freezer(Map<String, String> literals) {
    this.literals = Map.copyOf(literals);
  }

  Object freeze(Object value) {
    return freeze(value, "$");
  }

  /**
   * A state's params with their ids frozen, sharing this freezer's numbering, and recording no
   * path: a param is not part of the answer.
   */
  Map<String, String> freezeParams(Map<String, String> params) {
    Map<String, String> frozen = new TreeMap<>();
    params.forEach((name, value) -> frozen.put(name, freezeIds(value)));
    return frozen;
  }

  List<String> idPaths() {
    return new ArrayList<>(idPaths);
  }

  List<String> instantPaths() {
    return new ArrayList<>(instantPaths);
  }

  List<String> stringPaths() {
    return new ArrayList<>(stringPaths);
  }

  private Object freeze(Object value, String path) {
    if (value instanceof String text) {
      return freezeText(text, path);
    }
    if (value instanceof JsonArray array) {
      JsonArray out = new JsonArray();
      for (Object element : array) {
        out.add(freeze(element, path + "[*]"));
      }
      return out;
    }
    if (value instanceof JsonObject object) {
      JsonObject out = new JsonObject();
      for (Map.Entry<String, Object> field : object) {
        out.put(field.getKey(), freeze(field.getValue(), path + "." + field.getKey()));
      }
      return out;
    }
    return value;
  }

  private String freezeText(String value, String path) {
    if (INSTANT.matcher(value).matches()) {
      instantPaths.add(path);
      return FROZEN_INSTANT;
    }
    if (UUID.matcher(value).matches()) {
      idPaths.add(path);
      return freezeIds(value);
    }
    if (COMMIT_HASH.matcher(value).matches()) {
      String frozen =
          literals.containsKey(value)
              ? literals.get(value)
              : hashes.computeIfAbsent(value, k -> String.format("%040x", 0x100 + hashes.size()));
      if (!frozen.equals(value)) {
        stringPaths.add(path);
      }
      return frozen;
    }
    String result = freezeIds(value);
    for (Map.Entry<String, String> literal : literals.entrySet()) {
      result = result.replace(literal.getKey(), literal.getValue());
    }
    if (!result.equals(value)) {
      stringPaths.add(path);
    }
    return result;
  }

  private String freezeIds(String value) {
    Matcher m = UUID.matcher(value);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String original = m.group().toLowerCase(Locale.ROOT);
      String frozen =
          FROZEN_UUID.matcher(original).matches()
              ? original
              : ids.computeIfAbsent(
                  original, k -> String.format("00000000-0000-4000-8000-%012x", 0x100 + ids.size()));
      m.appendReplacement(out, Matcher.quoteReplacement(frozen));
    }
    m.appendTail(out);
    return out.toString();
  }
}
