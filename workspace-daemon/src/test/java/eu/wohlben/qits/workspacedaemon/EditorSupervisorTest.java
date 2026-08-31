package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Container-free coverage of the web-editor supervisor: a fake {@code bin/openvscode-server} script
 * stands in for the real one, so the whole lifecycle — the argv the editor is launched with, the two
 * routes to {@code RUNNING}, the crash/backoff/budget path to {@code ENDED}, shutdown termination
 * and the reconnect re-report — runs against real processes, the way {@link ServiceSupervisorTest}
 * covers the service supervisor. No image with openvscode-server in it is needed to prove any of it.
 *
 * <p>The two cases worth naming are the ones the epic turns on. The <b>argv</b> test is the loopback
 * bind: an editor on {@code 0.0.0.0} would be an unauthenticated shell over an untrusted checkout,
 * reachable from every other workspace container, and the only place that decision is made is the
 * spawn. And the <b>absent editor</b> tests are the "zero behaviour change for a plain workspace"
 * promise stated as an assertion — not one frame, in either arrangement that can produce it.
 */
class EditorSupervisorTest {

  @TempDir File install;

  @TempDir File workspace;

  private final CopyOnWriteArrayList<DaemonMessage> events = new CopyOnWriteArrayList<>();

  private EditorSupervisor supervisor;

  @AfterEach
  void cleanup() {
    if (supervisor != null) {
      supervisor.close();
    }
  }

  /** Install a fake openvscode-server at {@code <install>/bin/openvscode-server}. */
  private void fakeEditor(String script) throws Exception {
    Path bin = install.toPath().resolve("bin");
    Files.createDirectories(bin);
    Path launcher = bin.resolve("openvscode-server");
    Files.writeString(launcher, "#!/bin/bash\n" + script + "\n");
    assertTrue(launcher.toFile().setExecutable(true), "the fake editor must be executable");
  }

  private EditorSupervisor supervisor(boolean enabled, long readyGraceMs) {
    return new EditorSupervisor(
        install,
        workspace,
        13339,
        enabled,
        events::add,
        readyGraceMs,
        /* backoffInitial */ 30,
        /* backoffMax */ 60,
        /* stopGrace */ 500);
  }

  private List<String> states() {
    return events.stream()
        .filter(m -> m instanceof EditorState)
        .map(m -> ((EditorState) m).state())
        .collect(Collectors.toList());
  }

