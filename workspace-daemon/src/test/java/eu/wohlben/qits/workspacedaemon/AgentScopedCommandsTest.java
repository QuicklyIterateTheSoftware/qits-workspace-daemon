package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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

  @AfterEach
  void restoreTheRealProcessEnvironment() {
    AgentScopedCommands.processEnv = System::getenv;
  }

  @Test
  void everyWorkspaceSecretIsBlankedAndTheAgentsTokenWins() {
    Map<String, String> env =
        scoped(Map.of("QITS_TOKEN", "agent-token")).environmentFor(Map.of("HOME", "/claude-home"));

    assertEquals("agent-token", env.get("QITS_TOKEN"));
    assertEquals("", env.get("QITS_WORKSPACE_DAEMON_API_TOKEN"), "no way to drive the daemon");
    assertEquals("", env.get("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION"));
    assertEquals("/claude-home", env.get("HOME"));
  }

  @Test
  void anAgentWithoutATokenGetsAnEmptyOneRatherThanTheWorkspaces() {
    assertEquals("", scoped(Map.of()).environmentFor(Map.of()).get("QITS_TOKEN"));
  }

  /**
   * The bug this class exists to fix: the library bakes the egress proxy URL from its own,
   * un-overlaid view of the environment — here stood in for by a process environment that answers
   * the container's own token — before the agent's per-agent token is ever applied. {@code
   * environmentFor} must rewrite the four proxy variables with that agent's token once it is known,
   * discarding whatever the library baked in, URL-encoding it the same way the library would.
   */
  @Test
  void theProxyUrlCarriesTheAgentsOwnTokenNotTheOneTheLibraryBakedFromTheProcessEnvironment() {
    AgentScopedCommands.processEnv = name -> "QITS_TOKEN".equals(name) ? "container-token" : null;
    String wrong = "http://agent:container-token@127.0.0.1:3128";
    Map<String, String> launch =
        Map.of(
            "QITS_EGRESS_PROXY", "127.0.0.1:3128",
            "HTTPS_PROXY", wrong,
            "https_proxy", wrong,
            "HTTP_PROXY", wrong,
            "http_proxy", wrong);

    Map<String, String> env = scoped(Map.of("QITS_TOKEN", "agent token/+")).environmentFor(launch);

    String expected = "http://agent:agent%20token%2F%2B@127.0.0.1:3128";
    assertEquals(expected, env.get("HTTPS_PROXY"));
    assertEquals(expected, env.get("https_proxy"));
    assertEquals(expected, env.get("HTTP_PROXY"));
    assertEquals(expected, env.get("http_proxy"));
  }

  @Test
  void aBlankAgentTokenLeavesWhateverTheLibrarySetUnchanged() {
    String fromTheLibrary = "http://agent:container-token@127.0.0.1:3128";
    Map<String, String> launch =
        Map.of("QITS_EGRESS_PROXY", "127.0.0.1:3128", "HTTPS_PROXY", fromTheLibrary);

    Map<String, String> env = scoped(Map.of()).environmentFor(launch);

    assertEquals(fromTheLibrary, env.get("HTTPS_PROXY"));
  }

  @Test
  void withNoEgressProxyNoProxyVariableIsAdded() {
    AgentScopedCommands.processEnv = name -> null;

    Map<String, String> env =
        scoped(Map.of("QITS_TOKEN", "agent-token")).environmentFor(Map.of());

    assertNull(env.get("HTTPS_PROXY"));
    assertNull(env.get("https_proxy"));
    assertNull(env.get("HTTP_PROXY"));
    assertNull(env.get("http_proxy"));
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
