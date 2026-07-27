package eu.wohlben.qits.workspacedaemon.agents;

import java.util.Locale;
import java.util.Optional;

/**
 * The kind of coding agent to launch. Selected via {@link CodingAgentFactory#ofType}; each value
 * maps to a {@link CodingAgent} implementation that knows how to render its own process invocation.
 */
public enum AgentType {
  CLAUDE,
  KIMI;

  /**
   * Parse a request or config value (case-insensitive, trimmed) into an {@link AgentType}. The
   * single place harness strings become the enum, so the request parameter, the checkout's {@code
   * .qits-config.yml} and the daemon default all share one lenient parser. An empty or unknown value
   * yields {@link Optional#empty()}.
   */
  public static Optional<AgentType> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    try {
      // Locale.ROOT: default-locale upper-casing mangles 'i' in a Turkish JVM (kimi -> KİMİ).
      return Optional.of(valueOf(trimmed.toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
