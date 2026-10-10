package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a harness process is given: its agent's credential, and none of the workspace's. */
class AgentScopedCommandsTest {

  private static AgentScopedCommands scoped(Map<String, String> agentEnv) {
    return new AgentScopedCommands(
        null,
        Path.of("/workspace/agents/a1/wrapper"),
        () -> agentEnv,
        () -> null,
        () -> null,
        (id, command) -> {});
  }

  @Test
  void everyWorkspaceSecretIsBlankedAndTheAgentsTokenWins() {
    Map<String, String> env =
        scoped(Map.of("QITS_TOKEN", "agent-token")).environmentFor(Map.of("HOME", "/claude-home"));

    assertEquals("agent-token", env.get("QITS_TOKEN"));
    assertEquals("", env.get("QITS_WORKSPACE_DAEMON_API_TOKEN"), "no way to drive the daemon");
    assertEquals("", env.get("QITS_COMMISSIONED_CLIENT_ID"));
    assertEquals("", env.get("QITS_COMMISSIONED_CLIENT_SECRET"));
    assertEquals("", env.get("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION"));
    assertEquals("/claude-home", env.get("HOME"));
  }

  @Test
  void anAgentWithoutATokenGetsAnEmptyOneRatherThanTheWorkspaces() {
    assertEquals("", scoped(Map.of()).environmentFor(Map.of()).get("QITS_TOKEN"));
  }

  @Test
  void theHarnessRunsInTheAgentsWorktreeQuotedAgainstTheShell() {
    String script =
        new AgentScopedCommands(
                null,
                Path.of("/workspace/agents/a1/it's"),
                Map::of,
                () -> null,
                () -> null,
                (id, command) -> {})
            .inWorkingDirectory("exec claude");

    assertTrue(script.startsWith("cd -- '/workspace/agents/a1/it'\\''s' || exit 1\n"), script);
  }
}
