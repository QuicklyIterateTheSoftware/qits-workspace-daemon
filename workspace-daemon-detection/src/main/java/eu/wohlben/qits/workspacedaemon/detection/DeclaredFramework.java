package eu.wohlben.qits.workspacedaemon.detection;

/**
 * One {@code frameworks[]} entry a repository <em>declares</em> in its qits config, as the only
 * thing detection needs from it: a kind id and a root. Handed to {@link DetectionService} already
 * parsed, so this module stays free of any config-file knowledge — no YAML, no file name, no
 * schema. The daemon maps its existing {@code DaemonQitsConfig.FrameworkDecl} onto this one-to-one.
 *
 * <p>Declared entries are hints that <em>supersede</em> marker detection on the exact {@code (kind,
 * root)} they name (see {@link DetectionService}); markers fill in everything else.
 *
 * @param kind a {@link FrameworkDetection.Descriptor} id ({@code java-quarkus}, {@code ts-angular},
 *     {@code ts-lit}, {@code docs}); an unrecognised value contributes nothing
 * @param root the project root relative to the workspace ({@code ""}, {@code "."} and {@code "/"}
 *     all mean the workspace root)
 */
public record DeclaredFramework(String kind, String root) {}
