package eu.wohlben.qits.workspacedaemon.detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the language/framework "kinds" a workspace holds and links source files to their
 * test(s), as a <strong>pure, content-free pass over a path list</strong> — a faithful Java port of
 * the frontend's {@code detect-frameworks.ts}. It owns the framework registry (roots, membership
 * globs, source↔test rules); a thin orchestrator ({@link DetectionService}) fetches the path list
 * from the checkout, layers the label/runner content-peek on top, and ships the result.
 *
 * <p>Because it never touches the filesystem it is unit-testable in isolation and mirrors {@code
 * detect-frameworks.spec.ts} case-for-case. The gitignore glob translator and the CamelCase-prefix
 * owner resolution are subtle; they are ported verbatim (see the helpers) rather than approximated
 * with {@code java.nio.file.PathMatcher}, whose glob semantics differ.
 *
 * <p>Carried over from the host's {@code FrameworkDetectionService} with the CDI bean turned into
 * static methods: the class was already stateless, and this module must link into a GraalVM native
 * image with no container to inject it.
 */
public final class FrameworkDetection {

  private FrameworkDetection() {}

  // ---- descriptor registry -------------------------------------------------------------------

  /**
   * A framework kind, expressed <strong>project-root-relative</strong>: {@link #detectRoots} yields
   * roots ({@code ""} = repo root); the remaining hooks take/return root-relative paths and globs.
   */
  public interface Descriptor {
    String id();

    /**
     * Base display label; a Java pom may refine it to "Java / Quarkus" (done in the orchestrator).
     */
    String label();

    List<String> detectRoots(List<String> paths);

    /** Root-relative, gitignore-complete globs of the files that belong to this framework. */
    List<String> frameworkGlobs();

    default boolean isTestPath(String rel) {
      return false;
    }

    /** Given a root-relative source path, candidate root-relative test globs. */
    default List<String> testCandidates(String rel) {
      return List.of();
    }

    /**
     * Given a root-relative test path, candidate root-relative source globs (best-effort inverse).
     */
    default List<String> sourceCandidates(String relTest) {
      return List.of();
    }
  }

  /** A detected project: one root of one framework kind. */
  public record DetectedProject(String root, Descriptor descriptor) {}

  /** A file in a viewer "linked group": the opened file plus its detected counterpart(s). */
  public record LinkedFile(String role, String path) {}

  private static final Pattern JAVA_SOURCE =
      Pattern.compile("^src/main/java/(.*/)?([^/]+)\\.java$");
  private static final Pattern JAVA_TEST =
      Pattern.compile("^src/test/java/(.*/)?([^/]+?)(Test|IT)\\.java$");

  private static final Descriptor JAVA_QUARKUS =
      new Descriptor() {
        @Override
        public String id() {
          return "java-quarkus";
        }

        @Override
        public String label() {
          return "Java / Maven";
        }

        @Override
        public List<String> detectRoots(List<String> paths) {
          return markerRoots(paths, "pom.xml");
        }

        @Override
        public List<String> frameworkGlobs() {
          return List.of("pom.xml", "**/*.java", "src/main/resources/**", "src/test/resources/**");
        }

        @Override
        public boolean isTestPath(String rel) {
          return JAVA_TEST.matcher(rel).matches();
        }

        @Override
        public List<String> testCandidates(String rel) {
          Matcher m = JAVA_SOURCE.matcher(rel);
          if (!m.matches()) {
            return List.of();
          }
          String pkg = m.group(1) == null ? "" : m.group(1);
          // Pre-filter on the source's 2-word camel prefix (the full name when it has fewer words),
          // so a scenario-named test (OtelProxyUnreachableTest for OtelProxyResource) is a
          // candidate; ownerSource then decides which matched test truly belongs here.
          List<String> words = camelWords(m.group(2));
          String prefix = String.join("", words.subList(0, Math.min(2, words.size())));
          return List.of(
              "src/test/java/" + pkg + prefix + "*Test.java",
              "src/test/java/" + pkg + prefix + "*IT.java");
        }

        @Override
        public List<String> sourceCandidates(String relTest) {
          Matcher m = JAVA_TEST.matcher(relTest);
          if (!m.matches()) {
            return List.of();
          }
          String pkg = m.group(1) == null ? "" : m.group(1);
          // The *Test/*IT qualifier makes the inverse ambiguous, so try every CamelCase prefix of
          // the base longest-first; the caller picks the first that owns it — the deepest such
          // class.
          List<String> candidates = new ArrayList<>();
          for (CamelPrefix cp : camelPrefixes(m.group(2))) {
            candidates.add("src/main/java/" + pkg + cp.prefix() + ".java");
            // A >=2-word prefix may be extended by a single owning source (OtelProxy[A-Z]* ->
            // OtelProxyResource); a 1-word prefix (Otel, Workspace) never fuzzy-claims a test.
            if (cp.words() >= 2) {
              candidates.add("src/main/java/" + pkg + cp.prefix() + "[A-Z]*.java");
            }
          }
          return candidates;
        }
      };

