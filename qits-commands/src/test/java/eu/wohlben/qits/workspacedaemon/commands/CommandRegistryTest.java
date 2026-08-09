package eu.wohlben.qits.workspacedaemon.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry against real processes in a real terminal. This is the test that the host's
 * equivalent could not have: there, every spawn was a {@code docker exec} and the suite could only
 * assert on the argv it would have run. Here the process is a local child, so the whole path —
 * spawn, terminal, capture, broadcast, exit, group termination — runs for real without docker.
 */
@EnabledOnOs(OS.LINUX)
class CommandRegistryTest {

  /** Collects everything broadcast to an attached client. */
  private static final class RecordingSink implements CommandOutputSink {
    private final StringBuilder received = new StringBuilder();
    private volatile boolean open = true;

    @Override
    public synchronized void write(String data) {
      received.append(data);
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    synchronized String text() {
      return received.toString();
    }
  }

  /** Captures framed log lines the way {@code CommandLogService} would. */
  private record CapturedLine(long sequence, LogChannel channel, String content) {}

  private static final class RecordingLog implements CommandLogWriter {
    private final List<CapturedLine> lines = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void append(
        String commandId, long sequence, LogChannel channel, String content, Instant timestamp) {
      lines.add(new CapturedLine(sequence, channel, content));
    }

    List<String> contentOn(LogChannel channel) {
      synchronized (lines) {
        return lines.stream()
            .filter(line -> line.channel() == channel)
            .map(CapturedLine::content)
            .toList();
      }
    }
  }

  @Test
  void runsAScriptAndReportsItsExitCode(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    AtomicInteger code = new AtomicInteger(Integer.MIN_VALUE);
    RecordingSink sink = new RecordingSink();

    registry.spawn(
        "c1",
        "echo marker-one; exit 7",
        Map.of(),
        (id, exitCode, manual) -> {
          code.set(exitCode);
          exited.countDown();
        },
        null,
        sink);

    assertTrue(exited.await(30, TimeUnit.SECONDS), "the command should have exited");
    assertEquals(7, code.get());
    assertTrue(sink.text().contains("marker-one"), "expected output, got: " + sink.text());
  }

  @Test
  void theScriptRunsInTheWorkspaceRoot(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("sentinel.txt"), "here");
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingSink sink = new RecordingSink();

    registry.spawn("c2", "cat sentinel.txt", Map.of(), (id, c, m) -> exited.countDown(), null, sink);

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    assertTrue(sink.text().contains("here"), "expected the file's content, got: " + sink.text());
  }

  @Test
  void theLaunchEnvironmentReachesTheProcess(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingSink sink = new RecordingSink();

    registry.spawn(
        "c3",
        "echo \"[$QITS_TEST_VALUE]\"",
        Map.of("QITS_TEST_VALUE", "passed-through"),
        (id, c, m) -> exited.countDown(),
        null,
        sink);

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    assertTrue(sink.text().contains("[passed-through]"), "got: " + sink.text());
  }

  @Test
  void theProcessSeesAControllingTerminal(@TempDir Path workspace) throws Exception {
    // The reason for --ctty: without a controlling terminal `test -t 1` fails and every
    // full-screen TUI — which is what a coding agent in TERMINAL mode is — drops to line mode.
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingSink sink = new RecordingSink();

    registry.spawn(
        "c4",
        "test -t 1 && echo IS-TTY || echo NOT-TTY",
        Map.of(),
        (id, c, m) -> exited.countDown(),
        null,
        sink);

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    assertTrue(sink.text().contains("IS-TTY"), "expected a tty, got: " + sink.text());
  }

  @Test
  void keystrokesReachTheProcessAndAreCapturedOnStdin(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingSink sink = new RecordingSink();
    RecordingLog log = new RecordingLog();

    registry.spawn(
        "c5", "read -r answer; echo \"got:$answer\"", Map.of(), (id, c, m) -> exited.countDown(), log, sink);

    // Give the shell a moment to reach the read before typing at it.
    waitUntil(() -> registry.isRunning("c5"), 10_000);
    registry.input("c5", "ping\n".getBytes(StandardCharsets.UTF_8));

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    assertTrue(sink.text().contains("got:ping"), "expected the echoed answer, got: " + sink.text());
    assertTrue(log.contentOn(LogChannel.STDIN).contains("ping"), "stdin should be captured");
  }

  @Test
  void outputIsFramedIntoLogLines(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingLog log = new RecordingLog();

    registry.spawn(
        "c6", "printf 'alpha\\nbeta\\n'", Map.of(), (id, c, m) -> exited.countDown(), log);

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    List<String> output = log.contentOn(LogChannel.OUTPUT);
    assertTrue(output.contains("alpha"), "got: " + output);
    assertTrue(output.contains("beta"), "got: " + output);
  }

  @Test
  void aLateAttachReplaysTheScrollback(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);

