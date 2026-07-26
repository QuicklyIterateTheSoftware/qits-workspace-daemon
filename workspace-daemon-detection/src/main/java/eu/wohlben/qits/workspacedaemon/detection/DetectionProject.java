package eu.wohlben.qits.workspacedaemon.detection;

/**
 * One detected project root (the host's {@code DetectedProjectDto}, component names unchanged).
 * {@code frameworkId} is an <strong>open string id</strong> ({@code "java-quarkus"}, {@code
 * "ts-angular"}, {@code "docs"}, …), never a closed enum, so adding a framework needs no client
 * regen and an older client degrades to a generic icon rather than failing to deserialize.
 *
 * <p>Named {@code DetectionProject} rather than {@code DetectedProject} only to keep it distinct
 * from {@link FrameworkDetection.DetectedProject}, the pure detector's internal (root, descriptor)
 * pair — the wire field names are what must not move, and they don't.
 *
 * @param root dir relative to the workspace root ({@code ""} = workspace root)
 * @param frameworkId the framework kind's stable id
 * @param label presentation label, already pom-refined ("Java / Quarkus")
 */
public record DetectionProject(String root, String frameworkId, String label) {}
