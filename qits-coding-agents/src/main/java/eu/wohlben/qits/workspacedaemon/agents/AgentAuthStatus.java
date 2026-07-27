package eu.wohlben.qits.workspacedaemon.agents;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Whether the configured coding agent has a usable login on the shared credential volume. For Claude
 * Code this runs {@code claude auth status}; for Kimi Code it probes credential-file presence under
 * the real volume home. Used by {@link AgentLaunchService} to redirect a launch to an interactive
 * login terminal when the agent isn't signed in yet.
 *
 * <p>The host asked this question of a container over {@code docker exec}, and answered
 * not-signed-in when the container was missing. Here the probe runs in the container, so there is no
 * container to check for and no name to derive — the ids the host needed for both are gone from the
 * signature.
 */
public final class AgentAuthStatus {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final ProcessRunner processes;
  private final String claudeMount;
  private final Path workspaceRoot;

  public AgentAuthStatus(ProcessRunner processes, String claudeMount, Path workspaceRoot) {
    this.processes = processes;
    this.claudeMount = claudeMount;
    this.workspaceRoot = workspaceRoot;
  }

  /**
   * Whether {@code agentType} is signed in. The harness is passed in (the launch has already
   * resolved it) so the auth gate matches exactly the harness about to launch.
   */
  public boolean isLoggedIn(AgentType agentType) {
    return switch (agentType) {
      case CLAUDE -> probeClaude();
      case KIMI -> probeKimi();
    };
  }

  private boolean probeClaude() {
    ProcessRunner.Result result =
        processes.exec(
            List.of("claude", "auth", "status"),
            workspaceRoot,
            Map.of("HOME", claudeMount),
            TIMEOUT);
    return parseClaudeLoggedIn(result.exitCode(), result.output());
  }

  private boolean probeKimi() {
    String kimiHome = claudeMount + "/.kimi-code";
    // Kimi 0.28+ stores the OAuth token as $KIMI_CODE_HOME/credentials/kimi-code.json (a
    // directory layout); earlier builds wrote a flat `credentials` file. Accept either — a
    // `-f` probe on `credentials` alone always fails against the directory layout, which
    // misreads a signed-in volume as logged out and redirects every launch to `kimi login`
    // (which then exits immediately on the existing login — the "flicker").
    ProcessRunner.Result result =
        processes.exec(
            List.of(
                "bash",
                "-c",
                "test -f \"$KIMI_CODE_HOME/credentials\" "
                    + "|| test -f \"$KIMI_CODE_HOME/credentials/kimi-code.json\""),
            workspaceRoot,
            Map.of("KIMI_CODE_HOME", kimiHome),
            TIMEOUT);
    return result.exitCode() == 0;
  }

  /**
   * Reads the {@code claude auth status} result. The command prints JSON ({@code {"loggedIn": …}})
   * and exits non-zero when signed out; prefer the explicit field, fall back to the exit code.
   */
  static boolean parseClaudeLoggedIn(int exitCode, String output) {
    if (output != null && output.contains("\"loggedIn\"")) {
      String compact = output.replace(" ", "");
      return compact.contains("\"loggedIn\":true");
    }
    return exitCode == 0;
  }
}
