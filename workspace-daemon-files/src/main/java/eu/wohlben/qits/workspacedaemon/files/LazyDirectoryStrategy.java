package eu.wohlben.qits.workspacedaemon.files;

import java.util.List;

/**
 * Decides which directories of the workspace are returned as <em>lazy stubs</em> — shown in the
 * file tree as collapsed folders whose contents are fetched on demand — instead of being walked
 * eagerly.
 *
 * <p>This is the pluggable seam behind lazy directory exploration: the default {@link
 * GitignoreLazyDirectoryStrategy} treats gitignored directories as the lazy boundary, but future
 * heuristics (commit frequency, size, age, …) can slot in behind this interface without touching
 * the transport or the UI. On the host this was a CDI {@code Instance<LazyDirectoryStrategy>}
 * picked by config; here there is no container, so the candidates are handed to {@link
 * WorkspaceFileBrowser}'s constructor as a plain list and selected by {@link #id()} — which is also
 * what keeps this jar free of the {@code ServiceLoader} a native image would have to be told about.
 */
public interface LazyDirectoryStrategy {

  /** The config id this strategy answers to (e.g. {@code "gitignore"}). */
  String id();

  /**
   * The workspace-root-relative directory paths (no trailing slash) that should be returned as lazy
   * stubs rather than recursed into. Cheap by contract — a strategy must not walk the directories
   * it marks lazy.
   *
   * @param files the workspace's filesystem seam, rooted at the checkout this daemon owns
   */
  List<String> lazyDirectories(WorkspaceFiles files);
}