  private static final Descriptor TS_ANGULAR =
      new Descriptor() {
        @Override
        public String id() {
          return "ts-angular";
        }

        @Override
        public String label() {
          return "TypeScript / Angular";
        }

        @Override
        public List<String> detectRoots(List<String> paths) {
          return markerRoots(paths, "angular.json");
        }

        @Override
        public List<String> frameworkGlobs() {
          return List.of("package.json", "angular.json", "tsconfig*.json", "src/**", "public/**");
        }

        @Override
        public boolean isTestPath(String rel) {
          return rel.endsWith(".spec.ts");
        }

        @Override
        public List<String> testCandidates(String rel) {
          if (rel.endsWith(".spec.ts") || !rel.endsWith(".ts")) {
            return List.of();
          }
          return List.of(rel.substring(0, rel.length() - 3) + ".spec.ts");
        }

        @Override
        public List<String> sourceCandidates(String relTest) {
          if (!relTest.endsWith(".spec.ts")) {
            return List.of();
          }
          return List.of(relTest.substring(0, relTest.length() - ".spec.ts".length()) + ".ts");
        }
      };

  /**
   * Vite config file names that mark a candidate Lit root. The marker is structural (this class
   * stays content-free): a Vite config that is not an Angular workspace. Whether the project
   * actually depends on {@code lit} is a content peek layered in {@link DetectionService}, the way
   * the {@code java-quarkus} label refinement peeks the pom.
   */
  private static final List<String> VITE_MARKERS =
      List.of("vite.config.ts", "vite.config.mts", "vite.config.js", "vite.config.mjs");

  private static final Descriptor TS_LIT =
      new Descriptor() {
        @Override
        public String id() {
          return "ts-lit";
        }

        @Override
        public String label() {
          return "TypeScript / Lit";
        }

        @Override
        public List<String> detectRoots(List<String> paths) {
          Set<String> angular = new TreeSet<>(markerRoots(paths, "angular.json"));
          Set<String> roots = new TreeSet<>();
          for (String marker : VITE_MARKERS) {
            roots.addAll(markerRoots(paths, marker));
          }
          roots.removeAll(angular);
          return new ArrayList<>(roots);
        }

        @Override
        public List<String> frameworkGlobs() {
          return List.of(
              "package.json",
              "vite.config.*",
              "vitest.config.*",
              "tsconfig*.json",
              "index.html",
              "src/**",
              "public/**");
        }

        @Override
        public boolean isTestPath(String rel) {
          return rel.endsWith(".test.ts");
        }

        @Override
        public List<String> testCandidates(String rel) {
          // .test.ts is itself a .ts — exclude it (and Angular's .spec.ts) so a test never claims
          // a test as its counterpart.
          if (rel.endsWith(".test.ts") || rel.endsWith(".spec.ts") || !rel.endsWith(".ts")) {
            return List.of();
          }
          return List.of(rel.substring(0, rel.length() - 3) + ".test.ts");
        }

        @Override
        public List<String> sourceCandidates(String relTest) {
          if (!relTest.endsWith(".test.ts")) {
            return List.of();
          }
          return List.of(relTest.substring(0, relTest.length() - ".test.ts".length()) + ".ts");
        }
      };

  private static final Descriptor DOCS =
      new Descriptor() {
        @Override
        public String id() {
          return "docs";
        }

        @Override
        public String label() {
          return "Docs";
        }

        @Override
        public List<String> detectRoots(List<String> paths) {
          return docsRoots(paths);
        }

        @Override
        public List<String> frameworkGlobs() {
          return List.of("**");
        }
      };

