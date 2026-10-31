package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.RemoteControlLog;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.AgentSessionSource;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.workspacedaemon.protocol.AgentHarnessExit;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
  void everyAgentExitIsLoggedOnceWithItsAgentStatusAndCodeAndOnlyTheKillIsReported()
      throws IOException {
    // The owner lookup is what the log line is made from, so who was asked is who was logged.
    List<String> logged = new ArrayList<>();
    oomKills(0);
    AgentKillWatch watch =
        new AgentKillWatch(
            store,
            new CgroupMemory(cgroup),
            (id, event, code, message) -> reported.add(id + "=" + event + ":" + code),
            id -> {
              logged.add(id);
              return switch (id) {
                case "removed" -> new AgentKillWatch.Owner("agent-1", "remove");
                case "finished", "killed" -> new AgentKillWatch.Owner("agent-2", null);
                default -> null;
              };
            },
            exit -> {});
    CommandLifecycleService lifecycle = new CommandLifecycleService(store, watch::commandsChanged);
    launch(lifecycle, "removed", "claude");
    launch(lifecycle, "finished", "claude");
    launch(lifecycle, "killed", "claude");
    launch(lifecycle, "sign-in", "claude");
    launch(lifecycle, "shell", null);

    lifecycle.markTerminated("removed", 143);
    lifecycle.markExited("finished", 0);
    lifecycle.markExited("killed", 137);
    lifecycle.markExited("sign-in", 1);
    lifecycle.markExited("shell", 0);
    launch(lifecycle, "later", "claude"); // the list changes again: nothing is logged twice

    assertEquals(List.of("removed", "finished", "killed", "sign-in"), logged);
    assertEquals(List.of("killed=" + AgentEvent.KILLED + ":137"), reported, "only the kill");
    assertEquals(
        "agent agent-1's claude harness exited: CHAT command removed, status TERMINATED, exit code"
            + " 143, stopped by the daemon's own remove",
        AgentKillWatch.exitLine(
            store.find("removed").orElseThrow(), new AgentKillWatch.Owner("agent-1", "remove")));
    assertEquals(
        "agent agent-2's claude harness exited: CHAT command finished, status EXITED, exit code 0",
        AgentKillWatch.exitLine(
            store.find("finished").orElseThrow(), new AgentKillWatch.Owner("agent-2", null)));
    assertTrue(
        AgentKillWatch.exitLine(store.find("sign-in").orElseThrow(), null)
            .startsWith("agent (none)'s"));
  }

  @Test
  void everySettledAgentExitSendsExactlyOneFrameNamingWhatEndedIt() throws IOException {
    oomKills(0);
    List<AgentHarnessExit> sent = new ArrayList<>();
    AgentKillWatch watch =
        new AgentKillWatch(
            store,
            new CgroupMemory(cgroup),
            (id, event, code, message) -> reported.add(id + "=" + event + ":" + code),
            id ->
                switch (id) {
                  case "removed" -> new AgentKillWatch.Owner("agent-1", "remove");
                  case "yielded" -> new AgentKillWatch.Owner("agent-2", "yield");
                  case "killed", "finished" -> new AgentKillWatch.Owner("agent-3", null);
                  default -> null;
                },
            sent::add);
    CommandLifecycleService lifecycle = new CommandLifecycleService(store, watch::commandsChanged);
    launch(lifecycle, "removed", "claude");
    launch(lifecycle, "yielded", "claude");
    launch(lifecycle, "killed", "claude");
    launch(lifecycle, "finished", "kimi");
    launch(lifecycle, "sign-in", "claude");
    launch(lifecycle, "shell", null);

    lifecycle.markTerminated("removed", 137); // the grace ran out: still the daemon's remove
    lifecycle.markTerminated("yielded", 143);
    lifecycle.markExited("killed", 137);
    lifecycle.markExited("finished", 0);
    lifecycle.markExited("sign-in", 1);
    lifecycle.markExited("shell", 0);
    launch(lifecycle, "later", "claude"); // the list changes again: nothing is sent twice

    assertEquals(
        List.of(
            "agent-1 removed claude TERMINATED 137 " + AgentHarnessExit.DAEMON_REMOVE,
            "agent-2 yielded claude TERMINATED 143 " + AgentHarnessExit.DAEMON_YIELD,
            "agent-3 killed claude EXITED 137 " + AgentHarnessExit.KILLED,
            "agent-3 finished kimi EXITED 0 " + AgentHarnessExit.SELF,
            "null sign-in claude EXITED 1 " + AgentHarnessExit.SELF),
        sent.stream()
            .map(
                e ->
                    e.agentId()
                        + " "
                        + e.commandId()
                        + " "
                        + e.agentType()
                        + " "
                        + e.status()
                        + " "
                        + e.exitCode()
                        + " "
                        + e.stoppedBy())
            .toList());
    assertTrue(sent.stream().allMatch(e -> e.at() != null), sent.toString());
    // The kill's own ENDED path is untouched, and still only for the kill.
    assertEquals(List.of("killed=" + AgentEvent.KILLED + ":137"), reported);
  }

  // --- qits-1779: a claude harness's remote-control session, read back at its exit ---

  private static final String SESSION = "5f0c1a7e-0000-4000-8000-000000000001";
  private static final String CSE = "cse_01CiRkJvCf85w2aC9w8b2LHS";

  @TempDir Path claudeConfig;

  /** A watch over a claude config dir, every command agent-1's, sending frames to {@code sent}. */
  private CommandLifecycleService remoteLifecycle(List<AgentHarnessExit> sent) {
    AgentKillWatch watch =
        new AgentKillWatch(
            store,
            new CgroupMemory(cgroup.resolve("absent")),
            (id, event, code, message) -> reported.add(id + "=" + event + ":" + code),
            id -> "sign-in".equals(id) ? null : new AgentKillWatch.Owner("agent-1", null),
            sent::add,
            claudeConfig);
    return new CommandLifecycleService(store, watch::commandsChanged);
  }

  /** A command launched as {@link #SESSION}, the way the harness library pins a fresh session. */
  private void launchAs(CommandLifecycleService lifecycle, String id, String agentType) {
    lifecycle.createRunning(
        "feature",
        "deadbeef",
        null,
        "agent",
        "claude",
        true,
        CommandKind.TERMINAL,
        id,
        new AgentSessionRef(SESSION, AgentSessionSource.PINNED, null, null, Instant.now()),
        agentType);
  }

  /** The remote-control log the launch passed to {@code --debug-file}, with noise around it. */
  private Path remoteLog(String archive) throws IOException {
    Path log = RemoteControlLog.resolve(claudeConfig, SESSION);
    Files.createDirectories(log.getParent());
    Files.writeString(
        log,
        "2026-10-31T10:00:00Z [DEBUG] Loading settings\n"
            + "2026-10-31T10:00:01Z [DEBUG] [remote-bridge] Created session "
            + CSE
            + "\n"
            + "2026-10-31T10:00:02Z [DEBUG] Rendering 4096 lines of TUI\n"
            + "2026-10-31T10:05:00Z [DEBUG] [code-session] Archive "
            + CSE
            + " status="
            + archive
            + "\n"
            + "2026-10-31T10:05:00Z [DEBUG] [remote-bridge] Torn down (archive="
            + archive
            + ")\n"
            + "2026-10-31T10:05:01Z [DEBUG] Flushing telemetry\n");
    return log;
  }

  /**
   * The WARN lines {@link AgentKillWatch} logs while {@code body} runs, caught through JUL, which
   * JBoss Logging delegates to outside a Quarkus boot.
   */
  private static List<String> warnings(ThrowingRunnable body) throws IOException {
    List<String> warned = new ArrayList<>();
    Logger logger = Logger.getLogger(AgentKillWatch.class.getName());
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
              Object[] params = record.getParameters();
              warned.add(
                  params == null
                      ? record.getMessage()
                      : String.format(record.getMessage(), params));
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      body.run();
    } finally {
      logger.removeHandler(handler);
    }
    return warned;
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws IOException;
  }

  @Test
  void anArchivedRemoteSessionRidesTheExitFrameWithNoWarning() throws IOException {
    List<AgentHarnessExit> sent = new ArrayList<>();
    CommandLifecycleService lifecycle = remoteLifecycle(sent);
    launchAs(lifecycle, "terminal", "claude");
    remoteLog("200");

    List<String> warned = warnings(() -> lifecycle.markTerminated("terminal", 143));

    assertEquals(1, sent.size(), sent.toString());
    assertEquals(CSE, sent.get(0).remoteSession());
    assertEquals("200", sent.get(0).remoteArchive());
    assertTrue(warned.isEmpty(), warned.toString());
    assertNull(
        AgentKillWatch.notArchivedLine(
            new AgentKillWatch.Owner("agent-1", null), new RemoteControlLog.Outcome(CSE, "200")));
    assertTrue(
        AgentKillWatch.exitLine(
                store.find("terminal").orElseThrow(),
                new AgentKillWatch.Owner("agent-1", null),
                new RemoteControlLog.Outcome(CSE, "200"))
            .endsWith(", claude.ai remote session " + CSE + " archive=200"));
  }

  @Test
  void aRemoteSessionWhoseArchiveFailedIsAWarningAndStillRidesTheFrame() throws IOException {
    List<AgentHarnessExit> sent = new ArrayList<>();
    CommandLifecycleService lifecycle = remoteLifecycle(sent);
    launchAs(lifecycle, "terminal", "claude");
    remoteLog("401");

    List<String> warned = warnings(() -> lifecycle.markTerminated("terminal", 143));

    assertEquals(CSE, sent.get(0).remoteSession());
    assertEquals("401", sent.get(0).remoteArchive());
    String expected =
        "agent agent-1's claude.ai remote session " + CSE + " was not archived (archive=401)";
    assertEquals(
        expected,
        AgentKillWatch.notArchivedLine(
            new AgentKillWatch.Owner("agent-1", null), new RemoteControlLog.Outcome(CSE, "401")));
    assertTrue(
        warned.stream().anyMatch(line -> line.contains(expected)), "the WARN: " + warned);
  }

  @Test
  void aLogThatRecordsNoArchiveAtAllIsTheNoneWordAndAWarning() throws IOException {
    // The harness ended on EOF or a KILL without ever being asked to archive.
    List<AgentHarnessExit> sent = new ArrayList<>();
    CommandLifecycleService lifecycle = remoteLifecycle(sent);
    launchAs(lifecycle, "terminal", "claude");
    Path log = RemoteControlLog.resolve(claudeConfig, SESSION);
    Files.createDirectories(log.getParent());
    Files.writeString(log, "x [DEBUG] [remote-bridge] Created session " + CSE + "\n");

    lifecycle.markExited("terminal", 137);

    assertEquals(CSE, sent.get(0).remoteSession());
    assertEquals(AgentHarnessExit.NO_ARCHIVE, sent.get(0).remoteArchive());
    assertEquals(RemoteControlLog.NO_ARCHIVE, AgentHarnessExit.NO_ARCHIVE, "one word, two homes");
  }

  @Test
  void noRemoteLogIsNoRemoteFieldsAndNoWarning() throws IOException {
    // Remote control off: the launch passed no --debug-file, so there is nothing to read.
    List<AgentHarnessExit> sent = new ArrayList<>();
    CommandLifecycleService lifecycle = remoteLifecycle(sent);
    launchAs(lifecycle, "terminal", "claude");

    List<String> warned = warnings(() -> lifecycle.markExited("terminal", 0));

    assertEquals(1, sent.size(), sent.toString());
    assertNull(sent.get(0).remoteSession());
    assertNull(sent.get(0).remoteArchive());
    assertTrue(warned.isEmpty(), warned.toString());
    assertFalse(
        AgentKillWatch.exitLine(
                store.find("terminal").orElseThrow(), new AgentKillWatch.Owner("agent-1", null))
            .contains("remote"));
  }

  @Test
  void theLogIsCompactedToItsBridgeLinesOnceTheHarnessHasExited() throws IOException {
    List<AgentHarnessExit> sent = new ArrayList<>();
    CommandLifecycleService lifecycle = remoteLifecycle(sent);
    launchAs(lifecycle, "terminal", "claude");
    Path log = remoteLog("200");
    launchAs(lifecycle, "running", "claude"); // a change while it runs reads nothing yet
    assertTrue(Files.readString(log).contains("Rendering 4096 lines"), "untouched while running");

    lifecycle.markTerminated("terminal", 143);

    List<String> left = Files.readAllLines(log);
    assertEquals(3, left.size(), left.toString());
    assertTrue(left.stream().allMatch(line -> line.contains("[remote-bridge]")
        || line.contains("[code-session]")), left.toString());
  }

  @Test
  void onlyAClaudeCommandAnAgentOfThisDaemonRanIsRead() throws IOException {
    // A kimi harness has no remote control; the sign-in terminal is no agent's. Both would find the
    // same log here, so reading nothing proves neither looked.
    List<AgentHarnessExit> sent = new ArrayList<>();
    CommandLifecycleService lifecycle = remoteLifecycle(sent);
    launchAs(lifecycle, "kimi", "kimi");
    launchAs(lifecycle, "sign-in", "claude");
    Path log = remoteLog("401");
    long before = Files.size(log);

    lifecycle.markExited("kimi", 0);
    lifecycle.markExited("sign-in", 0);

    assertEquals(2, sent.size(), sent.toString());
    assertTrue(sent.stream().allMatch(e -> e.remoteSession() == null && e.remoteArchive() == null));
    assertEquals(before, Files.size(log), "not compacted");
  }

  @Test
  void theLaunchSessionIsTheFirstOneAndOnlyAFileNameSafeId() {
    CommandLifecycleService lifecycle = lifecycle(cgroup);
    launchAs(lifecycle, "terminal", "claude");
    // An in-TUI /resume later: the log keeps the name it was launched under.
    lifecycle.recordAgentSessionReport("terminal", "switched-later", null);
    assertEquals(SESSION, AgentKillWatch.launchSession(store.find("terminal").orElseThrow()));
    launch(lifecycle, "kimi-unpinned", "kimi");
    assertNull(AgentKillWatch.launchSession(store.find("kimi-unpinned").orElseThrow()));
    lifecycle.createRunning(
        "feature", "deadbeef", null, "agent", "claude", true, CommandKind.TERMINAL, "escape",
        new AgentSessionRef("../../etc", AgentSessionSource.RESUMED, null, null, Instant.now()),
        "claude");
    assertNull(AgentKillWatch.launchSession(store.find("escape").orElseThrow()));
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
