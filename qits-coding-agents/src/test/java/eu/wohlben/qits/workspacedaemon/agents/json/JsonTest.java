package eu.wohlben.qits.workspacedaemon.agents.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The contract every consumer of {@link Json} inherits.
 *
 * <p>This is deliberately exhaustive rather than illustrative. {@code AcpChatProtocol}, {@code
 * KimiEventNormalizer} and {@code AgentTranscriptService} were written against Jackson and ported
 * onto this class by substitution, so a divergence here does not surface as a compile error or an
 * exception — it surfaces as a blank assistant bubble, a dropped tool result, or a transcript that
 * looks almost right. The five shapes below (present, absent, JSON null, wrong type, and nested
 * under something absent) are the ones those files actually hit.
 */
class JsonTest {

  @Nested
  class Navigation {

    @Test
    void aChainThroughAnAbsentKeyKeepsChaining() {
      // AcpChatProtocol.onIncoming does exactly this on a notification with no params.
      Json node = Json.parse("{\"method\":\"session/update\"}");

      Json update = node.path("params").path("update");

      assertTrue(update.isMissing(), "an absent intermediate must not end the chain");
      assertEquals("", update.path("sessionUpdate").asText(""), "and the chain stays usable");
    }

    @Test
    void iteratingAMissingArrayRunsZeroTimes() {
      // AgentTranscriptService walks node.path("message").path("content") on lines that have no
      // message object at all; under Jackson that loop simply does not execute.
      Json node = Json.parse("{\"type\":\"summary\"}");

      List<Json> seen = new ArrayList<>();
      for (Json block : node.path("message").path("content")) {
        seen.add(block);
      }

      assertEquals(List.of(), seen);
    }

    @Test
    void arrayElementsArePositional() {
      Json options = Json.parse("{\"options\":[{\"optionId\":\"a\"},{\"optionId\":\"b\"}]}");

      assertEquals("a", options.path("options").path(0).path("optionId").asText(null));
      assertEquals("b", options.path("options").path(1).path("optionId").asText(null));
      assertTrue(options.path("options").path(2).isMissing(), "out of range is missing, not a throw");
      assertTrue(options.path("options").path(-1).isMissing());
    }

    @Test
    void hasDistinguishesAbsentFromPresentButNull() {
      // KimiEventNormalizer.toolContentText branches on has("content") and must not treat an
      // explicit null as a wrapper.
      Json node = Json.parse("{\"present\":1,\"explicitNull\":null}");

      assertTrue(node.has("present"));
      assertTrue(node.has("explicitNull"), "present with a null value is still present");
      assertFalse(node.has("absent"));
    }

    @Test
    void fieldsEnumeratesInInsertionOrder() {
      // AgentPluginService.parseEnabledPlugins walks enabledPlugins' fields.
      Json node = Json.parse("{\"enabledPlugins\":{\"b@m\":true,\"a@m\":false}}");

      assertEquals(List.of("b@m", "a@m"), List.copyOf(node.path("enabledPlugins").fields().keySet()));
      assertTrue(node.path("enabledPlugins").path("b@m").asBoolean(false));
      assertFalse(node.path("enabledPlugins").path("a@m").asBoolean(true));
      assertEquals(0, node.path("nope").fields().size(), "a missing node has no fields");
    }

    @Test
    void sizeCountsElementsAndFields() {
      assertEquals(2, Json.parse("[1,2]").size());
      assertEquals(1, Json.parse("{\"a\":1}").size());
      assertEquals(0, Json.parse("\"text\"").size());
      assertEquals(0, Json.missing().size());
    }
  }

  @Nested
  class Text {

    @Test
    void anAbsentKeyYieldsTheDefault() {
      Json node = Json.parse("{}");

      assertEquals("", node.path("text").asText(""));
      assertEquals("fallback", node.path("text").asText("fallback"));
      assertEquals(null, node.path("text").asText(null));
    }

    @Test
    void nullIsAbsentForText() {
      // THE ONE DELIBERATE DIVERGENCE FROM JACKSON. Jackson's NullNode.asText(default) returns the
      // four-character string "null"; a sidechain meta file with "agentType": null would then be
      // labelled the literal word null in the UI. Nothing being ported wants that, so a JSON null
      // reads as absent.
      Json node = Json.parse("{\"agentType\":null}");

      assertEquals(null, node.path("agentType").asText(null));
      assertEquals("", node.path("agentType").asText(""));
      assertTrue(node.path("agentType").isNull(), "but it is still distinguishable from missing");
      assertFalse(node.path("agentType").isMissing());
    }

