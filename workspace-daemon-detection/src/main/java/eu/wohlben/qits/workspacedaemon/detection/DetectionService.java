package eu.wohlben.qits.workspacedaemon.detection;

import eu.wohlben.qits.workspacedaemon.detection.FrameworkDetection.DetectedProject;
import eu.wohlben.qits.workspacedaemon.files.WorkspaceFiles;
import eu.wohlben.qits.workspacedaemon.files.WorkspaceFiles.Entry;
import eu.wohlben.qits.workspacedaemon.files.WorkspaceFiles.EntryType;
import eu.wohlben.qits.workspacedaemon.files.WorkspaceTreeScan;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Produces the framework/project/test-link metadata the file browser consumes, computed over the
 * live working tree this daemon owns. The pure classification is {@link FrameworkDetection}; this
 * orchestrator fetches the path list, layers the content peeks it can't do (a pom's Quarkus label,
 * a project's test runner) on top, resolves membership and links, and returns the one {@link
 * Detection}.
 *
 * <p>Moved here from the host's {@code DetectionService} because its entire cost was N small {@code
 * docker exec} reads per cache miss — a pom, a package.json and a handful of {@code stat}s per
 * detected root, each paying a process spawn. In the container they are plain {@code java.nio}
 * calls. Three things changed with the move, all of them consequences of "the daemon <em>is</em>
 * the workspace":
 *
 * <ol>
 *   <li><b>No repoId/workspaceId, no validation.</b> The host injected {@code
 *       RepositoryRepository}, {@code WorkspaceRepository} and {@code WorkspaceService} solely to
 *       resolve a workspace, 404 on an unknown one, and materialize its container before reading.
 *       Inside the container there is nothing to resolve and nothing to start.
 *   <li><b>Declared frameworks arrive as a parameter, from a different source.</b> See {@link
 *       #DetectionService(WorkspaceFiles, Supplier)}.
 *   <li><b>The cache is keyed on a marker the caller supplies.</b> See {@link #detect(String)}.
 * </ol>
 */
public final class DetectionService {

  private final WorkspaceFiles files;
  private final Supplier<List<DeclaredFramework>> declaredFrameworks;

  /** The single cached scan with the marker it was computed at ({@code null} until the first). */
  private record CachedDetection(String marker, Detection detection) {}

  /**
   * One workspace, so one slot instead of the host's per-workspace map. Volatile rather than
   * locked: a racing miss recomputes twice and the later write wins, which is cheaper than
   * serializing every hit behind a scan that reads files.
   */
  private volatile CachedDetection cached;

  private static final Pattern QUARKUS = Pattern.compile("quarkus", Pattern.CASE_INSENSITIVE);

  /** A {@code "lit"} dependency key in a package.json — the ts-lit candidate confirmation. */
  private static final Pattern LIT_DEP = Pattern.compile("\"lit\"\\s*:");

  /** Detection over marker-detected frameworks only, with nothing declared. */
  public DetectionService(WorkspaceFiles files) {
    this(files, List::of);
  }

  /**
   * @param declaredFrameworks the repository's declared {@code frameworks[]} hints, already parsed.
   *     <p><b>This is the one behavioural difference from the host that is not merely mechanical,
   *     and it changes where the hints come from.</b> The host read them from the <em>bare
   *     origin</em> at the repository's main branch ({@code qitsConfigParser.readConfig(origin,
   *     repo.mainBranch).frameworks()}) — a container-free read of a clone qits owns. The daemon
   *     has no access to that bare origin; it sees only its own checkout. So the hints must now
   *     come from the workspace's <em>own committed</em> config file ({@code
   *     /workspace/.config/qits/repository.yml}, legacy fallback {@code
   *     /workspace/.qits-config.yml} ), which the daemon's existing {@code ConfigReader}/{@code
   *     DaemonQitsConfig} already parses. Consequences worth knowing: a branch that edits its own
   *     {@code frameworks:} block now affects its own detection (on the host it did not, until
   *     merged to main), and an uncommitted edit to that file is picked up too, because it is read
   *     from the working tree.
   *     <p>A {@link Supplier} rather than a fixed list precisely because that file lives in the
   *     working tree and an agent can edit it: a list captured at construction would freeze the
   *     hints for the life of the daemon. Kept as a plain supplier of {@link DeclaredFramework} so
   *     this module never learns a file name, a YAML schema, or a config class.
   */
  public DetectionService(
      WorkspaceFiles files, Supplier<List<DeclaredFramework>> declaredFrameworks) {
    this.files = files;
    this.declaredFrameworks = declaredFrameworks;
  }

  /**
   * The workspace's detection metadata, served from cache while {@code marker} is unchanged. A tree
   * with no recognised framework yields empty lists — never an error.
   *
   * <p><b>The marker is the caller's, deliberately.</b> Detection depends on structural files an
   * agent can create or delete without committing, so it cannot be cached on the commit SHA; the
   * host validated it against a {@code WorkingTreeMarker} (sha256 of {@code git status
   * --porcelain=v2 --branch -uall} + {@code git diff}). The daemon already computes exactly that
   * marker, once, in {@code GitStatusMonitor} — debounced behind inotify rather than recomputed per
   * request. Recomputing it here would fork the same two git processes a second time and could
   * disagree with the value the monitor just reported, so this takes it as a parameter and the
   * daemon passes the monitor's. {@link #invalidate()} covers a caller with no marker to offer.
   *
   * <p>Inherited blind spot, unchanged: a content edit to an <em>already-untracked</em> file moves
   * neither {@code git status} nor {@code git diff}, so it does not invalidate — at worst one stale
   * response until any other tree change.
   */
  public Detection detect(String marker) {
    CachedDetection hit = cached;
    if (hit != null && hit.marker().equals(marker)) {
      return hit.detection();
    }
    Detection detection = scan();
    cached = new CachedDetection(marker, detection);
    return detection;
  }

  /** Drop the cached scan, so the next {@link #detect} rescans whatever marker it is given. */
  public void invalidate() {
    cached = null;
  }

  private Detection scan() {
    // The same submodule-aware walk the file listing serves, and it must be: the generation token
    // below is compared byte-for-byte against the listing's, so the two path lists have to come
    // from the one producer. It also means a framework rooted inside a submodule is detected like
    // any other — which, in a workspace that is mostly submodules, is most of them.
    List<String> paths = WorkspaceTreeScan.of(files).eagerPaths();

    // Consult the declared frameworks first (a config hint/override), then fall back to
    // marker-based detection for everything not declared. Declared entries win on the (kind, root)
    // they name; markers fill in the rest. The ts-lit marker (a Vite config) also matches React/Vue
    // Vite apps, so candidates are confirmed by a content peek first — a declared ts-lit entry
    // bypasses the peek by construction (merged afterwards).
    List<DetectedProject> projects =
        mergeDeclaredFrameworks(filterLitCandidates(FrameworkDetection.detect(paths)));

    // Content peeks, memoized per root within the scan: a pom's Quarkus label and a TS project's
    // test runner. Both are one small read per detected root, not per file.
    Map<String, String> labelByProject = new HashMap<>();
    Map<String, String> runnerByRoot = new HashMap<>();

    List<DetectionProject> projectViews = new ArrayList<>();
    List<FrameworkMembership> frameworks = new ArrayList<>();
    for (DetectedProject project : projects) {
      String id = project.descriptor().id();
      String label = label(project, labelByProject);
      projectViews.add(new DetectionProject(project.root(), id, label));
      frameworks.add(
          new FrameworkMembership(
              id, project.root(), label, FrameworkDetection.memberPaths(project, paths)));
    }

    List<FileLink> links = new ArrayList<>();
    for (String path : paths) {
      List<String> tests = FrameworkDetection.linkedTestsOf(path, projects, paths);
      if (tests.isEmpty()) {
        continue;
      }
      DetectedProject owner = FrameworkDetection.owningProject(path, projects);
      List<TestLink> testLinks =
          tests.stream().map(t -> new TestLink(t, testKinds(t, projects, runnerByRoot))).toList();
      links.add(new FileLink(path, owner == null ? null : owner.root(), testLinks));
    }

    // The structural generation token, stamped so the client can render detection only against the
    // matching file listing (TreeGeneration.of over the same normalized path list).
    return new Detection(projectViews, frameworks, links, TreeGeneration.of(paths));
  }

  /**
   * Confirms marker-detected {@code ts-lit} candidates with the content peek the pure detector
   * can't do: a Vite root counts as Lit only when its {@code package.json} declares a {@code lit}
   * dependency. Other kinds pass through untouched; a Vite root without the dependency is simply
   * not a project (never mislabeled). One small read per candidate root, mirroring the {@code
   * java-quarkus} pom peek.
   */
  private List<DetectedProject> filterLitCandidates(List<DetectedProject> detected) {
    List<DetectedProject> kept = new ArrayList<>();
    for (DetectedProject project : detected) {
      if ("ts-lit".equals(project.descriptor().id())) {
        boolean isLit =
            readIfPresent(rooted(project.root(), "package.json"))
                .filter(c -> LIT_DEP.matcher(c).find())
                .isPresent();
        if (!isLit) {
          continue;
        }
      }
      kept.add(project);
    }
    return kept;
  }

  /**
   * Prepends the repository's declared {@code frameworks[]} to the marker-detected projects,
   * deduped by {@code (kind, root)} so a declared entry supersedes an identically-keyed detected
   * one. An absent/invalid config or an unknown {@code kind} simply contributes nothing — detection
   * is a read path with no warning surface, and a broken config must never break it.
   */
  private List<DetectedProject> mergeDeclaredFrameworks(List<DetectedProject> detected) {
    List<DeclaredFramework> declared;
    try {
      declared = declaredFrameworks.get();
    } catch (RuntimeException e) {
      return detected; // a failing config read falls back to marker-only, never propagates
    }
    if (declared == null || declared.isEmpty()) {
      return detected;
    }

    List<DetectedProject> merged = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (DeclaredFramework decl : declared) {
      FrameworkDetection.Descriptor descriptor = FrameworkDetection.descriptorById(decl.kind());
      if (descriptor == null) {
        continue; // unknown kind — ignored
      }
      String root = normalizeRoot(decl.root());
      if (seen.add(descriptor.id() + " " + root)) {
        merged.add(new DetectedProject(root, descriptor));
      }
    }
    for (DetectedProject p : detected) {
      if (seen.add(p.descriptor().id() + " " + p.root())) {
        merged.add(p);
      }
    }
    return merged;
  }

  /** Normalize a declared framework root to the detector's convention ({@code ""} = repo root). */
  private static String normalizeRoot(String root) {
    if (root == null) {
      return "";
    }
    String r = root.trim();
    if (r.equals(".") || r.equals("/")) {
      return "";
    }
    while (r.startsWith("/")) {
      r = r.substring(1);
    }
    while (r.endsWith("/")) {
      r = r.substring(0, r.length() - 1);
    }
    return r;
  }

  /**
   * The framework's presentation label, refining a Java pom to "Java / Quarkus" via a content peek.
   */
  private String label(DetectedProject project, Map<String, String> memo) {
    if (!"java-quarkus".equals(project.descriptor().id())) {
      return project.descriptor().label();
    }
    return memo.computeIfAbsent(
        project.root(),
        root ->
            readIfPresent(rooted(root, "pom.xml"))
                    .filter(c -> QUARKUS.matcher(c).find())
                    .isPresent()
                ? "Java / Quarkus"
                : project.descriptor().label());
  }

  /**
   * The runner kind(s) of a test file. Java tests are {@code junit}; a {@code *.spec.ts} / {@code
   * *.test.ts} takes its owning TS project's (Angular or Lit) runner, config-detected once per root
   * (never a hardcoded default — qits' own SPA runs Vitest). A test owned by no known project falls
   * back to {@code unspecified}.
   */
  private List<String> testKinds(
      String testPath, List<DetectedProject> projects, Map<String, String> runnerByRoot) {
    DetectedProject owner = FrameworkDetection.owningProject(testPath, projects);
    if (owner != null && "java-quarkus".equals(owner.descriptor().id())) {
      return List.of("junit");
    }
    if (owner != null
        && ("ts-angular".equals(owner.descriptor().id())
            || "ts-lit".equals(owner.descriptor().id()))) {
      return List.of(runnerByRoot.computeIfAbsent(owner.root(), this::detectRunner));
    }
    return List.of("unspecified");
  }

  /**
   * The test runner of a TS project (Angular or Lit), detected from config: the {@code
   * angular.json} test builder first (absent at a Lit root, so it falls through), then the presence
   * of a runner's config file at the project root. Emits an open string id, or {@code unspecified}
   * when nothing matches — never a canonical guess.
   */
  private String detectRunner(String root) {
    Optional<String> content = readIfPresent(rooted(root, "angular.json"));
    if (content.isPresent()) {
      String c = content.get();
      if (c.contains("@angular/build:unit-test") || c.toLowerCase().contains("vitest")) {
        return "vitest";
      }
      if (c.contains(":karma") || c.toLowerCase().contains("karma")) {
        return "karma-jasmine";
      }
    }
    if (configPresent(root, "vitest.config")) {
      return "vitest";
    }
    if (configPresent(root, "playwright.config")) {
      return "playwright";
    }
    if (configPresent(root, "cypress.config")) {
      return "cypress";
    }
    if (configPresent(root, "karma.conf")) {
      return "karma-jasmine";
    }
    return "unspecified";
  }

  /**
   * Whether a {@code <base>.<ext>} config file exists at the project root, for the usual JS exts.
   */
  private boolean configPresent(String root, String base) {
    for (String ext : List.of("ts", "mts", "cts", "js", "mjs", "cjs")) {
      if (files.stat(rooted(root, base + "." + ext)).type() == EntryType.FILE) {
        return true;
      }
    }
    return false;
  }

  /** A root-relative name resolved against a project root ({@code ""} = the workspace root). */
  private static String rooted(String root, String name) {
    return root.isEmpty() ? name : root + "/" + name;
  }

  /**
   * Reads a file's text if it exists as a regular file; empty otherwise (missing or unreadable).
   *
   * <p>The {@link WorkspaceFiles#resolvesInsideRoot} guard is new here — the host read through
   * {@code docker exec cat}, already confined to the container. Now that the read is a plain {@code
   * java.nio} open in the daemon's own process, an escape matters: {@link WorkspaceFiles#stat} does
   * not follow symlinks, so a symlinked {@code pom.xml} already reports as {@code SYMLINK} and is
   * rejected, but a symlinked <em>intermediate</em> directory ({@code web -> /etc}, then {@code
   * web/angular.json}) still stats as a regular file. Cloned repositories are untrusted and git
   * checks symlinks out verbatim, so the containment check runs before the read. A peek that would
   * escape is treated as absent, which costs at most a label refinement.
   */
  private Optional<String> readIfPresent(String path) {
    try {
      Entry entry = files.stat(path);
      if (entry.type() != EntryType.FILE || !files.resolvesInsideRoot(path)) {
        return Optional.empty();
      }
      return Optional.of(new String(files.read(path), StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      // A peek is an optimization; its failure must never fail the scan. (The host caught its
      // InternalServerErrorException here for the same reason — this module has no exception type
      // of its own, and WorkspaceFiles documents only "throws" without one.)
      return Optional.empty();
    }
  }
}
