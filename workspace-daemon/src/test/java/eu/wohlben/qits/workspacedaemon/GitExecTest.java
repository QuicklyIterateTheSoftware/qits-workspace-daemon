package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
          "SOME_CLIENT_SECRET", "client-secret",
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
          "SOME_CLIENT_SECRET",
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
    assertFalse(env.containsKey("SOME_CLIENT_SECRET"), "an inherited secret never rides along");
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

    // With no editor installed (a CI image) git refuses instead of printing one; either way the
    // planted value must not show.
    String shown = out.ok() ? out.line() : out.message();
    assertFalse(shown.contains("planted-editor"), shown);
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

  /** Whether {@code pid} is still a live process: absent and zombie both count as gone. */
  private static boolean running(long pid) throws Exception {
    Path stat = Path.of("/proc/" + pid + "/stat");
    try {
      String fields = Files.readString(stat);
      return fields.charAt(fields.lastIndexOf(')') + 2) != 'Z';
    } catch (java.io.IOException gone) {
      return false;
    }
  }

  private static boolean goneWithin(long pid, Duration within) throws Exception {
    long deadline = System.nanoTime() + within.toNanos();
    while (running(pid)) {
      if (System.nanoTime() > deadline) {
        return false;
      }
      Thread.sleep(50);
    }
    return true;
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aCallThatHoldsItsOutputOpenIsKilledWithItsChildrenAtTheDeadline(@TempDir Path tmp)
      throws Exception {
    // qits-2365: git holds stdout until it exits, so a stalled transfer used to block the read
    // before the deadline was ever consulted. The alias's shell holds the same pipe.
    Path pid = tmp.resolve("pid");
    long start = System.nanoTime();
    GitExec.Out out =
        GitExec.run(
            tmp,
            Map.of(),
            Duration.ofSeconds(1),
            "-c",
            "alias.hang=!echo $$ > " + pid + "; exec sleep 30",
            "hang");
    long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

    assertTrue(seconds < 10, "returned at the deadline, not when the child ended: " + seconds);
    assertTrue(out.timedOut(), out.toString());
    assertTrue(out.message().contains("timed out"), "a push classifies it transient: " + out);
    long child = Long.parseLong(Files.readString(pid).strip());
    assertTrue(goneWithin(child, Duration.ofSeconds(5)), "the child was killed too: " + child);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void anOrphanHoldingThePipeDoesNotHoldACallThatEnded(@TempDir Path tmp) throws Exception {
    Path pid = tmp.resolve("pid");
    long start = System.nanoTime();
    GitExec.Out out =
        GitExec.run(
            tmp,
            Map.of(),
            Duration.ofSeconds(120),
            "-c",
            "alias.orphan=!sh -c 'echo $$ > " + pid + "; exec sleep 30' & echo done",
            "orphan");
    long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    try {
      assertTrue(out.ok(), out.toString());
      assertTrue(out.line().startsWith("done"), out.toString());
      assertTrue(seconds < 15, "returned after a short grace, not with the orphan: " + seconds);
    } finally {
      ProcessHandle.of(Long.parseLong(Files.readString(pid).strip()))
          .ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aNetworkCallBoundsAStalledTransferWithTheProvisionersWindow(@TempDir Path tmp) {
    GitExec.Out out = GitExec.network(tmp, Map.of(), "-c", "alias.showenv=!env", "showenv");

    assertTrue(out.ok(), out.toString());
    assertTrue(
        out.stdout()
            .contains("GIT_HTTP_LOW_SPEED_LIMIT=" + Provisioner.lowSpeedLimitBytesPerSecond),
        out.stdout());
    assertTrue(
        out.stdout().contains("GIT_HTTP_LOW_SPEED_TIME=" + Provisioner.lowSpeedTimeSeconds),
        out.stdout());
  }
}
