package eu.wohlben.qits.workspacedaemon.files;

/**
 * A lazily-resolvable directory in the workspace's file tree — shown as a collapsed folder stub
 * whose contents are fetched on demand.
 *
 * <p>The host's {@code LazyDirDto} carried a third field, {@code href}, holding the {@code
 * /api/repositories/…/files?path=…} URL the client follows to open the stub. It is absent here on
 * purpose: the daemon does not know the repository or workspace id that URL is built from (it
 * <em>is</em> one workspace), so the host's controller keeps synthesising it from {@link #path}.
 * The two fields that do exist keep their DTO names so the JSON the browser eventually sees is
 * unchanged.
 *
 * @param path the directory path relative to the workspace root (no trailing slash)
 * @param childCount the number of <em>immediate</em> children (not total descendants — counting
 *     those would mean walking the very directory we are refusing to walk); drives the {@code
 *     node_modules/ (312)} label hint
 */
public record LazyDir(String path, int childCount) {}