  private void awaitState(String state, long timeoutMs) {
    awaitCondition(
        () -> states().contains(state), timeoutMs, () -> "state " + state + "; saw " + states());
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
  void anImageWithoutTheEditorSupervisesNothingAndAnnouncesNothing() throws Exception {
    // The plain-workspace case: the switch is on (a host that injects it unconditionally) but
    // /opt/openvscode-server is not in this image. Not one frame — the absence of EditorState IS
    // how the host knows there is no editor here, so an announcement would be a lie it acts on.
    supervisor =
        new EditorSupervisor(
            new File(install, "absent"), workspace, 13339, true, events::add, 200, 30, 60, 500);
    assertFalse(supervisor.start(), "no install directory means nothing to supervise");
    Thread.sleep(400);
    assertTrue(events.isEmpty(), "a workspace without an editor sends nothing: " + events);
  }

  @Test
  void anInstallDirectoryWithNoLauncherInItAnnouncesNothingEither() throws Exception {
    // A broken editor layer, not a plain workspace. It is still silent on the wire: a STARTING the
    // host waits on, followed by an ENDED from a spawn that could never work, is a worse account of
    // "this image is built wrong" than the WARN in the container log.
    supervisor = supervisor(true, 200);
    assertFalse(supervisor.start(), "an install directory without the launcher supervises nothing");
    Thread.sleep(400);
    assertTrue(events.isEmpty(), "nothing spawnable means nothing announced: " + events);
  }

  @Test
  void theSwitchOffSupervisesNothingEvenWithTheEditorInstalled() throws Exception {
    fakeEditor("touch " + workspace.getAbsolutePath() + "/launched; sleep 30");
    supervisor = supervisor(false, 200);
    assertFalse(supervisor.start(), "the switch off means nothing to supervise");
    Thread.sleep(400);
    assertTrue(events.isEmpty(), "a disabled editor sends nothing: " + events);
    assertFalse(
        new File(workspace, "launched").exists(), "a disabled editor must not be spawned at all");
  }

  @Test
  void theEditorIsLaunchedOnLoopbackWithItsConfiguredPortAndNoConnectionToken() throws Exception {
    // The security-relevant half of the spawn, asserted as literal argv: --host 127.0.0.1 is the
    // reason --without-connection-token is safe, and the port is what the tunnel's EDITOR target
    // resolves to. A change to any of the three has to come here first.
    fakeEditor("printf '%s\\n' \"$@\" > " + workspace.getAbsolutePath() + "/argv; sleep 30");
    supervisor = supervisor(true, 60_000);
    assertTrue(supervisor.start());

    awaitCondition(
        () -> new File(workspace, "argv").isFile(), 8000, () -> "the fake editor to record its argv");
    // The file is written by the spawned shell; give the write a moment to complete before reading.
    awaitCondition(
        () -> {
          try {
            return Files.readAllLines(workspace.toPath().resolve("argv")).size() == 5;
          } catch (Exception e) {
            return false;
          }
        },
        8000,
        () -> "five recorded arguments");
    assertLinesMatch(
        List.of("--host", "127.0.0.1", "--port", "13339", "--without-connection-token"),
        Files.readAllLines(workspace.toPath().resolve("argv")));
  }

  @Test
  void itRunsInTheCheckoutSoTerminalsAndTheOpenFolderStartThere() throws Exception {
    fakeEditor("pwd > " + workspace.getAbsolutePath() + "/cwd; sleep 30");
    supervisor = supervisor(true, 60_000);
    assertTrue(supervisor.start());

    awaitCondition(
        () -> new File(workspace, "cwd").isFile(), 8000, () -> "the fake editor to record its cwd");
    assertEquals(
        workspace.toPath().toRealPath().toString(),
        Path.of(Files.readString(workspace.toPath().resolve("cwd")).strip()).toRealPath().toString());
  }

  @Test
  void theReadyLineIsWhatTurnsStartingIntoRunning() throws Exception {
    // The grace is set far out of reach, so a RUNNING here can only have come from the marker.
    fakeEditor("echo 'Web UI available at http://localhost:13339/'; sleep 30");
    supervisor = supervisor(true, 60_000);
    assertTrue(supervisor.start());

    awaitState(EditorState.State.RUNNING, 8000);
    assertEquals(
        List.of(EditorState.State.STARTING, EditorState.State.RUNNING),
        states(),
        "one transition each, in order");
  }

  @Test
  void aSilentEditorBecomesRunningOnTheGraceInstead() throws Exception {
    // An openvscode-server that words its banner differently must still resolve to RUNNING, or the
    // splash never clears for an editor that is in fact serving. That is the whole reason there are
    // two routes to the state and not one.
    fakeEditor("sleep 30");
    supervisor = supervisor(true, 300);
    assertTrue(supervisor.start());

    awaitState(EditorState.State.RUNNING, 8000);
  }

  @Test
  void aCrashLoopRelaunchesAndThenEndsWhenTheBudgetIsSpent() throws Exception {
    // Never reaches RUNNING (the grace is out of reach), so the restart budget is never reset and
    // the loop terminates. ENDED is what lets the host's splash stop waiting instead of spinning
    // for the container's lifetime.
    fakeEditor("echo run >> " + workspace.getAbsolutePath() + "/runs; exit 1");
    supervisor = supervisor(true, 60_000);
    assertTrue(supervisor.start());

    awaitState(EditorState.State.ENDED, 15_000);
    assertEquals(
        List.of(EditorState.State.STARTING, EditorState.State.ENDED),
        states(),
        "a relaunch is not a transition: STARTING is reported once, then the terminal");
    assertEquals(
        6,
        Files.readAllLines(workspace.toPath().resolve("runs")).size(),
        "the first launch plus a bounded five relaunches");
  }

  @Test
  void anEditorThatDiesAfterRunningGoesBackToStarting() throws Exception {
    // The host proxies into a closed port for the whole backoff window if this stays RUNNING, so
    // the exit — not the relaunch — is where the state moves back.
    fakeEditor("echo 'Web UI available at http://localhost:13339/'; sleep 0.3; exit 1");
    supervisor = supervisor(true, 60_000);
    assertTrue(supervisor.start());

    awaitCondition(
        () ->
            states().size() >= 3
                && EditorState.State.STARTING.equals(states().get(2)),
        15_000,
        () -> "RUNNING then back to STARTING; saw " + states());
    assertEquals(
        List.of(EditorState.State.STARTING, EditorState.State.RUNNING, EditorState.State.STARTING),
        states().subList(0, 3));
  }

  @Test
  void closeTerminatesTheEditorAndReportsItEnded() throws Exception {
    fakeEditor("sleep 4243");
    supervisor = supervisor(true, 300);
    assertTrue(supervisor.start());
    awaitState(EditorState.State.RUNNING, 8000);
    awaitCondition(() -> pgrepCount("sleep 4243") >= 1, 8000, () -> "the editor process");

    supervisor.close();

    awaitCondition(
        () -> pgrepCount("sleep 4243") == 0,
        8000,
        () -> "the editor's whole session to be reaped on daemon shutdown");
    assertEquals(EditorState.State.ENDED, states().getLast());
    assertEquals(
        1,
        states().stream().filter(EditorState.State.ENDED::equals).count(),
        "the waiter and close() both settle ENDED; the host must be told once");
  }

  @Test
  void reportCurrentReReportsTheStateOnReconnect() throws Exception {
    fakeEditor("echo 'Web UI available at http://localhost:13339/'; sleep 30");
    supervisor = supervisor(true, 60_000);
    assertTrue(supervisor.start());
    awaitState(EditorState.State.RUNNING, 8000);

    supervisor.reportCurrent();

    assertEquals(
        List.of(EditorState.State.STARTING, EditorState.State.RUNNING, EditorState.State.RUNNING),
        states(),
        "a reconnect re-report is the same state again, not a transition that was withheld");
  }

  @Test
  void aSupervisorThatNeverStartedSaysNothingOnClose() throws Exception {
    supervisor =
        new EditorSupervisor(
            new File(install, "absent"), workspace, 13339, true, events::add, 200, 30, 60, 500);
    assertFalse(supervisor.start());
    supervisor.close();
    assertTrue(events.isEmpty(), "no editor was ever supervised, so none ended: " + events);
  }
}
