package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.DaemonDecl;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonEvent;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Container-free coverage of the in-container service supervisor: it forks real {@code setsid bash}
 * processes in a temp working dir, so ready detection, the restart policy / backoff / max-restarts
 * decision, stop-signalling, session-group kill of an escaped fork, and the reconnect re-report are
 * all exercised end-to-end (docs/epics/qits-workspace-daemon/ Part 4). The extended real-docker IT
 * drives the same {@link ServiceSupervisor} under a real {@code quarkus:dev}.
 */
class ServiceSupervisorTest {

  @TempDir File workspace;

  private final CopyOnWriteArrayList<DaemonMessage> events = new CopyOnWriteArrayList<>();
  private volatile List<DaemonDecl> decls = List.of();
  private ServiceSupervisor supervisor;

  private ServiceSupervisor supervisor() {
    if (supervisor == null) {
      supervisor =
          new ServiceSupervisor(
              "ws-1",
              workspace,
              events::add,
              () -> decls, /* readyGrace */
              400, /* backoffInit */
              50, /* backoffMax */
              200, /* stopGrace */
              1000);
    }
    return supervisor;
  }

  @AfterEach
  void cleanup() {
    if (supervisor != null) {
      for (DaemonDecl d : decls) {
        supervisor.signal(d.name(), "KILL");
      }
      supervisor.close();
    }
  }

  private static DaemonDecl svc(
      String name, String start, String readyPattern, String policy, Integer maxRestarts) {
    return new DaemonDecl(
        name,
        null,
        start,
        readyPattern,
        null,
        true,
        policy,
        maxRestarts,
        "TERM",
        Map.of(),
        null,
        List.of(),
        List.of(),
        List.of());
  }

  private List<DaemonEvent> statesFor(String id) {
    return events.stream()
        .filter(m -> m instanceof DaemonEvent de && id.equals(de.id()))
        .map(m -> (DaemonEvent) m)
        .collect(Collectors.toList());
  }

  private void awaitState(String id, String state, long timeoutMs) {
    awaitCondition(
        () -> statesFor(id).stream().anyMatch(e -> state.equals(e.state())),
        timeoutMs,
        () -> "state " + state + " for " + id + "; saw " + statesFor(id));
  }

  private static void awaitCondition(
      BooleanSupplier condition, long timeoutMs, java.util.function.Supplier<String> what) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("timed out waiting for " + what.get());
  }

  private static int pgrepCount(String pattern) {
    try {
      Process p = new ProcessBuilder("pgrep", "-f", pattern).start();
      String out =
          new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      p.waitFor();
      return (int) out.lines().filter(l -> !l.isBlank()).count();
    } catch (Exception e) {
      return -1;
    }
  }

  @Test
  void startsAndBecomesReadyOnPattern() {
    decls = List.of(svc("dev", "echo listening; sleep 30", "listening", "NEVER", 0));
    supervisor().startAutoStart();

    awaitState("dev", DaemonEvent.State.READY, 8000);
    List<DaemonEvent> states = statesFor("dev");
    assertEquals(DaemonEvent.State.STARTING, states.get(0).state());
    assertTrue(
        events.stream()
            .anyMatch(m -> m instanceof CommandChunk c && "service:dev".equals(c.correlationId())),
        "expected service:dev output chunks");

    supervisor().signal("dev", "TERM");
    awaitState("dev", DaemonEvent.State.STOPPED, 8000);
  }

  @Test
  void becomesReadyAfterGraceWithoutPattern() {
    decls = List.of(svc("dev", "sleep 30", null, "NEVER", 0));
    supervisor().startAutoStart();

    awaitState("dev", DaemonEvent.State.READY, 8000);

    supervisor().signal("dev", "TERM");
    awaitState("dev", DaemonEvent.State.STOPPED, 8000);
  }

  @Test
  void crashLoopRestartsThenCrashes() {
    decls = List.of(svc("boom", "exit 3", null, "ON_FAILURE", 2));
    supervisor().startAutoStart();

    awaitState("boom", DaemonEvent.State.CRASHED, 15000);
    long restarting =
        statesFor("boom").stream()
            .filter(e -> DaemonEvent.State.RESTARTING.equals(e.state()))
            .count();
    assertEquals(2, restarting, "expected exactly maxRestarts RESTARTING events");
  }

  @Test
  void cleanExitWithoutRestartStops() {
    decls = List.of(svc("once", "true", null, "NEVER", 0));
    supervisor().startAutoStart();

    awaitState("once", DaemonEvent.State.STOPPED, 8000);
    assertTrue(
        statesFor("once").stream().noneMatch(e -> DaemonEvent.State.CRASHED.equals(e.state())),
        "a clean exit must not be reported CRASHED");
  }

  @Test
  void groupKillReapsForkedChild() {
    // A backgrounded fork (the Quarkus-dev forked-JVM case, in miniature) with a distinctive
    // marker.
    decls = List.of(svc("forky", "sleep 4242 & sleep 4242", null, "NEVER", 0));
    supervisor().startAutoStart();

    awaitState("forky", DaemonEvent.State.STARTING, 8000);
    awaitCondition(() -> pgrepCount("sleep 4242") >= 2, 8000, () -> "the forked sleeps to appear");

    supervisor().signal("forky", "TERM");
    awaitCondition(
        () -> pgrepCount("sleep 4242") == 0,
        8000,
        () -> "the whole session (incl. the fork) to be reaped without /proc");
  }

  @Test
  void reportAllReReportsRunningState() {
    decls = List.of(svc("dev", "sleep 30", "listening", "NEVER", 0));
    supervisor().startAutoStart();
    awaitState("dev", DaemonEvent.State.STARTING, 8000);

    int before = statesFor("dev").size();
    supervisor().reportAll();
    awaitCondition(() -> statesFor("dev").size() > before, 3000, () -> "a re-reported state event");

    supervisor().signal("dev", "TERM");
  }
}
