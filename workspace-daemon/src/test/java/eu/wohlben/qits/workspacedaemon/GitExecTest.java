package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Which secrets a git process of the daemon's may carry: none, or exactly the one named. */
class GitExecTest {

  private static final Map<String, String> DAEMON =
      Map.of(
          "PATH", "/usr/bin",
          "QITS_TOKEN", "workspace-token",
          "QITS_COMMISSIONED_CLIENT_ID", "client",
          "QITS_COMMISSIONED_CLIENT_SECRET", "client-secret",
          "QITS_WORKSPACE_DAEMON_API_TOKEN", "api-token",
          "QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION", "{\"headerValue\":\"x\"}",
          "SOME_PASSWORD", "p",
          "GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");

  @Test
  void aLocalCallCarriesNoSecretOfTheWorkspace() {
    Map<String, String> env = GitExec.environment(DAEMON, Map.of());

    assertEquals("/usr/bin", env.get("PATH"));
    for (String name :
        new String[] {
          "QITS_TOKEN",
          "QITS_COMMISSIONED_CLIENT_ID",
          "QITS_COMMISSIONED_CLIENT_SECRET",
          "QITS_WORKSPACE_DAEMON_API_TOKEN",
          "QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION",
          "SOME_PASSWORD"
        }) {
      assertFalse(env.containsKey(name), name);
    }
    assertEquals("/etc/qits-gitconfig", env.get("GIT_CONFIG_GLOBAL"), "the image's helper stays");
  }

  @Test
  void anAgentsPushCarriesTheAgentsTokenAndNotTheWorkspaces() {
    Map<String, String> env = GitExec.environment(DAEMON, Map.of("QITS_TOKEN", "agent-token"));

    assertEquals("agent-token", env.get("QITS_TOKEN"));
    assertFalse(env.containsKey("QITS_COMMISSIONED_CLIENT_SECRET"), "no fallback to the pair");
    assertFalse(env.containsKey("QITS_WORKSPACE_DAEMON_API_TOKEN"));
  }

  @Test
  void withoutAGlobalConfigTheWritableHomeConfigIsNotRead() {
    Map<String, String> env = GitExec.environment(Map.of("HOME", "/workspace"), Map.of());

    assertEquals("/dev/null", env.get("GIT_CONFIG_GLOBAL"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aNetworkCallTakesOnlyCredentialNamesFromWhatItIsHanded(@TempDir Path tmp) throws Exception {
    // A host-supplied env could name anything; a daemon push must not run with, say, the agent's
    // GIT_SSH_COMMAND. `git var` prints what git would use, so the process proves it.
    GitFixtures.git(tmp, "init", "--quiet");
    GitExec.Out out =
        GitExec.network(
            tmp,
            Map.of("QITS_TOKEN", "agent-token", "GIT_EDITOR", "planted-editor"),
            "var",
            "GIT_EDITOR");

    assertTrue(out.ok(), out.message());
    assertFalse(out.line().contains("planted-editor"), out.line());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void noHookOrFsmonitorAnAgentPlantedRunsInADaemonCall(@TempDir Path tmp) throws Exception {
    GitFixtures.git(tmp, "init", "--quiet");
    Path ran = tmp.resolve("ran");
    Path script = tmp.resolve("evil.sh");
    Files.writeString(script, "#!/bin/sh\ntouch " + ran + "\n");
    script.toFile().setExecutable(true);
    GitFixtures.git(tmp, "config", "core.fsmonitor", script.toString());
    Files.createDirectories(tmp.resolve(".git/hooks"));
    Files.copy(script, tmp.resolve(".git/hooks/post-checkout"));
    tmp.resolve(".git/hooks/post-checkout").toFile().setExecutable(true);
    Files.writeString(tmp.resolve("a.txt"), "a\n");

    GitExec.git(tmp, "status", "--porcelain");
    GitExec.git(tmp, "add", "a.txt");
    GitExec.git(tmp, "-c", "user.name=x", "-c", "user.email=x@x", "commit", "-qm", "a");
    GitExec.git(tmp, "checkout", "-q", "-b", "other");

    assertFalse(Files.exists(ran), "planted code ran inside a daemon git call");
  }
}
