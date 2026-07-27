package eu.wohlben.qits.workspacedaemon.agents.json;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A read-only, never-null view over {@code io.vertx.core.json}, shaped like Jackson's {@code
 * JsonNode}.
 *
 * <p>It exists for one reason. The three files this module ports from the monolith — {@code
 * AcpChatProtocol}, {@code KimiEventNormalizer} and {@code AgentTranscriptService} — read harness
 * JSON through roughly forty {@code node.path("a").path("b")} chains, and they are correct only
 * because Jackson's {@code path()} returns a <em>missing node</em> rather than null. A chain over an
 * absent key keeps chaining, an iteration over a missing array runs zero times, and {@code
 * asText(default)} absorbs the absence at the end. The direct translation —
 * {@code getJsonObject("a").getJsonArray("b")} — throws {@link NullPointerException} at every one of
 * those sites, and the ones it does not throw at it silently changes: an absent key becomes {@code
 * null} where the original produced {@code ""}.
 *
 * <p>So rather than translate forty call sites as forty judgement calls, the semantics are
 * reproduced once, here, and each site ports character-for-character. Nothing in this class is
 * agent-specific; it is a compatibility layer and lives in its own package to say so.
 *
 * <p><strong>Read-only on purpose.</strong> Construction stays plain {@link JsonObject} /
 * {@link JsonArray}, which is what {@code CommandJson}, {@code WorkspaceJson} and {@code
 * StreamJsonChatProtocol} already do — one idiom for output, and no second way to build a body.
 *
 * <p><strong>One deliberate divergence from Jackson.</strong> For a key that is <em>present with a
 * JSON null value</em>, Jackson's {@code asText(default)} yields the four-character string {@code
 * "null"}; this yields the default. Jackson's behaviour is a trap — it is how a literal {@code
 * "null"} ends up rendered as a subagent description — and no call site being ported wants it.
 * {@code nullIsAbsentForText} pins it.
 */
public final class Json implements Iterable<Json> {

  /** The absent node: what {@link #path} answers for a key that is not there. */
  private static final Json MISSING = new Json(null, true);

  /** A {@link JsonObject}, {@link JsonArray}, String, Number, Boolean, or null. */
  private final Object value;

  private final boolean missing;

  private Json(Object value, boolean missing) {
    this.value = value;
    this.missing = missing;
  }

  /** The absent node — {@link #isMissing()} is true and every accessor yields its default. */
  public static Json missing() {
    return MISSING;
  }

  /** Wraps an already-decoded value. */
  public static Json of(Object value) {
    return value instanceof Json json ? json : new Json(value, false);
  }

  /**
   * Parses one JSON document. <strong>Never throws.</strong> Blank input, malformed input and input
   * that is not JSON at all all yield {@link #missing()} — which is what lets {@code
   * KimiEventNormalizer.onWireLine(String)} and {@code AgentTranscriptService.parseLine} drop their
   * try/catch and simply let missingness propagate.
   */
  public static Json parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return MISSING;
    }
    try {
      return of(io.vertx.core.json.Json.decodeValue(raw.trim()));
    } catch (DecodeException | ClassCastException e) {
      return MISSING;
    }
  }

  // --- navigation --------------------------------------------------------------------------------

  /**
   * The value at {@code field}, or {@link #missing()} when this is not an object or has no such key.
   * Chains: {@code node.path("params").path("update")} is safe at every step.
   */
  public Json path(String field) {
    if (value instanceof JsonObject object && object.containsKey(field)) {
      return of(object.getValue(field));
    }
    return MISSING;
  }

  /** The element at {@code index}, or {@link #missing()} when out of range or not an array. */
  public Json path(int index) {
    if (value instanceof JsonArray array && index >= 0 && index < array.size()) {
      return of(array.getValue(index));
    }
    return MISSING;
  }

  /** Whether this is an object carrying {@code field}, present-but-null included. */
  public boolean has(String field) {
    return value instanceof JsonObject object && object.containsKey(field);
  }

  /**
   * Array elements, or nothing for every other node. Missing nodes iterate zero times, so {@code
   * for (Json block : node.path("content"))} over an absent {@code content} is a no-op rather than a
   * {@link NullPointerException} — the property the ported loops depend on.
   */
  @Override
  public Iterator<Json> iterator() {
    if (!(value instanceof JsonArray array)) {
      return Collections.emptyIterator();
    }
    Iterator<Object> delegate = array.iterator();
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return delegate.hasNext();
      }

      @Override
      public Json next() {
        return of(delegate.next());
      }
    };
  }

  /** This object's fields in insertion order, as views. Empty for every other node. */
  public Map<String, Json> fields() {
    if (!(value instanceof JsonObject object)) {
      return Map.of();
    }
    Map<String, Json> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : object) {
      out.put(entry.getKey(), of(entry.getValue()));
    }
    return out;
  }

  /** Element count for an array, field count for an object, {@code 0} otherwise. */
  public int size() {
    if (value instanceof JsonArray array) {
      return array.size();
    }
    return value instanceof JsonObject object ? object.size() : 0;
  }

  // --- interrogation -----------------------------------------------------------------------------

  public boolean isMissing() {
    return missing;
  }

  /** Present in the document, with a JSON null value. Never true for {@link #missing()}. */
  public boolean isNull() {
    return !missing && value == null;
  }

  public boolean isObject() {
    return value instanceof JsonObject;
  }

  public boolean isArray() {
    return value instanceof JsonArray;
  }

  /** A JSON string — Jackson's {@code isTextual}, and false for numbers and booleans. */
  public boolean isTextual() {
    return value instanceof String;
  }

  // --- scalars -----------------------------------------------------------------------------------

  /** {@link #asText(String)} with {@code ""}, matching Jackson's no-arg {@code asText()}. */
  public String asText() {
    return asText("");
  }

  /**
   * This node as text, or {@code defaultValue} when it is missing, JSON null, or a container. A
   * number or boolean renders as its literal, as Jackson's does.
   */
  public String asText(String defaultValue) {
    if (value instanceof String text) {
      return text;
    }
    if (value instanceof Number || value instanceof Boolean) {
      return String.valueOf(value);
    }
    return defaultValue;
  }

  /** {@link #asInt(int)} with {@code 0}, matching Jackson's no-arg {@code asInt()}. */
  public int asInt() {
    return asInt(0);
  }

  /** {@link #asBoolean(boolean)} with {@code false}, matching Jackson's no-arg {@code asBoolean()}. */
  public boolean asBoolean() {
    return asBoolean(false);
  }

  public int asInt(int defaultValue) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    return defaultValue;
  }

  public long asLong(long defaultValue) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    return defaultValue;
  }

  public boolean asBoolean(boolean defaultValue) {
    if (value instanceof Boolean flag) {
      return flag;
    }
    if (value instanceof String text) {
      return Boolean.parseBoolean(text);
    }
    return defaultValue;
  }

  // --- escape hatches ----------------------------------------------------------------------------

  /**
   * The underlying decoded value — {@code null} for both missing and JSON null. Needed to echo an
   * opaque JSON-RPC {@code id} (which may be an int or a string) straight back onto the wire.
   */
  public Object raw() {
    return value;
  }

  /** This node re-encoded, or {@code "null"} when missing — Jackson's {@code toString()}. */
  public String encode() {
    if (value instanceof JsonObject object) {
      return object.encode();
    }
    if (value instanceof JsonArray array) {
      return array.encode();
    }
    return io.vertx.core.json.Json.encode(value);
  }

  @Override
  public String toString() {
    return encode();
  }
}
