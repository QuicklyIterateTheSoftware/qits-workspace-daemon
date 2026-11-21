package eu.wohlben.qits.workspacedaemon;

import static eu.wohlben.qits.workspacedaemon.GitFixtures.CHILD;
import static eu.wohlben.qits.workspacedaemon.GitFixtures.WRAPPER;
import static eu.wohlben.qits.workspacedaemon.GitFixtures.git;
import static eu.wohlben.qits.workspacedaemon.GitFixtures.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.AgentBranchPushed;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * qits-2365: a branch with commits no remote has must not stay unpushed in silence. Shapes the
 * incident: the branch made with {@code git switch -c <branch> origin/main} (upstream origin/main)
 * in a submodule, while the same agent's branches elsewhere are pushed. Each case was a silent skip
 * before the fix; each now pushes, retries, or says so — on a stepped clock, never a sleep through
 * a backoff.
 */
@EnabledOnOs(OS.LINUX)
class OriginSyncFailureTest {

  private static final String BRANCH = "ticket/qits-2312";

  /** Where the child's pushes and refusals are reported: its path in the wrapper. */
  private static final String CHILD_WHERE = CHILD;

  @TempDir Path tmp;

  private GitFixtures.Estate estate;
  private AgentWorktrees worktrees;
  private final List<DaemonMessage> sent = new CopyOnWriteArrayList<>();
  private final AtomicLong now = new AtomicLong(1_000);
  private OriginSync sync;

  @BeforeEach
  void estate() throws Exception {
    estate = GitFixtures.estate(tmp);
    worktrees = new AgentWorktrees(estate.base(), estate.agents(), WRAPPER);
    sync = sync(worktrees);
  }

  private OriginSync sync(AgentWorktrees over) {
    return new OriginSync(
        over,
        agentId -> Optional.of(Map.of("QITS_TOKEN", "agent-token")),
        sent::add,
        true,
        0,
        0,
        0,
        5,
        0,
        0,
        now::get);
  }

  @AfterEach
  void close() {
    sync.close();
  }

  /** The agent's child worktree on BRANCH from origin/main, with two commits. */
  private Path childWithTwoCommits() throws Exception {
    Path child = worktrees.ensure("agent-1", "ticket/qits-2312-w").resolve(CHILD);
    git(child, "switch", "--quiet", "-c", BRANCH, "origin/main");
    assertEquals("refs/heads/main", line(child, "config", "--get", "branch." + BRANCH + ".merge"));
    git(child, "commit", "--quiet", "--allow-empty", "-m", "chore(qits-2312): one");
    git(child, "commit", "--quiet", "--allow-empty", "-m", "feat(qits-2312): two");
    return child;
  }

  private String onOrigin() throws Exception {
    return git(estate.childOrigin(), "branch", "--list", BRANCH).strip();
  }

  private static String failedSubject() {
    return "push-failed:agent-1:" + CHILD_WHERE + ":" + BRANCH;
  }

