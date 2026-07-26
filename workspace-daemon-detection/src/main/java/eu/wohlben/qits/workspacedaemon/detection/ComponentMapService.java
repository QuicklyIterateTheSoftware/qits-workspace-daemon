package eu.wohlben.qits.workspacedaemon.detection;

import eu.wohlben.qits.workspacedaemon.files.WorkspaceFiles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the component map of the workspace: every {@code @Component} the working tree declares,
 * with selectors and source files, so a web-view pick can be attributed to the code that renders
 * it. The scan reads the live checkout through {@link WorkspaceFiles} — an agent's uncommitted
 * edits included — and is lazy: nothing is scanned until the first request.
 *
 * <p>Moved here from the host's {@code ComponentMapService} for the same reason as {@link
 * DetectionService}: the scan is one read per candidate {@code .ts} file, and on the host every one
 * of those was a {@code docker exec}. The host-only plumbing (repo/workspace lookup, 404, {@code
 * ensureContainer}) is gone — the daemon <em>is</em> the workspace — and the cache is keyed on a
 * caller-supplied working-tree marker; see {@link #componentMap(String)}.
 */
public final class ComponentMapService {

  private final WorkspaceFiles files;

  private record CachedScan(String marker, ComponentMap map) {}

  /** One workspace, so one slot; see {@code DetectionService.cached} for the volatile rationale. */
  private volatile CachedScan cached;

  public ComponentMapService(WorkspaceFiles files) {
    this.files = files;
  }

  /**
   * The workspace's component map, scanned on demand and served from cache while {@code marker} is
   * unchanged. A tree without components (or a non-Angular repository) yields an empty map — never
   * an error.
   *
   * <p>The marker is the caller's for the same reason it is in {@link DetectionService#detect}: the
   * daemon's {@code GitStatusMonitor} already computes the identical working-tree marker (sha256 of
   * {@code git status --porcelain=v2 --branch -uall} + {@code git diff}) behind its inotify
   * debounce, so recomputing it here would fork the same two git processes again and could disagree
   * with the value just reported. Known accepted staleness, inherited: a content edit to an
   * already-untracked file moves neither {@code git status} nor {@code git diff}, so such an edit
   * is invisible until any other tree change — at worst one pick session sees a stale map.
   */
  public ComponentMap componentMap(String marker) {
    CachedScan hit = cached;
    if (hit != null && hit.marker().equals(marker)) {
      return hit.map();
    }
    ComponentMap map = scan();
    cached = new CachedScan(marker, map);
    return map;
  }

  /** Drop the cached scan, so the next {@link #componentMap} rescans. */
  public void invalidate() {
    cached = null;
  }

  private ComponentMap scan() {
    List<ComponentMapEntry> components = new ArrayList<>();
    for (String path : candidateFiles()) {
      // git grep lists paths from blob content, and a symlink's blob is its target text — so a
      // symlink whose target string happens to contain "@Component" would be listed and then
      // dereferenced by the read. Skip anything that does not resolve inside the checkout.
      if (!files.resolvesInsideRoot(path)) {
        continue;
      }
      String source;
      try {
        source = new String(files.read(path), StandardCharsets.UTF_8);
      } catch (RuntimeException e) {
        continue; // deleted between the grep and the read, or unreadable — one file, not the scan
      }
      for (var parsed : AngularComponentParser.parse(path, source)) {
        components.add(
            new ComponentMapEntry(
                parsed.className(),
                parsed.componentFile(),
                parsed.templateFile(),
                parsed.styleFiles(),
                parsed.selectors().stream()
                    .map(s -> new ComponentSelector(s.element(), s.attribute()))
                    .toList()));
      }
    }
    return new ComponentMap("angular", components);
  }

  /**
   * The {@code .ts} files mentioning {@code @Component}, tracked and untracked alike (uncommitted
   * agent edits are the whole point of scanning the working tree). {@code git grep} exits non-zero
   * when nothing matches, which {@link WorkspaceFiles#git} surfaces as an exception — treated as
   * "no candidates" here. That also swallows a genuine grep failure, deliberately: the endpoint's
   * contract is an empty map over an error.
   */
  private List<String> candidateFiles() {
    String output;
    try {
      output = files.git("grep", "-l", "--untracked", "-e", "@Component", "--", "*.ts");
    } catch (RuntimeException e) {
      return List.of();
    }
    return output
        .lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .filter(line -> !line.endsWith(".spec.ts"))
        .toList();
  }
}
