package eu.wohlben.qits.workspacedaemon;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/**
 * Golden files for the contract tests (ticket qits-1149): where the repository root is, how a file
 * is rendered, and the compare-or-rewrite switch.
 *
 * <p>The render is the shape {@code JSON.stringify(value, null, 2) + "\n"} produces — 2-space
 * indentation, {@code "key": value}, {@code []} and {@code {}} for empty containers, keys in the
 * order the object holds them — so a consumer in any language can re-render a file byte for byte.
 * Vert.x JSON rather than Jackson, which this module does not otherwise use.
 */
final class GoldenFiles {

  private GoldenFiles() {}

  /** {@code -Dgolden.update=true} or {@code QITS_GOLDEN_UPDATE=true}: rewrite instead of compare. */
  static boolean updating() {
    return Boolean.getBoolean("golden.update")
        || "true".equalsIgnoreCase(System.getenv("QITS_GOLDEN_UPDATE"));
  }

  /** The repository root: the nearest ancestor of the working directory holding {@code mvnw}. */
  static Path repositoryRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      if (Files.isRegularFile(dir.resolve("mvnw")) && Files.isDirectory(dir.resolve(".config"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
  }

  /**
   * Compares {@code golden} to {@code actual}, or rewrites it when updating. Answers a failure
   * message, or null when the file holds {@code actual}.
   */
  static String check(Path golden, String actual, boolean update) throws IOException {
    String committed = Files.isRegularFile(golden) ? Files.readString(golden) : null;
    if (actual.equals(committed)) {
      return null;
    }
    if (update) {
      Files.createDirectories(golden.getParent());
      Files.writeString(golden, actual);
      return null;
    }
    return golden
        + (committed == null ? " is missing" : " differs")
        + " — rerun with -Dgolden.update=true to rewrite it.\n--- committed\n"
        + committed
        + "\n+++ recorded\n"
        + actual;
  }

  static String render(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out, "");
    return out.append('\n').toString();
  }

  private static void write(Object value, StringBuilder out, String indent) {
    String inner = indent + "  ";
    if (value instanceof JsonObject object) {
      if (object.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      Iterator<Map.Entry<String, Object>> fields = object.iterator();
      while (fields.hasNext()) {
        Map.Entry<String, Object> field = fields.next();
        out.append(inner).append(quote(field.getKey())).append(": ");
        write(field.getValue(), out, inner);
        out.append(fields.hasNext() ? ",\n" : "\n");
      }
      out.append(indent).append('}');
    } else if (value instanceof JsonArray array) {
      if (array.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      for (int i = 0; i < array.size(); i++) {
        out.append(inner);
        write(array.getValue(i), out, inner);
        out.append(i + 1 < array.size() ? ",\n" : "\n");
      }
      out.append(indent).append(']');
    } else if (value instanceof String text) {
      out.append(quote(text));
    } else {
      out.append(value);
    }
  }

  private static String quote(String text) {
    // A one-element array encodes the string with Jackson's escaping; strip the brackets.
    String encoded = new JsonArray().add(text).encode();
    return encoded.substring(1, encoded.length() - 1);
  }
}
