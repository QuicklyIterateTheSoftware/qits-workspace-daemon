package eu.wohlben.qits.workspacedaemon.detection;

import java.util.List;

/**
 * The component map of a workspace's working tree: every UI component the scanner could attribute
 * DOM elements to, with its source files. Framework-generic envelope from day one so future
 * scanners (React, Vue) extend rather than break the contract.
 *
 * <p>The host's {@code ComponentMapDto} without the framework; component names unchanged so the
 * JSON the web-view picker consumes is byte-identical.
 *
 * @param framework the framework the scan targeted (currently always {@code "angular"})
 * @param components the components found; empty when the tree contains none (e.g. a non-Angular
 *     repository)
 */
public record ComponentMap(String framework, List<ComponentMapEntry> components) {}