  /** The framework kinds we ship, in registry order — adding another is a one-entry change. */
  public static final List<Descriptor> DESCRIPTORS =
      List.of(JAVA_QUARKUS, TS_ANGULAR, TS_LIT, DOCS);

  /**
   * The descriptor with the given {@link Descriptor#id()} ({@code java-quarkus}/{@code
   * ts-angular}/{@code ts-lit}/{@code docs}), or {@code null} for an unknown id. Used to resolve a
   * {@code frameworks[].kind} declared in the repository's qits config to a real descriptor.
   */
  public static Descriptor descriptorById(String id) {
    for (Descriptor descriptor : DESCRIPTORS) {
      if (descriptor.id().equals(id)) {
        return descriptor;
      }
    }
    return null;
  }

  // ---- detection -----------------------------------------------------------------------------

  /** Detect every project of every kind in the path list — a pure, content-free pass. */
  public static List<DetectedProject> detect(List<String> paths) {
    List<DetectedProject> projects = new ArrayList<>();
    for (Descriptor descriptor : DESCRIPTORS) {
      for (String root : descriptor.detectRoots(paths)) {
        projects.add(new DetectedProject(root, descriptor));
      }
    }
    return projects;
  }

  /**
   * The project that owns {@code path}: the <strong>deepest</strong> root that prefixes it
   * (most-specific wins), so a file under a nested project belongs to that project, not an
   * enclosing one. {@code null} when no project owns it.
   */
  public static DetectedProject owningProject(String path, List<DetectedProject> projects) {
    DetectedProject best = null;
    for (DetectedProject proj : projects) {
      if (rootPrefixes(proj.root(), path)
          && (best == null || proj.root().length() > best.root().length())) {
        best = proj;
      }
    }
    return best;
  }

  /**
   * The resolved member path set of a detected project: each of the descriptor's framework globs,
   * scoped by the root, matched against the tree. This is the "membership metadata" the client
   * whitelists (the ported {@code frameworkToRules} + client evaluation, resolved server-side).
   */
  public static List<String> memberPaths(DetectedProject project, List<String> allPaths) {
    String root = project.root();
    List<Pattern> res = new ArrayList<>();
    for (String glob : project.descriptor().frameworkGlobs()) {
      res.add(gitignoreGlobToRegExp(scoped(root, glob), true));
    }
    List<String> members = new ArrayList<>();
    for (String p : allPaths) {
      if (res.stream().anyMatch(re -> re.matcher(p).matches())) {
        members.add(p);
      }
    }
    return members;
  }

  /**
   * The existing <strong>test</strong> files of a source {@code path} (source→test). The single
   * primitive behind the viewer's test tabs and the tree's redundant-test hiding. A candidate test
   * is kept only when {@code path} is its most-specific owner, so {@code Foo.java} does not also
   * claim {@code FooBarTest} once {@code FooBar.java} exists.
   */
  public static List<String> linkedTestsOf(
      String path, List<DetectedProject> projects, List<String> allPaths) {
    DetectedProject proj = owningProject(path, projects);
    if (proj == null) {
      return List.of();
    }
    Descriptor descriptor = proj.descriptor();
    String root = proj.root();
    String rel = relOf(root, path);
    if (descriptor.isTestPath(rel)) {
      return List.of();
    }
    List<String> globs = descriptor.testCandidates(rel);
    if (globs.isEmpty()) {
      return List.of();
    }
    List<Pattern> res =
        globs.stream().map(g -> gitignoreGlobToRegExp(scoped(root, g), true)).toList();
    Set<String> existing = new LinkedHashSet<>(allPaths);
    return allPaths.stream()
        .filter(p -> !p.equals(path) && res.stream().anyMatch(re -> re.matcher(p).matches()))
        .filter(test -> Objects.equals(ownerSource(test, descriptor, root, existing), path))
        .sorted(BY_CLOSEST)
        .toList();
  }