    @Test
    void aContainerYieldsTheDefaultRatherThanItsEncoding() {
      Json node = Json.parse("{\"content\":[{\"type\":\"text\"}],\"message\":{\"a\":1}}");

      assertEquals("", node.path("content").asText(""));
      assertEquals("", node.path("message").asText(""));
    }

    @Test
    void numbersAndBooleansRenderAsTheirLiteral() {
      Json node = Json.parse("{\"n\":42,\"b\":true}");

      assertEquals("42", node.path("n").asText(""));
      assertEquals("true", node.path("b").asText(""));
    }

    @Test
    void isTextualIsTrueOnlyForStrings() {
      Json node = Json.parse("{\"s\":\"x\",\"n\":1,\"b\":true,\"a\":[],\"o\":{}}");

      assertTrue(node.path("s").isTextual());
      assertFalse(node.path("n").isTextual());
      assertFalse(node.path("b").isTextual());
      assertFalse(node.path("a").isTextual());
      assertFalse(node.path("o").isTextual());
      assertFalse(node.path("absent").isTextual());
    }

    @Test
    void theNoArgFormDefaultsToEmpty() {
      assertEquals("", Json.missing().asText());
      assertEquals("x", Json.parse("\"x\"").asText());
    }
  }

  @Nested
  class Scalars {

    @Test
    void intAndLongFallBackOnAnythingUnparseable() {
      Json node = Json.parse("{\"i\":7,\"s\":\"8\",\"bad\":\"eight\",\"o\":{}}");

      assertEquals(7, node.path("i").asInt(-1));
      assertEquals(8, node.path("s").asInt(-1), "a numeric string parses, as Jackson's does");
      assertEquals(-1, node.path("bad").asInt(-1));
      assertEquals(-1, node.path("o").asInt(-1));
      assertEquals(-1, node.path("absent").asInt(-1));
      assertEquals(9L, Json.parse("{\"l\":9}").path("l").asLong(-1L));
    }

    @Test
    void booleanFallsBackOnAbsence() {
      Json node = Json.parse("{\"t\":true,\"f\":false}");

      assertTrue(node.path("t").asBoolean(false));
      assertFalse(node.path("f").asBoolean(true));
      assertTrue(node.path("absent").asBoolean(true), "absent takes the default, either way");
      assertFalse(node.path("absent").asBoolean(false));
    }
  }

  @Nested
  class Parsing {

    @Test
    void unparseableInputIsMissingRatherThanAThrow() {
      // This is what lets the ported readers drop their try/catch: a truncated tail line, a blank
      // line, or a stray log write in the middle of a JSONL file all just read as absent.
      assertTrue(Json.parse("{not json").isMissing());
      assertTrue(Json.parse("").isMissing());
      assertTrue(Json.parse("   ").isMissing());
      assertTrue(Json.parse(null).isMissing());
      assertTrue(Json.parse("{\"unterminated\":").isMissing());
    }

    @Test
    void topLevelArraysAndScalarsDecode() {
      assertTrue(Json.parse("[1,2,3]").isArray());
      assertEquals(3, Json.parse("[1,2,3]").size());
      assertTrue(Json.parse("\"bare\"").isTextual());
      assertEquals("bare", Json.parse("\"bare\"").asText(""));
      assertEquals(5, Json.parse("5").asInt(-1));
      assertTrue(Json.parse("null").isNull(), "a literal null document is null, not missing");
    }

    @Test
    void leadingAndTrailingWhitespaceIsTolerated() {
      assertEquals("v", Json.parse("  {\"k\":\"v\"}\n").path("k").asText(""));
    }
  }

  @Nested
  class EscapeHatches {

    @Test
    void rawRoundTripsAnOpaqueJsonRpcId() {
      // AcpChatProtocol echoes the peer's id verbatim; it may be a number or a string and must come
      // back as the same JSON type.
      assertEquals(3, new JsonObject().put("id", Json.parse("{\"id\":3}").path("id").raw())
          .getValue("id"));
      assertEquals("abc", new JsonObject().put("id", Json.parse("{\"id\":\"abc\"}").path("id").raw())
          .getValue("id"));
    }

    @Test
    void encodeReproducesTheDocument() {
      assertEquals("{\"a\":1}", Json.parse("{\"a\":1}").encode());
      assertEquals("[1,2]", Json.parse("[1,2]").encode());
      assertEquals("null", Json.missing().encode());
    }

    @Test
    void ofIsIdempotent() {
      Json node = Json.parse("{\"a\":1}");

      assertSame(node, Json.of(node), "wrapping a view again must not double-wrap it");
    }
  }
}
