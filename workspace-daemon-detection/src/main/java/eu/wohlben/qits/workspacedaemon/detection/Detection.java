package eu.wohlben.qits.workspacedaemon.detection;

import java.util.List;

/**
 * Everything the workspace file browser needs to understand a workspace's structure, computed once
 * over the live working tree and (once the daemon wires this module in) served from {@code GET
 * .../{workspaceId}/detection}: the detected projects, per-framework resolved membership sets (the
 * filter input), and the precomputed source↔test link graph. Keeps {@code /files} a pure filesystem
 * transport.
 *
 * <p>The host's {@code DetectionDto} without the framework: a plain record, no Jackson annotations,
 * and <strong>component names kept byte-identical</strong> to that DTO's so the JSON the browser
 * eventually sees is unchanged whichever side serializes it.
 *
 * @param projects one entry per detected project root (the ownership list)
 * @param frameworks the same roots with their resolved member path sets (the whitelist input)
 * @param links source→test graph, precomputed over the full path set
 * @param generation the structural generation token (a hash of the sorted {@code ls-files}); the
 *     client applies this detection only while it matches the {@code /files} response's generation,
 *     so tree and detection never render as a skewed combination
 */
public record Detection(
    List<DetectionProject> projects,
    List<FrameworkMembership> frameworks,
    List<FileLink> links,
    String generation) {}
