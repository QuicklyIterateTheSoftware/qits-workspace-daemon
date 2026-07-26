package eu.wohlben.qits.workspacedaemon.detection;

/**
 * One pre-parsed alternative of a component selector, structured so the frontend matcher stays
 * dumb: {@code app-foo} is element-only, {@code [appFoo]} attribute-only, {@code button[appFoo]}
 * carries both.
 *
 * @param element the element (tag) name, lowercased, or {@code null}
 * @param attribute the attribute name (without brackets or value), or {@code null}
 */
public record ComponentSelector(String element, String attribute) {}
