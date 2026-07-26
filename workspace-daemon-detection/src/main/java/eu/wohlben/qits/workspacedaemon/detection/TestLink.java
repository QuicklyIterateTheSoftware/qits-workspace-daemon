package eu.wohlben.qits.workspacedaemon.detection;

import java.util.List;

/**
 * One test file linked to a source, with the runner kind(s) it is executed by. {@code kinds} are
 * <strong>open string ids</strong> ({@code "junit"}, {@code "vitest"}, {@code "playwright"}, {@code
 * "cypress"}, {@code "karma-jasmine"}, {@code "unspecified"}, …), detected from project config
 * where the extension alone is ambiguous — never a closed enum.
 *
 * @param path the test file
 * @param kinds its runner kind ids
 */
public record TestLink(String path, List<String> kinds) {}
