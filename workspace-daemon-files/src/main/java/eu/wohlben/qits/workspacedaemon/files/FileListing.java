package eu.wohlben.qits.workspacedaemon.files;

import java.util.List;

/**
 * One level of the workspace's file tree: eager {@code paths} (files rendered immediately), {@code
 * lazyDirs} (collapsed stubs), and the whole-tree structural {@code generation} token. Returned for
 * both the root and a single lazy directory, which is what lets arbitrarily deep lazy nesting
 * resolve through one endpoint.
 *
 * <p>{@code generation} is deliberately whole-tree even when the listing is a single lazy level:
 * the client gates tree-vs-detection rendering on the same token regardless of which level
 * triggered the fetch, so a per-level token would make the two fetches incomparable. Field names
 * are the host controller response's, so the JSON reaching the UI is unchanged.
 *
 * @param paths eager, root-relative file paths, sorted
 * @param lazyDirs collapsed directory stubs, sorted by path
 * @param generation the {@link WorkspaceTreeFingerprint} of the whole tree
 */
public record FileListing(List<String> paths, List<LazyDir> lazyDirs, String generation) {}
