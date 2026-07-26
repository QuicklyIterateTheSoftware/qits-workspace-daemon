package eu.wohlben.qits.workspacedaemon.detection;

import java.util.List;

/**
 * One component in a workspace's component map.
 *
 * @param className the exported class name (e.g. {@code Greeting})
 * @param componentFile the {@code .ts} file declaring the component, workspace-relative
 * @param templateFile the external template resolved workspace-relative, or {@code null} for an
 *     inline template
 * @param styleFiles external stylesheets resolved workspace-relative; empty for inline/no styles
 * @param selectors the selector's comma alternatives, pre-parsed; empty for selector-less (routed)
 *     components, which are still matchable by class name
 */
public record ComponentMapEntry(
    String className,
    String componentFile,
    String templateFile,
    List<String> styleFiles,
    List<ComponentSelector> selectors) {}
