package eu.wohlben.qits.workspacedaemon.detection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Angular {@code @Component} metadata from a TypeScript source at regex level — no TS
 * parsing. The decorator-literal shape ({@code @Component({ selector: '...', ... }) export class
 * X}) is near-universal in practice; anything the regexes miss degrades to "no attribution", never
 * to wrong files.
 *
 * <p>Extraction is <em>windowed</em>, not brace-balanced: inline templates contain {@code {{ }}}
 * and {@code @if (...) {} } blocks that defeat a brace counter, so each decorator's window simply
 * runs to the next {@code @Component(} occurrence (or EOF) and the first match of each key wins.
 */
final class AngularComponentParser {

  /**
   * One alternative of a component's selector, pre-parsed so the frontend matcher stays dumb.
   * Either side may be null: {@code app-foo} is element-only, {@code [appFoo]} attribute-only,
   * {@code button[appFoo]} carries both.
   */
  record Selector(String element, String attribute) {}

  /**
   * One {@code @Component}-decorated class found in a file. {@code templateFile} is {@code null}
   * for inline templates; external template/style paths are resolved workspace-relative (against
   * the component file's directory).
   */
  record ParsedComponent(
      String className,
      String componentFile,
      String templateFile,
      List<String> styleFiles,
      List<Selector> selectors) {}

  private static final Pattern DECORATOR = Pattern.compile("@Component\\s*\\(\\s*\\{");
  private static final Pattern SELECTOR = keyPattern("selector");
  private static final Pattern TEMPLATE_URL = keyPattern("templateUrl");
  private static final Pattern STYLE_URL = keyPattern("styleUrl");
  private static final Pattern STYLE_URLS = Pattern.compile("styleUrls\\s*:\\s*\\[([^\\]]*)\\]");
  private static final Pattern QUOTED = Pattern.compile("['\"`]([^'\"`]+)['\"`]");
  private static final Pattern EXPORTED_CLASS =
      Pattern.compile("export\\s+(?:default\\s+)?(?:abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)");
  private static final Pattern SELECTOR_ALTERNATIVE =
      Pattern.compile("([A-Za-z][\\w-]*)?(?:\\[([^\\]]+)\\])?");

  private AngularComponentParser() {}

  /**
   * Parses every {@code @Component} occurrence in {@code source}. Occurrences without a following
   * {@code export class} in their window (decorators in comments/strings, unexported test hosts)
   * are skipped entirely. A component without a usable selector is still emitted with an empty
   * selector list — selector-less routed components stay matchable by class name.
   */
  static List<ParsedComponent> parse(String componentFile, String source) {
    List<Integer> starts = new ArrayList<>();
    Matcher decorator = DECORATOR.matcher(source);
    while (decorator.find()) {
      starts.add(decorator.start());
    }

    List<ParsedComponent> components = new ArrayList<>();
    for (int i = 0; i < starts.size(); i++) {
      int end = i + 1 < starts.size() ? starts.get(i + 1) : source.length();
      String window = source.substring(starts.get(i), end);

      Matcher exported = EXPORTED_CLASS.matcher(window);
      if (!exported.find()) {
        continue;
      }

      String templateFile =
          firstGroup(TEMPLATE_URL, window)
              .map(ref -> resolveRelative(componentFile, ref))
              .orElse(null);
      List<String> styleFiles =
          styleRefs(window).stream()
              .map(ref -> resolveRelative(componentFile, ref))
              .filter(resolved -> resolved != null)
              .toList();
      List<Selector> selectors =
          firstGroup(SELECTOR, window).map(AngularComponentParser::parseSelector).orElse(List.of());

      components.add(
          new ParsedComponent(
              exported.group(1), componentFile, templateFile, styleFiles, selectors));
    }
    return components;
  }

  private static Pattern keyPattern(String key) {
    return Pattern.compile(key + "\\s*:\\s*['\"`]([^'\"`]+)['\"`]");
  }

  private static java.util.Optional<String> firstGroup(Pattern pattern, String window) {
    Matcher matcher = pattern.matcher(window);
    return matcher.find() ? java.util.Optional.of(matcher.group(1)) : java.util.Optional.empty();
  }

  /** Both spellings: {@code styleUrl: '...'} (single) and {@code styleUrls: ['...', ...]}. */
  private static List<String> styleRefs(String window) {
    Matcher plural = STYLE_URLS.matcher(window);
    if (plural.find()) {
      List<String> refs = new ArrayList<>();
      Matcher quoted = QUOTED.matcher(plural.group(1));
      while (quoted.find()) {
        refs.add(quoted.group(1));
      }
      return refs;
    }
    return firstGroup(STYLE_URL, window).map(List::of).orElse(List.of());
  }

  /**
   * Splits a selector into its comma alternatives and pre-parses each. Alternatives outside the
   * element/attribute shapes ({@code .class}, {@code :not(...)}) are skipped — never guessed at.
   */
  private static List<Selector> parseSelector(String raw) {
    List<Selector> selectors = new ArrayList<>();
    for (String alternative : raw.split(",")) {
      Matcher matcher = SELECTOR_ALTERNATIVE.matcher(alternative.trim());
      if (!matcher.matches()) {
        continue;
      }
      String element = matcher.group(1);
      String attribute = matcher.group(2);
      if (element == null && attribute == null) {
        continue;
      }
      // an attribute selector may carry a value ([appFoo=bar]) — only the name matters for matching
      if (attribute != null) {
        attribute = attribute.split("=", 2)[0].trim();
      }
      selectors.add(new Selector(element == null ? null : element.toLowerCase(), attribute));
    }
    return selectors;
  }

  /**
   * Lexically resolves a {@code templateUrl}/{@code styleUrl} reference against the component
   * file's directory into a workspace-relative path. A reference that is absolute or escapes the
   * workspace root resolves to {@code null} (dropped — a miss must never point at wrong files).
   */
  private static String resolveRelative(String componentFile, String ref) {
    if (ref.isBlank() || ref.startsWith("/")) {
      return null;
    }
    int lastSlash = componentFile.lastIndexOf('/');
    String dir = lastSlash < 0 ? "" : componentFile.substring(0, lastSlash);
    Deque<String> parts = new ArrayDeque<>();
    for (String segment : (dir.isEmpty() ? ref : dir + "/" + ref).split("/")) {
      switch (segment) {
        case "", "." -> {}
        case ".." -> {
          if (parts.isEmpty()) {
            return null;
          }
          parts.removeLast();
        }
        default -> parts.addLast(segment);
      }
    }
    return parts.isEmpty() ? null : String.join("/", parts);
  }
}
