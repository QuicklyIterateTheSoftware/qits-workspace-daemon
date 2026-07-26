package eu.wohlben.qits.workspacedaemon.detection;

import eu.wohlben.qits.workspacedaemon.files.WorkspaceFiles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory {@link WorkspaceFiles} for the orchestrator tests: a path→content map plus just
 * enough {@code git} emulation ({@code ls-files}, {@code grep -l}) to drive a scan. Hand-written
 * rather than mocked so the two behaviours that actually matter here stay explicit and visible — a
 * {@code git grep} that matches nothing <em>throws</em> (git exits 1), and a path can be declared
 * as escaping the root so the symlink guard can be exercised.
 *
 * <p>{@code ls-files} deliberately replays paths in insertion order, unsorted and with duplicates
 * kept, so the production normalization (and therefore the generation token) is exercised rather
 * than assumed.
 */
final class FakeWorkspaceFiles implements WorkspaceFiles {

  private final Map<String, String> contents = new LinkedHashMap<>();
  private final List<String> lsFilesOrder = new ArrayList<>();
  private final Set<String> escapes = new HashSet<>();

  /** Register a file with its content; repeated calls overwrite the content, not the order. */
  FakeWorkspaceFiles file(String path, String content) {
    if (contents.put(path, content) == null) {
      lsFilesOrder.add(path);
    }
    return this;
  }

  /** Register a file with irrelevant content. */
  FakeWorkspaceFiles file(String path) {
    return file(path, "");
  }

  /**
   * Declare that {@code path} (or anything beneath it) resolves outside the workspace root — the
   * committed-symlink case {@link WorkspaceFiles#resolvesInsideRoot} exists to reject.
   */
  FakeWorkspaceFiles escaping(String path) {
    escapes.add(path);
    return this;
  }

  /** Replay a path twice in {@code ls-files}, as a staged + on-disk listing does. */
  FakeWorkspaceFiles duplicateInListing(String path) {
    lsFilesOrder.add(path);
    return this;
  }

  @Override
  public String git(String... args) {
    List<String> argv = List.of(args);
    if (!argv.isEmpty() && argv.get(0).equals("ls-files")) {
      return String.join("\n", lsFilesOrder) + "\n";
    }
    if (argv.size() >= 6 && argv.get(0).equals("grep")) {
      String needle = argv.get(4);
      List<String> hits =
          lsFilesOrder.stream()
              .distinct()
              .filter(p -> p.endsWith(".ts") && contents.getOrDefault(p, "").contains(needle))
              .toList();
      if (hits.isEmpty()) {
        throw new IllegalStateException("git grep exited 1 (no matches)");
      }
      return String.join("\n", hits) + "\n";
    }
    throw new UnsupportedOperationException("unexpected git " + argv);
  }

  @Override
  public Entry stat(String path) {
    if (contents.containsKey(path)) {
      return new Entry(path, EntryType.FILE, contents.get(path).length(), 0);
    }
    if (contents.keySet().stream().anyMatch(p -> p.startsWith(path + "/"))) {
      return new Entry(path, EntryType.DIRECTORY, 0, childCount(path));
    }
    return new Entry(path, EntryType.MISSING, 0, 0);
  }

  @Override
  public List<Entry> list(String dir) {
    throw new UnsupportedOperationException("detection never lists directories");
  }

  @Override
  public int childCount(String dir) {
    String prefix = dir.isEmpty() ? "" : dir + "/";
    return (int)
        contents.keySet().stream()
            .filter(p -> p.startsWith(prefix))
            .map(p -> p.substring(prefix.length()).split("/")[0])
            .distinct()
            .count();
  }

  @Override
  public boolean resolvesInsideRoot(String path) {
    if (stat(path).type() == EntryType.MISSING) {
      return false;
    }
    // An escaping segment anywhere along the path taints everything under it.
    for (String escape : escapes) {
      if (path.equals(escape) || path.startsWith(escape + "/")) {
        return false;
      }
    }
    return true;
  }

  @Override
  public byte[] read(String path) {
    String content = contents.get(path);
    if (content == null) {
      throw new IllegalStateException("no such file: " + path);
    }
    return content.getBytes(StandardCharsets.UTF_8);
  }
}