  /**
   * The existing <strong>source</strong> file of a test {@code path} (test→source), or {@code []}.
   */
  public static List<String> linkedSourcesOf(
      String path, List<DetectedProject> projects, List<String> allPaths) {
    DetectedProject proj = owningProject(path, projects);
    if (proj == null) {
      return List.of();
    }
    String source =
        ownerSource(path, proj.descriptor(), proj.root(), new LinkedHashSet<>(allPaths));
    return source == null ? List.of() : List.of(source);
  }

  /**
   * The viewer's "linked group" for an opened file: the file itself plus its detected
   * counterpart(s). Resolves a test off its owning source so opening any member yields the
   * identical, fully-named strip. {@code []} when there is no counterpart.
   */
  public static List<LinkedFile> resolveLinkedGroup(
      String path, List<DetectedProject> projects, List<String> allPaths) {
    List<String> tests = linkedTestsOf(path, projects, allPaths);
    if (!tests.isEmpty()) {
      List<LinkedFile> group = new ArrayList<>();
      group.add(new LinkedFile("code", path));
      tests.forEach(p -> group.add(new LinkedFile("test", p)));
      return group;
    }
    List<String> sources = linkedSourcesOf(path, projects, allPaths);
    if (!sources.isEmpty()) {
      String source = sources.get(0);
      List<LinkedFile> group = new ArrayList<>();
      group.add(new LinkedFile("code", source));
      linkedTestsOf(source, projects, allPaths).forEach(p -> group.add(new LinkedFile("test", p)));
      return group;
    }
    return List.of();
  }

  // ---- private helpers (verbatim ports) ------------------------------------------------------

  /** Directory of every path whose basename is {@code marker} ({@code ""} = repo root). */
  private static List<String> markerRoots(List<String> paths, String marker) {
    Set<String> roots = new TreeSet<>();
    for (String p : paths) {
      if (basename(p).equals(marker)) {
        int slash = p.lastIndexOf('/');
        roots.add(slash == -1 ? "" : p.substring(0, slash));
      }
    }
    return new ArrayList<>(roots);
  }

  /** Every directory named {@code docs} that has at least one {@code *.md} beneath it. */
  private static List<String> docsRoots(List<String> paths) {
    Set<String> candidates = new TreeSet<>();
    for (String p : paths) {
      String[] parts = p.split("/");
      // A `docs` segment must be a directory, so never the final (basename) segment.
      for (int i = 0; i < parts.length - 1; i++) {
        if (parts[i].equals("docs")) {
          candidates.add(String.join("/", List.of(parts).subList(0, i + 1)));
        }
      }
    }
    List<String> roots = new ArrayList<>();
    for (String root : candidates) {
      boolean hasMd =
          paths.stream()
              .anyMatch(p -> p.startsWith(root + "/") && basename(p).toLowerCase().endsWith(".md"));
      if (hasMd) {
        roots.add(root);
      }
    }
    return roots;
  }

  /**
   * Whether {@code root} (a project root, {@code ""} = repo root) is a path-prefix of {@code path}.
   */
  private static boolean rootPrefixes(String root, String path) {
    return root.isEmpty() || path.equals(root) || path.startsWith(root + "/");
  }

  private static String relOf(String root, String path) {
    return root.isEmpty() ? path : path.substring(root.length() + 1);
  }

  private static String scoped(String root, String glob) {
    return root.isEmpty() ? glob : root + "/" + glob;
  }

  /**
   * Sort matched counterpart paths so the closest (shortest basename, then lexical) comes first.
   */
  private static final Comparator<String> BY_CLOSEST =
      Comparator.<String>comparingInt(p -> basename(p).length())
          .thenComparing(Comparator.naturalOrder());

  /**
   * The single source that owns a test — the first of its {@code sourceCandidates} that exists. A
   * pattern candidate ({@code P[A-Z]*.java}) owns the test only when it matches exactly one source;
   * ambiguity claims nothing and the walk continues to a shorter prefix. {@code null} if {@code
   * path} is not a test or has no existing source.
   */
  private static String ownerSource(
      String path, Descriptor descriptor, String root, Set<String> existing) {
    String rel = relOf(root, path);
    if (!descriptor.isTestPath(rel)) {
      return null;
    }
    for (String candidate : descriptor.sourceCandidates(rel)) {
      String full = scoped(root, candidate);
      if (full.contains("*")) {
        Pattern re = gitignoreGlobToRegExp(full, true);
        List<String> matches =
            existing.stream().filter(p -> !p.equals(path) && re.matcher(p).matches()).toList();
        if (matches.size() == 1) {
          return matches.get(0);
        }
      } else if (!full.equals(path) && existing.contains(full)) {
        return full;
      }
    }
    return null;
  }

