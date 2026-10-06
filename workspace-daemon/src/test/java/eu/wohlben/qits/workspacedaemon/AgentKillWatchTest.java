package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks in {@link AgentKillWatch}: an agent that dies by SIGKILL is reported once, as the OOM
 * killer's work exactly when the cgroup counted a kill while it ran. Wired the way {@code
 * ControlSocket} wires it — behind a real {@link CommandLifecycleService}'s change callback over a
 * real {@link CommandStore} — with the cgroup played by two files in a temp directory, so no process
 * is spawned and no real cgroup is read.
 */
class AgentKillWatchTest {

  private static final long FOUR_GIB = 4L << 30;

  @TempDir Path cgroup;

  private final List<String> reported = new ArrayList<>();
  private final List<String> messages = new ArrayList<>();

  private final CommandStore store = new CommandStore();

  private CommandLifecycleService lifecycle(Path dir) {
    AgentKillWatch watch =
        new AgentKillWatch(
            store,
            new CgroupMemory(dir),
            (id, event, code, message) -> {
              reported.add(id + "=" + event + ":" + code);
              messages.add(message);
            });
    return new CommandLifecycleService(store, watch::commandsChanged);
  }

  private void launch(CommandLifecycleService lifecycle, String id, String agentType) {
    lifecycle.createRunning(
        "feature", "deadbeef", null, "agent", "claude", false, CommandKind.CHAT, id, null, agentType);
  }

  private void oomKills(long count) throws IOException {
    Files.writeString(
        cgroup.resolve("memory.events"),
        "low 0\nhigh 0\nmax 12\noom " + count + "\noom_kill " + count + "\noom_group_kill 0\n");
  }

  @Test
  void anAgentKilledAfterTheCgroupCountedAnOomIsReportedAsTheOomKillerWithTheCap()
      throws IOException {
    oomKills(0);
    Files.writeString(cgroup.resolve("memory.max"), FOUR_GIB + "\n");
    CommandLifecycleService lifecycle = lifecycle(cgroup);
    launch(lifecycle, "cmd-1", "claude");

    oomKills(4);
    lifecycle.markExited("cmd-1", 137);

    assertEquals(List.of("cmd-1=" + AgentEvent.OOM_KILLED + ":137"), reported);
    assertTrue(messages.get(0).contains("out-of-memory killer"), messages.get(0));
    assertTrue(messages.get(0).contains("exit code 137"), messages.get(0));
    assertTrue(messages.get(0).contains("memory cap 4 GiB"), messages.get(0));
  }

  @Test
  void everyAgentOneOomEventTookIsReportedAsItsWork() throws IOException {
    // The measured case: one OOM event, CHAT and TERMINAL both EXITED 137, oom_kill 4. The second
    // exit reads a counter that has not moved since the first — it is still the OOM killer's.
    oomKills(0);
    CommandLifecycleService lifecycle = lifecycle(cgroup);
    launch(lifecycle, "chat", "claude");
    launch(lifecycle, "terminal", "claude");

    oomKills(4);
    lifecycle.markExited("chat", 137);
    lifecycle.markExited("terminal", 137);

    assertEquals(
        List.of(
            "chat=" + AgentEvent.OOM_KILLED + ":137", "terminal=" + AgentEvent.OOM_KILLED + ":137"),
        reported);
  }

  @Test
  void aSigkillWithNoOomCountedIsAPlainKill() throws IOException {
    oomKills(2); // an earlier OOM, before this agent started, is not this agent's
    CommandLifecycleService lifecycle = lifecycle(cgroup);
    launch(lifecycle, "cmd-1", "claude");

    lifecycle.markExited("cmd-1", 137);

    assertEquals(List.of("cmd-1=" + AgentEvent.KILLED + ":137"), reported);
    assertTrue(messages.get(0).contains("SIGKILL"), messages.get(0));
  }

  @Test
  void noCgroupV2IsAPlainKillAndNoCap() {
    // cgroup v1, or a test: neither file exists. Never guessed to be an OOM, never a failure.
    CommandLifecycleService lifecycle = lifecycle(cgroup.resolve("absent"));
    launch(lifecycle, "cmd-1", "claude");

    lifecycle.markExited("cmd-1", 137);

    assertEquals(List.of("cmd-1=" + AgentEvent.KILLED + ":137"), reported);
    assertTrue(!messages.get(0).contains("memory cap"), messages.get(0));
  }

  @Test
  void aCleanExitATerminationAndANonAgentCommandAreNotKills() throws IOException {
    oomKills(0);
    CommandLifecycleService lifecycle = lifecycle(cgroup);
    launch(lifecycle, "clean", "claude");
    launch(lifecycle, "stopped", "claude");
    launch(lifecycle, "shell", null);

    oomKills(1);
    lifecycle.markExited("clean", 0);
    // Somebody asked for this one: DELETE /commands/{id}, or the daemon stopping its agents.
    lifecycle.markTerminated("stopped", 137);
    lifecycle.markExited("shell", 137);

    assertTrue(reported.isEmpty(), reported.toString());
  }

  @Test
  void aKillIsReportedOnceHoweverOftenTheListChangesAfterwards() throws IOException {
    oomKills(0);
    CommandLifecycleService lifecycle = lifecycle(cgroup);
    launch(lifecycle, "cmd-1", "claude");
    lifecycle.markExited("cmd-1", 137);

    launch(lifecycle, "cmd-2", "claude");
    lifecycle.markExited("cmd-2", 0);

    assertEquals(1, reported.size(), reported.toString());
  }

  @Test
  void aCapIsDescribedTheWayAPersonReadsIt() throws IOException {
    assertEquals("4 GiB", CgroupMemory.describe(FOUR_GIB));
    assertEquals("512 MiB", CgroupMemory.describe(512L << 20));
    assertEquals("1.5 GiB", CgroupMemory.describe(3L << 29));
    Files.writeString(cgroup.resolve("memory.max"), "max\n");
    assertTrue(new CgroupMemory(cgroup).limitBytes().isEmpty(), "no cap is no cap");
  }
}