  private static void hook(Path bare, String body) throws Exception {
    Path hook = bare.resolve("hooks/pre-receive");
    Files.writeString(hook, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  @Test
  void aBranchWhoseUpstreamIsOriginMainIsPushedUnderItsOwnName() throws Exception {
    childWithTwoCommits();

    List<AgentBranchPushed> pushed = sync.pushCycle();

    assertEquals(1, pushed.size(), "the refspec is explicit: the upstream does not matter");
    assertEquals(BRANCH, onOrigin().replace("* ", ""));
    assertTrue(sync.warned.isEmpty(), sync.warned.toString());
  }

  @Test
  void aConfigRefusalPushesNothingAndWarnsAgainWhileItLasts() throws Exception {
    Path child = childWithTwoCommits();
    // From the agent's worktree: a linked worktree's `git config` lands in the shared base config.
    git(child, "config", "core.hooksPath", ".husky/_");
    String subject = "push:" + CHILD_WHERE;

    for (int i = 0; i < 5; i++) {
      assertTrue(sync.pushCycle().isEmpty());
    }
    assertEquals(1, sync.warnings(subject), "one WARN, not one a cycle");

    now.addAndGet(OriginSync.WARN_INTERVAL_MS - 1);
    assertTrue(sync.pushCycle().isEmpty());
    assertEquals(1, sync.warnings(subject), "not before the interval");

    now.addAndGet(1);
    assertTrue(sync.pushCycle().isEmpty());
    assertEquals(2, sync.warnings(subject), "said again while it lasts");
    assertEquals("", onOrigin(), "never pushed");
    assertEquals(1, sync.fetchAll(), "the fetch is refused too");

    git(child, "config", "--unset", "core.hooksPath");
    assertEquals(1, sync.pushCycle().size(), "a clean config pushes at once");
    assertEquals(0, sync.warnings(subject), "and the refusal is over");
  }

  @Test
  void aRefusedPushIsTriedAgainAfterABackoffWithoutTheHeadMoving() throws Exception {
    childWithTwoCommits();
    Path attempts = tmp.resolve("attempts");
    Path once = tmp.resolve("refused-once");
    hook(
        estate.childOrigin(),
        "echo x >> "
            + attempts
            + "; if [ ! -e "
            + once
            + " ]; then touch "
            + once
            + "; echo 'HTTP 502 from edge' >&2; exit 1; fi");

    assertTrue(sync.pushCycle().isEmpty(), "refused: [remote rejected] is FATAL");
    assertEquals(1, sync.warnings(failedSubject()), "a WARN naming it: " + sync.warned);

    now.addAndGet(OriginSync.FAILURE_BACKOFF_INITIAL_MS - 1);
    assertTrue(sync.pushCycle().isEmpty(), "not again before its backoff");
    assertEquals(1, Files.readAllLines(attempts).size(), "the host was not asked twice");

    now.addAndGet(1);
    List<AgentBranchPushed> pushed = sync.pushCycle();
    assertEquals(1, pushed.size(), "tried again on an unmoved head, and the host takes it now");
    assertEquals(BRANCH, onOrigin().replace("* ", ""));
    assertEquals(0, sync.warnings(failedSubject()), "the failure is over");
    assertTrue(sync.pushCycle().isEmpty(), "and a pushed head is not pushed twice");
  }

  @Test
  void aStalledPushIsKilledAtItsDeadlineAndTriedAgainLater() throws Exception {
    childWithTwoCommits();
    hook(estate.childOrigin(), "sleep 30");
    sync.gitTimeout = Duration.ofSeconds(2);

    long start = System.nanoTime();
    assertTrue(sync.pushCycle().isEmpty());
    long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

    assertTrue(seconds < 12, "killed at the 2 s deadline, not retried in the cycle: " + seconds);
    assertEquals(1, sync.warnings(failedSubject()), "said so: " + sync.warned);
    assertEquals("", onOrigin());

    Files.delete(estate.childOrigin().resolve("hooks/pre-receive"));
    now.addAndGet(OriginSync.FAILURE_BACKOFF_INITIAL_MS);
    assertEquals(1, sync.pushCycle().size(), "pushed on a later cycle");
  }

  @Test
  void aStalledFetchDoesNotHoldBackAPush() throws Exception {
    childWithTwoCommits();
    try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      List<Socket> accepted = new CopyOnWriteArrayList<>();
      CountDownLatch connected = new CountDownLatch(1);
      Thread acceptor =
          new Thread(
              () -> {
                try {
                  while (true) {
                    accepted.add(silent.accept());
                    connected.countDown();
                  }
                } catch (java.io.IOException closed) {
                  // the test is over
                }
              });
      acceptor.setDaemon(true);
      acceptor.start();
      // The wrapper's origin accepts a connection and never answers; recorded before the daemon
      // reads it, so the guard takes it as the daemon's own.
      git(
          estate.base(),
          "config",
          "remote.origin.url",
          "http://127.0.0.1:" + silent.getLocalPort() + "/wrapper");
      sync.close();
      sync = sync(new AgentWorktrees(estate.base(), estate.agents(), WRAPPER));

      sync.requestFetch();
      assertTrue(connected.await(30, TimeUnit.SECONDS), "the fetch reached the silent origin");
      sync.nudge();

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (sent.isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertFalse(sent.isEmpty(), "pushed while the fetch still hangs");
      assertEquals(BRANCH, ((AgentBranchPushed) sent.get(0)).branch());
      assertEquals(BRANCH, onOrigin().replace("* ", ""));
      sync.close();
      for (Socket socket : accepted) {
        socket.close();
      }
    }
  }
}
