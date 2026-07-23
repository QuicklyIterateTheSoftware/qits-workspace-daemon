package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.BootstrapDecl;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapOutcome;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapStep;
import eu.wohlben.qits.workspacedaemon.protocol.Bootstrapped;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Container-free coverage of the bootstrap chain the daemon self-runs on boot: it forks real {@code
 * bash} processes in a temp working dir, so ordering, {@code check}-skip, fail-fast abort, and
 * timeout-terminate are all exercised end-to-end (docs/epics/qits-workspace-daemon/ Part 3). The
 * backend/extended tests drive the same {@link BootstrapRunner} over a real socket.
 */
class BootstrapRunnerTest {

  private static final long GENEROUS_TIMEOUT_MS = 30_000;

  private static BootstrapDecl step(String name, String execute) {
    return new BootstrapDecl(name, null, execute, null, Map.of());
  }

  private static BootstrapDecl step(String name, String execute, String check) {
    return new BootstrapDecl(name, null, execute, check, Map.of());
  }

  private static List<BootstrapOutcome> outcomes(List<DaemonMessage> messages) {
    return messages.stream()
        .filter(m -> m instanceof BootstrapOutcome)
        .map(m -> (BootstrapOutcome) m)
        .collect(Collectors.toList());
  }

  private static Bootstrapped terminal(List<DaemonMessage> messages) {
    return messages.stream()
        .filter(m -> m instanceof Bootstrapped)
        .map(m -> (Bootstrapped) m)
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  @Test
  void runsChainInOrderReportingSucceeded(@TempDir File dir) {
    File log = new File(dir, "order.txt");
    List<DaemonMessage> emitted = new ArrayList<>();
    BootstrapRunner.run(
        "ws-1",
        List.of(
            step("install", "echo install >> " + log.getAbsolutePath()),
            step("seed", "echo seed >> " + log.getAbsolutePath())),
        null,
        dir,
        GENEROUS_TIMEOUT_MS,
        emitted::add);

    List<BootstrapOutcome> outcomes = outcomes(emitted);
    assertEquals(
        List.of("install", "seed"), outcomes.stream().map(BootstrapOutcome::name).toList());
    assertTrue(
        outcomes.stream().allMatch(o -> BootstrapOutcome.Result.SUCCEEDED.equals(o.outcome())));
    assertTrue(terminal(emitted).ok());
    assertEquals("install\nseed\n", readOrEmpty(log));
  }

  @Test
  void checkNonZeroSkipsExecute(@TempDir File dir) {
    File marker = new File(dir, "ran.txt");
    List<DaemonMessage> emitted = new ArrayList<>();
    BootstrapRunner.run(
        "ws-1",
        List.of(step("seed", "touch " + marker.getAbsolutePath(), "exit 1")),
        null,
        dir,
        GENEROUS_TIMEOUT_MS,
        emitted::add);

    BootstrapOutcome only = outcomes(emitted).get(0);
    assertEquals(BootstrapOutcome.Result.SKIPPED, only.outcome());
    assertFalse(marker.exists(), "execute must not run when check is non-zero");
    assertTrue(
        emitted.stream()
            .anyMatch(
                m -> m instanceof BootstrapStep s && BootstrapStep.Phase.SKIP.equals(s.phase())));
    assertTrue(terminal(emitted).ok());
  }

  @Test
  void checkZeroRunsExecute(@TempDir File dir) {
    File marker = new File(dir, "ran.txt");
    List<DaemonMessage> emitted = new ArrayList<>();
    BootstrapRunner.run(
        "ws-1",
        List.of(step("seed", "touch " + marker.getAbsolutePath(), "exit 0")),
        null,
        dir,
        GENEROUS_TIMEOUT_MS,
        emitted::add);

    assertEquals(BootstrapOutcome.Result.SUCCEEDED, outcomes(emitted).get(0).outcome());
    assertTrue(marker.exists(), "execute must run when check is zero");
  }

  @Test
  void failFastAbortsRemainingSteps(@TempDir File dir) {
    File marker = new File(dir, "second.txt");
    List<DaemonMessage> emitted = new ArrayList<>();
    BootstrapRunner.run(
        "ws-1",
        List.of(step("install", "exit 7"), step("seed", "touch " + marker.getAbsolutePath())),
        null,
        dir,
        GENEROUS_TIMEOUT_MS,
        emitted::add);

    List<BootstrapOutcome> outcomes = outcomes(emitted);
    assertEquals(1, outcomes.size(), "the failed step aborts the chain — no second outcome");
    assertEquals(BootstrapOutcome.Result.FAILED, outcomes.get(0).outcome());
    assertEquals(7, outcomes.get(0).exitCode());
    assertFalse(marker.exists(), "the step after a failure must not run");
    assertFalse(terminal(emitted).ok());
  }

  @Test
  void timeoutTerminatesAndFails(@TempDir File dir) {
    List<DaemonMessage> emitted = new CopyOnWriteArrayList<>();
    BootstrapRunner.run("ws-1", List.of(step("hang", "sleep 30")), null, dir, 300, emitted::add);

    BootstrapOutcome only = outcomes(emitted).get(0);
    assertEquals(BootstrapOutcome.Result.FAILED, only.outcome());
    assertEquals(124, only.exitCode());
    assertFalse(terminal(emitted).ok());
  }

  @Test
  void emptyChainReportsOkWithNoSteps(@TempDir File dir) {
    List<DaemonMessage> emitted = new ArrayList<>();
    BootstrapRunner.run("ws-1", List.of(), null, dir, GENEROUS_TIMEOUT_MS, emitted::add);

    assertTrue(outcomes(emitted).isEmpty());
    assertTrue(terminal(emitted).ok());
    assertEquals(1, emitted.stream().filter(m -> m instanceof Bootstrapped).count());
  }

  @Test
  void singleNamedStepRunsOnlyThatOne(@TempDir File dir) {
    File other = new File(dir, "other.txt");
    File target = new File(dir, "target.txt");
    List<DaemonMessage> emitted = new ArrayList<>();
    BootstrapRunner.run(
        "ws-1",
        List.of(
            step("install", "touch " + other.getAbsolutePath()),
            step("seed", "touch " + target.getAbsolutePath())),
        "seed",
        dir,
        GENEROUS_TIMEOUT_MS,
        emitted::add);

    List<BootstrapOutcome> outcomes = outcomes(emitted);
    assertEquals(List.of("seed"), outcomes.stream().map(BootstrapOutcome::name).toList());
    assertFalse(other.exists(), "the un-named step must not run");
    assertTrue(target.exists());
    assertTrue(terminal(emitted).ok());
  }

  @Test
  void blankExecuteFailsLoudly(@TempDir File dir) {
    List<DaemonMessage> emitted = new ArrayList<>();
    BootstrapRunner.run(
        "ws-1", List.of(step("broken", "  ")), null, dir, GENEROUS_TIMEOUT_MS, emitted::add);

    assertEquals(BootstrapOutcome.Result.FAILED, outcomes(emitted).get(0).outcome());
    assertFalse(terminal(emitted).ok());
  }

  @Test
  void streamsStepOutputTaggedWithBootstrapCorrelation(@TempDir File dir) {
    List<DaemonMessage> emitted = new ArrayList<>();
    BootstrapRunner.run(
        "ws-1",
        List.of(step("install", "echo building")),
        null,
        dir,
        GENEROUS_TIMEOUT_MS,
        emitted::add);

    String out =
        emitted.stream()
            .filter(m -> m instanceof CommandChunk)
            .map(m -> (CommandChunk) m)
            .filter(c -> "bootstrap:install".equals(c.correlationId()))
            .map(CommandChunk::text)
            .collect(Collectors.joining());
    assertTrue(out.contains("building"), out);
  }

  private static String readOrEmpty(File file) {
    try {
      return file.isFile() ? Files.readString(file.toPath()) : "";
    } catch (Exception e) {
      return "";
    }
  }
}