    // No initial sink: everything this prints lands only in the ring.
    registry.spawn(
        "c7", "echo replay-me; read -r ignored", Map.of(), (id, c, m) -> exited.countDown(), null);
    waitUntil(() -> registry.isRunning("c7"), 10_000);

    RecordingSink late = new RecordingSink();
    waitUntil(
        () -> {
          late.received.setLength(0);
          return registry.attach("c7", late) && late.text().contains("replay-me");
        },
        10_000);
    assertTrue(late.text().contains("replay-me"), "expected scrollback replay, got: " + late.text());

    registry.input("c7", "\n".getBytes(StandardCharsets.UTF_8));
    assertTrue(exited.await(30, TimeUnit.SECONDS));
  }

  @Test
  void terminateKillsTheWholeProcessGroup(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    AtomicReference<Boolean> manual = new AtomicReference<>();
    Path childPid = workspace.resolve("child.pid");

    // A background child of the shell: killing only the shell would leave it running, which is
    // exactly the orphan the pid-file/pgid dance exists to prevent.
    registry.spawn(
        "c8",
        "sleep 300 & echo $! > " + childPid + "; wait",
        Map.of(),
        (id, c, m) -> {
          manual.set(m);
          exited.countDown();
        },
        null);

    waitUntil(() -> Files.exists(childPid) && !readQuietly(childPid).isBlank(), 15_000);
    long grandchild = Long.parseLong(readQuietly(childPid).trim());

    assertTrue(registry.terminate("c8"), "terminate should find the session");
    assertTrue(exited.await(30, TimeUnit.SECONDS), "the command should have ended");
    assertEquals(Boolean.TRUE, manual.get(), "the exit should be reported as manual");
    waitUntil(() -> isNoLongerRunning(grandchild), 15_000);
    assertTrue(
        isNoLongerRunning(grandchild),
        "the backgrounded grandchild should have been killed with the group, but "
            + describe(grandchild));
  }

  @Test
  void isRunningIsFalseForAnUnknownCommand(@TempDir Path workspace) {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    assertFalse(registry.isRunning("never-launched"));
    assertFalse(registry.terminate("never-launched"));
    assertFalse(registry.input("never-launched", new byte[] {1}));
    assertFalse(registry.resize("never-launched", 80, 24));
  }

  /**
   * True when the pid no longer names a running process: either the kernel has dropped it, or it
   * is a zombie waiting to be collected.
   *
   * <p>Why {@code ProcessHandle.of(pid).isEmpty()} is not enough on its own. A killed process stays
   * in the process table as a zombie until its parent calls {@code wait()} for it. The grandchild
   * in this test is backgrounded by the session shell, so killing the group orphans it and the
   * kernel reparents it onto PID 1. PID 1 then decides how long the corpse lingers. On a developer
   * host PID 1 is systemd, which reaps orphans at once, so the pid vanishes and the plain
   * {@code ProcessHandle} check passes. In a CI step container PID 1 is whatever the step launched
   * — for this suite the Maven JVM — and a JVM never calls {@code wait()} for a process it did not
   * start. The zombie therefore stays forever, {@code /proc/<pid>} keeps answering, and
   * {@code ProcessHandle.of(pid)} keeps reporting the process as present.
   *
   * <p>That difference is a property of the container, not of the code under test. A zombie is
   * dead: it holds no memory, runs no code, and cannot be signalled. For the question this test
   * asks — did killing the session kill the backgrounded grandchild — gone and zombie are the same
   * answer, so the assertion must accept both.
   *
   * <p>Reading {@code /proc} is fair here. The whole class is {@code @EnabledOnOs(OS.LINUX)}, and
   * the registry under test needs PTYs and {@code setsid --ctty}, so there is no other platform to
   * serve.
   */
  private static boolean isNoLongerRunning(long pid) {
    if (ProcessHandle.of(pid).isEmpty()) {
      return true;
    }
    return "Z".equals(processState(pid));
  }

  /**
   * The single-letter process state from {@code /proc/<pid>/stat}, or {@code null} when there is no
   * such process.
   *
   * <p>The state is field 3, but field 2 is the executable name wrapped in parentheses and may
   * itself contain spaces and parentheses. Splitting the whole line on whitespace would therefore
   * pick the wrong field, so the scan starts after the last {@code ')'}.
   */
  private static String processState(long pid) {
    try {
      String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
      int endOfName = stat.lastIndexOf(')');
      if (endOfName < 0) {
        return null;
      }
      String[] fields = stat.substring(endOfName + 1).trim().split("\\s+");
      return fields.length > 0 && !fields[0].isEmpty() ? fields[0] : null;
    } catch (Exception e) {
      // No /proc entry at all: the process is gone, the strongest form of "not running".
      return null;
    }
  }

  /** What the pid looks like right now, so a failure says why it was judged still running. */
  private static String describe(long pid) {
    String state = processState(pid);
    return state == null
        ? "pid " + pid + " has no /proc entry"
        : "pid " + pid + " is still there in state " + state;
  }

  private static String readQuietly(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception e) {
      return "";
    }
  }

  private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(25);
    }
  }
}
