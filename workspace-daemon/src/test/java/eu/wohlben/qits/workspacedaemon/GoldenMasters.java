package eu.wohlben.qits.workspacedaemon;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.core.support.Json;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A provider's recorded answers, as this daemon's consumer pacts read them</b> (ticket
 * qits-1149). Each provider publishes its tree as {@code eu.wohlben.qits:<application>-golden-masters}
 * at the classpath root {@code golden-masters/}; the index is chosen by its {@code provider} field.
 *
 * <p>The slim form of qits-workspaces-service's class of the same name, on Vert.x JSON: this daemon
 * reads one flat answer ({@code access_token}), so the response body is the recording cut down to
 * the consumed top-level fields, each type-matched. An interaction that reads nothing carries the
 * status alone.
 */
final class GoldenMasters {

  /** The consumer, as the pact names it: the repository name. */
  static final String CONSUMER = "qits-workspace-daemon";

  /** Where every provider's jar puts its tree on the classpath. */
  static final String ROOT = "golden-masters/";

  /** One provider: its repository name (the pact's provider) and its application name (the index's). */
  record Provider(String repository, String application) {}

  static final Provider IDP = new Provider("qits-idp-service", "qits-idp");

  private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  private GoldenMasters() {}

  /** What made this daemon make the call: the {@code qits-trigger} reference. */
  record Trigger(String kind, String key, String value) {
    static Trigger schedule(String schedule) {
      return new Trigger("schedule", "schedule", schedule);
    }

    Map<String, String> reference() {
      Map<String, String> ref = new LinkedHashMap<>();
      ref.put("kind", kind);
      ref.put("app", CONSUMER);
      ref.put(key, value);
      return ref;
    }
  }

  /** The provider state's frozen example params. */
  static Map<String, String> params(Provider provider, String state) {
    Map<String, String> params = new LinkedHashMap<>();
    JsonObject recorded = state(provider, state).getJsonObject("params", new JsonObject());
    recorded.forEach(e -> params.put(e.getKey(), String.valueOf(e.getValue())));
    return params;
  }

  /** The index entry for one (state, operation). */
  static JsonObject operation(Provider provider, String state, String operationId) {
    for (Object op : state(provider, state).getJsonArray("operations")) {
      JsonObject operation = (JsonObject) op;
      if (operationId.equals(operation.getString("operationId"))) {
        return operation;
      }
    }
    throw new IllegalArgumentException(
        provider.application() + "'s golden masters record no operation " + operationId
            + " in state '" + state + "'");
  }

  /**
   * Add the V4 HTTP interaction for one recorded (state, operation): {@code given(state, params)},
   * the route from the index with each {@code {param}} a provider-state expression, the request
   * body this daemon sends, the recorded status, and the recorded body cut to {@code consumes}.
   */
  static PactBuilder interaction(
      PactBuilder builder,
      Provider provider,
      String state,
      String operationId,
      Trigger trigger,
      String requestBody,
      String requestContentType,
      List<String> consumes) {
    Objects.requireNonNull(trigger, "trigger: every interaction names the entry point that makes it");
    JsonObject op = operation(provider, state, operationId);
    Map<String, String> params = params(provider, state);
    String path = op.getString("path");
    DslPart body = consumes.isEmpty() ? null : responseBody(provider, state, op, consumes);
    Map<String, Object> references = new LinkedHashMap<>();
    references.put("qits-call", Map.of("app", provider.repository(), "operationId", operationId));
    references.put("qits-trigger", trigger.reference());
    return builder.expectsToReceiveHttpInteraction(
        trigger.value() + ": " + operationId,
        http -> {
          http.state(state, new LinkedHashMap<String, Object>(params));
          http.withRequest(
              request -> {
                request
                    .method(op.getString("method"))
                    .path(
                        Matchers.fromProviderState(
                            substitute(path, name -> "${" + name + "}", params),
                            substitute(path, params::get, params)));
                JsonObject query = op.getJsonObject("query");
                if (query != null) {
                  query.forEach(e -> request.queryParameter(e.getKey(), String.valueOf(e.getValue())));
                }
                return requestBody == null ? request : request.body(requestBody, requestContentType);
              });
          http.willRespondWith(
              response -> {
                response.status(op.getInteger("status"));
                return body == null
                    ? response
                    : response
                        .header("Content-Type", Matchers.regexp("application/json.*", "application/json"))
                        .body(body);
              });
          http.getInteraction().getComments().put("references", Json.toJson(references));
          return http;
        });
  }

  /** The recording's top-level {@code consumes} fields, type-matched. */
  private static DslPart responseBody(
      Provider provider, String state, JsonObject op, List<String> consumes) {
    JsonObject recorded = new JsonObject(read(provider, op.getString("file")));
    PactDslJsonBody body = new PactDslJsonBody();
    for (String path : consumes) {
      if (!path.matches("\\$\\.[A-Za-z0-9_]+")) {
        throw new IllegalArgumentException("only a top-level field is read here: " + path);
      }
      String field = path.substring(2);
      Object value = recorded.getValue(field);
      if (value instanceof String text) {
        body.stringType(field, text);
      } else if (value instanceof Number number) {
        body.numberType(field, number);
      } else if (value instanceof Boolean flag) {
        body.booleanType(field, flag);
      } else {
        throw new IllegalStateException(
            "golden master " + state + "/" + op.getString("operationId") + " holds no scalar at "
                + path);
      }
    }
    return body;
  }

  private static String substitute(
      String template, java.util.function.Function<String, String> value, Map<String, String> params) {
    Matcher m = PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      if (!params.containsKey(m.group(1))) {
        throw new IllegalStateException(
            template + " names {" + m.group(1) + "}, which the state's params do not hold");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value.apply(m.group(1))));
    }
    m.appendTail(out);
    return out.toString();
  }

  // --- reading the jar --------------------------------------------------------------------------

  private static JsonObject state(Provider provider, String state) {
    for (Object node : index(provider).getJsonArray("states")) {
      JsonObject candidate = (JsonObject) node;
      if (state.equals(candidate.getString("name"))) {
        return candidate;
      }
    }
    throw new IllegalArgumentException(
        provider.application() + "'s golden masters record no state '" + state + "'");
  }

  private static JsonObject index(Provider provider) {
    List<String> seen = new ArrayList<>();
    for (URL url : resources(ROOT + "index.json")) {
      JsonObject index = new JsonObject(read(url));
      String owner = index.getString("provider");
      seen.add(owner);
      if (provider.application().equals(owner)) {
        index.put("__location", url.toString());
        return index;
      }
    }
    throw new IllegalStateException(
        "no golden-masters/index.json of " + provider.application() + " on the test classpath (found "
            + seen + ") — is eu.wohlben.qits:" + provider.application()
            + "-golden-masters a test dependency of this module?");
  }

  @SuppressWarnings("deprecation") // new URL(context, spec) is the one resolver jar: URLs have
  private static String read(Provider provider, String file) {
    try {
      return read(new URL(new URL(index(provider).getString("__location")), file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<URL> resources(String name) {
    List<URL> out = new ArrayList<>();
    try {
      Enumeration<URL> found = GoldenMasters.class.getClassLoader().getResources(name);
      while (found.hasMoreElements()) {
        out.add(found.nextElement());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  private static String read(URL url) {
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + url, e);
    }
  }
}
