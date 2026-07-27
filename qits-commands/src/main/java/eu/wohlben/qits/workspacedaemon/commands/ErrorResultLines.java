package eu.wohlben.qits.workspacedaemon.commands;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;

/**
 * The one stream-json shape the UI renders that the harness transcript does not contain: a failure
 * {@code result} event. {@link ChatSession} records exactly these to {@code OUTPUT}, and the
 * finished-chat replay merges exactly these back into the transcript — one predicate, both sides.
 */
final class ErrorResultLines {

  private ErrorResultLines() {}

  /** Mirrors the frontend error-bubble predicate: {@code is_error} or {@code subtype=="error"}. */
  static boolean isErrorResult(String line) {
    if (line == null || !line.contains("\"type\":\"result\"")) {
      return false; // substring pre-check keeps the hot path parse-free.
    }
    JsonObject node;
    try {
      node = new JsonObject(line);
    } catch (DecodeException | ClassCastException e) {
      // Not an object, or not JSON at all. Either way it is not the result event we are after.
      return false;
    }
    return "result".equals(node.getString("type"))
        && (Boolean.TRUE.equals(node.getBoolean("is_error", false))
            || "error".equals(node.getString("subtype")));
  }
}