  private record CamelPrefix(String prefix, int words) {}

  private static final Pattern CAMEL_WORD = Pattern.compile("[A-Z][a-z0-9]*|[A-Z]+(?![a-z])");

  /**
   * The CamelCase "words" of a class base name: {@code TheFileCase} → {@code [The, File, Case]}. A
   * run of capitals stays together ({@code HTTPServer} → {@code [HTTP, Server]}). Falls back to
   * {@code [base]} if nothing matches.
   */
  private static List<String> camelWords(String base) {
    List<String> words = new ArrayList<>();
    Matcher m = CAMEL_WORD.matcher(base);
    while (m.find()) {
      words.add(m.group());
    }
    return words.isEmpty() ? List.of(base) : words;
  }

  /**
   * The CamelCase prefixes of a class base name, <strong>longest first</strong>, each tagged with
   * its word count: {@code TheFileCase} → {@code [{TheFileCase,3}, {TheFile,2}, {The,1}]}.
   */
  private static List<CamelPrefix> camelPrefixes(String base) {
    List<String> words = camelWords(base);
    List<CamelPrefix> prefixes = new ArrayList<>();
    StringBuilder acc = new StringBuilder();
    for (int i = 0; i < words.size(); i++) {
      acc.append(words.get(i));
      prefixes.add(new CamelPrefix(acc.toString(), i + 1));
    }
    java.util.Collections.reverse(prefixes);
    return prefixes;
  }

  /** The final path segment (basename) of a slash-separated path. */
  private static String basename(String path) {
    int slash = path.lastIndexOf('/');
    return slash == -1 ? path : path.substring(slash + 1);
  }

  private static char at(String s, int i) {
    return i >= 0 && i < s.length() ? s.charAt(i) : '\0';
  }

  private static final Pattern REGEX_META = Pattern.compile("[.+^$\\{\\}()|\\[\\]\\\\]");

  /**
   * A gitignore-complete glob → anchored {@link Pattern}. Distinguishes {@code **} (crosses
   * directory separators) from {@code *} (stays within one path segment), and supports {@code ?}
   * and character classes ({@code [a-z]}, {@code [!x]}). Callers here pass {@code sensitive=true}
   * to match git's default. A char-by-char port of {@code gitignoreGlobToRegExp} in {@code
   * filter-file-paths.ts} — do not swap for {@code PathMatcher}, its semantics differ.
   */
  static Pattern gitignoreGlobToRegExp(String glob, boolean sensitive) {
    StringBuilder re = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (at(glob, i + 1) == '*') {
          // `**` crosses directories; `**/` optionally consumes leading dirs; bare `**` is
          // anything.
          i++;
          if (at(glob, i + 1) == '/') {
            i++;
            re.append("(?:.*/)?");
          } else {
            re.append(".*");
          }
        } else {
          re.append("[^/]*"); // single `*` stays within one segment
        }
      } else if (c == '?') {
        re.append("[^/]");
      } else if (c == '[') {
        int j = i + 1;
        StringBuilder cls = new StringBuilder("[");
        if (at(glob, j) == '!') {
          cls.append('^');
          j++;
        }
        if (at(glob, j) == ']') {
          cls.append("\\]");
          j++;
        }
        while (j < glob.length() && glob.charAt(j) != ']') {
          char cc = glob.charAt(j);
          if (cc == '\\' || cc == '^') {
            cls.append('\\');
          }
          cls.append(cc);
          j++;
        }
        if (j >= glob.length()) {
          re.append("\\["); // unterminated class — treat `[` as a literal
        } else {
          re.append(cls).append(']');
          i = j; // j points at the closing `]`
        }
      } else {
        String s = String.valueOf(c);
        re.append(REGEX_META.matcher(s).matches() ? "\\" + s : s);
      }
    }
    int flags = sensitive ? 0 : Pattern.CASE_INSENSITIVE;
    return Pattern.compile("^" + re + "$", flags);
  }
}
