package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.ServiceDecl;
import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.WebViewDecl;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.ServiceTransition;
import java.io.File;
import java.nio.file.Files;
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
  private volatile List<ServiceDecl> decls = List.of();
  private ServiceSupervisor supervisor;

  private ServiceSupervisor supervisor() {
    if (supervisor == null) {
      supervisor = supervisor("/workspaces/service/42");
    }
    return supervisor;
  }

  private ServiceSupervisor supervisor(String serviceProxyBase) {
    return new ServiceSupervisor(
        "ws-1",
        workspace,
        events::add,
        () -> decls, /* readyGrace */
        400, /* backoffInit */
        50, /* backoffMax */
        200, /* stopGrace */
        1000,
        serviceProxyBase);
  }

  @AfterEach
  void cleanup() {
    if (supervisor != null) {
      for (ServiceDecl d : decls) {
        supervisor.signal(d.name(), "KILL");
      }
      supervisor.close();
    }
  }

  private static ServiceDecl svc(
      String name, String start, String readyPattern, String policy, Integer maxRestarts) {
    return new ServiceDecl(
        name,
        name,
        null,
        start,
        readyPattern,
        true,
        policy,
        maxRestarts,
        "TERM",
        Map.of(),
        null,
        List.of());
  }

  private List<ServiceTransition> statesFor(String id) {
    return events.stream()
        .filter(m -> m instanceof ServiceTransition de && id.equals(de.id()))
        .map(m -> (ServiceTransition) m)
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

    awaitState("dev", ServiceTransition.State.READY, 8000);
    List<ServiceTransition> states = statesFor("dev");
    assertEquals(ServiceTransition.State.STARTING, states.get(0).state());
    assertTrue(
        events.stream()
            .anyMatch(m -> m instanceof CommandChunk c && "service:dev".equals(c.correlationId())),
        "expected service:dev output chunks");

    supervisor().signal("dev", "TERM");
    awaitState("dev", ServiceTransition.State.STOPPED, 8000);
  }

  @Test
  void becomesReadyAfterGraceWithoutPattern() {
    decls = List.of(svc("dev", "sleep 30", null, "NEVER", 0));
    supervisor().startAutoStart();

    awaitState("dev", ServiceTransition.State.READY, 8000);

    supervisor().signal("dev", "TERM");
    awaitState("dev", ServiceTransition.State.STOPPED, 8000);
  }

  @Test
  void crashLoopRestartsThenCrashes() {
    decls = List.of(svc("boom", "exit 3", null, "ON_FAILURE", 2));
    supervisor().startAutoStart();

    awaitState("boom", ServiceTransition.State.CRASHED, 15000);
    long restarting =
        statesFor("boom").stream()
            .filter(e -> ServiceTransition.State.RESTARTING.equals(e.state()))
            .count();
    assertEquals(2, restarting, "expected exactly maxRestarts RESTARTING events");
  }

  @Test
  void cleanExitWithoutRestartStops() {
    decls = List.of(svc("once", "true", null, "NEVER", 0));
    supervisor().startAutoStart();

    awaitState("once", ServiceTransition.State.STOPPED, 8000);
    assertTrue(
        statesFor("once").stream()
            .noneMatch(e -> ServiceTransition.State.CRASHED.equals(e.state())),
        "a clean exit must not be reported CRASHED");
  }

  @Test
  void groupKillReapsForkedChild() {
    // A backgrounded fork (the Quarkus-dev forked-JVM case, in miniature) with a distinctive
    // marker.
    decls = List.of(svc("forky", "sleep 4242 & sleep 4242", null, "NEVER", 0));
    supervisor().startAutoStart();

    awaitState("forky", ServiceTransition.State.STARTING, 8000);
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
    awaitState("dev", ServiceTransition.State.STARTING, 8000);

    int before = statesFor("dev").size();
    supervisor().reportAll();
    awaitCondition(() -> statesFor("dev").size() > before, 3000, () -> "a re-reported state event");

    supervisor().signal("dev", "TERM");
  }

  @Test
  void aWebViewableServiceKeepsItsPublicBaseAcrossARestart() throws Exception {
    // N3: the verbatim web-view proxy only works while the dev server serves under
    // /workspaces/service/{rowId}/{serviceId}/, and spawn is the only place it learns that base.
    // Every spawn — first start and crash-restart alike — must carry it, keyed by the declared
    // ID (the proxy path segment), not the display name, with the declared base-path appended.
    decls =
        List.of(
            new ServiceDecl(
                "web-id",
                "Web UI",
                null,
                "echo \"$QITS_PUBLIC_BASE\" >> pb.out; exit 1",
                null,
                true,
                "ON_FAILURE",
                2,
                "TERM",
                Map.of(),
                new WebViewDecl(4200, null, "app"),
                List.of()));
    supervisor().startAutoStart();

    awaitState("Web UI", ServiceTransition.State.CRASHED, 15000);
    List<String> bases = Files.readAllLines(workspace.toPath().resolve("pb.out"));
    assertEquals(3, bases.size(), "the initial spawn plus two restarts each wrote their base");
    for (String base : bases) {
      assertEquals("/workspaces/service/42/web-id/app/", base);
    }
  }

  @Test
  void theComputedPublicBaseWinsOverAConfigDeclaredOne() throws Exception {
    // A QITS_PUBLIC_BASE in the file cannot know the workspace's row id, so it can only be stale;
    // the spawn-time computation overrides it rather than merging.
    decls =
        List.of(
            new ServiceDecl(
                "web",
                "web",
                null,
                "echo \"${QITS_PUBLIC_BASE:-UNSET}\" > pb-once.out; true",
                null,
                true,
                "NEVER",
                0,
                "TERM",
                Map.of("QITS_PUBLIC_BASE", "/stale/"),
                new WebViewDecl(4200, null, null),
                List.of()));
    supervisor().startAutoStart();

    awaitState("web", ServiceTransition.State.STOPPED, 8000);
    assertEquals(
        "/workspaces/service/42/web/",
        Files.readString(workspace.toPath().resolve("pb-once.out")).strip());
  }

  @Test
  void aWebViewableServiceWithoutAProxyBaseWarnsAndSpawnsWithoutOne() throws Exception {
    decls =
        List.of(
            new ServiceDecl(
                "web",
                "web",
                null,
                "echo \"${QITS_PUBLIC_BASE:-UNSET}\" > pb-unset.out; true",
                null,
                true,
                "NEVER",
                0,
                "TERM",
                Map.of(),
                new WebViewDecl(4200, null, null),
                List.of()));
    supervisor = supervisor(""); // nothing fronting the daemon: no base to hand down
    supervisor.startAutoStart();

    awaitState("web", ServiceTransition.State.STOPPED, 8000);
    assertEquals("UNSET", Files.readString(workspace.toPath().resolve("pb-unset.out")).strip());
    assertTrue(
        events.stream()
            .anyMatch(
                m ->
                    m instanceof DaemonLog log
                        && "WARN".equals(log.level())
                        && log.message().contains("service-proxy-base")),
        "a web view with no proxy base to serve under must say so");
  }
}
