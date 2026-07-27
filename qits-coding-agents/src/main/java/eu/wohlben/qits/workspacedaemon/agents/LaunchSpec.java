package eu.wohlben.qits.workspacedaemon.agents;

import java.util.Map;

/**
 * A rendered agent launch: the exact shell {@code script} to run, whether it is {@code interactive}
 * (a human attaches a terminal) or a one-off, and an environment overlay. The prompt (if any) is
 * embedded directly in the {@code script} as a shell-quoted argument — there is no side file to
 * write.
 */
public record LaunchSpec(String script, boolean interactive, Map<String, String> environment) {

  public LaunchSpec {
    environment = environment == null ? Map.of() : Map.copyOf(environment);
  }
}
