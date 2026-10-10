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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fetch and auto-push against real repositories, driven off the scheduler through {@code fetchAll}
 * and {@code pushCycle}: what is pushed, what is not, and what is reported.
 */
@EnabledOnOs(OS.LINUX)
class OriginSyncTest {

  private static final String BRANCH = "ticket/qits-1";

  @TempDir Path tmp;

  private GitFixtures.Estate estate;
  private AgentWorktrees worktrees;
  private final List<DaemonMessage> sent = new CopyOnWriteArrayList<>();

  /** The credential each agent's push carries; empty means "not known yet". */
  private final AtomicReference<Optional<Map<String, String>>> credential =
      new AtomicReference<>(Optional.of(Map.of("QITS_TOKEN", "agent-token")));

  private OriginSync sync;

  @BeforeEach
  void estate() throws Exception {
    estate = GitFixtures.estate(tmp);
    worktrees = new AgentWorktrees(estate.base(), estate.agents(), WRAPPER);
    sync =
        new OriginSync(worktrees, agentId -> credential.get(), sent::add, true, 0, 0, 0, 3, 0, 0);
  }

  @AfterEach
  void close() {
    sync.close();
  }

  private Path child() throws Exception {
    return worktrees.ensure("agent-1", BRANCH).resolve(CHILD);
  }

  @Test
  void aSubmoduleBranchWithNewCommitsIsPushedAndReported() throws Exception {
    Path child = child();
    git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    Files.writeString(child.resolve("lib.txt"), "fixed\n");
    git(child, "commit", "--quiet", "-am", "fix");
    String head = line(child, "rev-parse", "HEAD");

    List<AgentBranchPushed> pushed = sync.pushCycle();

    AgentBranchPushed expected = new AgentBranchPushed("agent-1", "child", BRANCH + "-fix", head);
    assertEquals(List.of(expected), pushed);
    assertEquals(List.of(expected), sent, "and each push goes home as a frame");
    assertEquals(head, line(estate.childOrigin(), "rev-parse", BRANCH + "-fix"));
  }

  @Test
  void theWrapperBranchIsReportedUnderTheWrappersName() throws Exception {
    Path wrapper = worktrees.ensure("agent-1", BRANCH);
    git(wrapper, "commit", "--quiet", "--allow-empty", "-m", "docs");

    List<AgentBranchPushed> pushed = sync.pushCycle();

    assertEquals(1, pushed.size());
    assertEquals(WRAPPER, pushed.get(0).repository());
    assertEquals(BRANCH, pushed.get(0).branch());
  }

  @Test
  void aBranchWithNothingNewIsNotPushed() throws Exception {
    git(child(), "switch", "--quiet", "-c", BRANCH + "-fix");

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals("", git(estate.childOrigin(), "branch", "--list", BRANCH + "-fix"));
  }

  @Test
  void aCommitOnADetachedHeadIsNotPushed() throws Exception {
    Path child = child();
    git(child, "commit", "--quiet", "--allow-empty", "--no-verify", "-m", "nowhere to go");

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals(1, worktrees.cleanupCheck("agent-1").unpushed().size(), "the check catches it");
  }

  @Test
  void theDefaultBranchIsNeverPushed() throws Exception {
    Path child = child();
    String before = line(estate.childOrigin(), "rev-parse", "main");
    git(child, "switch", "--quiet", "main");
    git(child, "commit", "--quiet", "--allow-empty", "--no-verify", "-m", "on main");

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals(before, line(estate.childOrigin(), "rev-parse", "main"));
  }

  @Test
  void anAgentWhoseCredentialIsNotKnownWaitsAndIsPushedOnceItIs() throws Exception {
    Path child = child();
    git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    git(child, "commit", "--quiet", "--allow-empty", "-m", "work");
    credential.set(Optional.empty());

    assertTrue(sync.pushCycle().isEmpty(), "never pushed as the workspace");

    credential.set(Optional.of(Map.of("QITS_TOKEN", "agent-token")));
    assertEquals(1, sync.pushCycle().size());
  }

  @Test
  void anUnmovedHeadIsNotPushedTwice() throws Exception {
    Path child = child();
    git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    git(child, "commit", "--quiet", "--allow-empty", "-m", "work");
    assertEquals(1, sync.pushCycle().size());

    assertTrue(sync.pushCycle().isEmpty());

    git(child, "commit", "--quiet", "--allow-empty", "-m", "more work");
    assertEquals(1, sync.pushCycle().size(), "a new commit is pushed again");
  }

  @Test
  void aBranchTheHostMovedAheadIsLeftAloneRatherThanForced() throws Exception {
    Path child = child();
    git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    git(child, "commit", "--quiet", "--allow-empty", "-m", "local");
    Path elsewhere = tmp.resolve("elsewhere");
    git(tmp, "clone", "--quiet", estate.childOrigin().toString(), elsewhere.toString());
    GitFixtures.identity(elsewhere);
    git(elsewhere, "commit", "--quiet", "--allow-empty", "-m", "remote");
    git(elsewhere, "push", "--quiet", "origin", "HEAD:refs/heads/" + BRANCH + "-fix");
    git(estate.base().resolve(CHILD), "fetch", "--quiet", "origin");

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals(
        line(elsewhere, "rev-parse", "HEAD"),
        line(estate.childOrigin(), "rev-parse", BRANCH + "-fix"),
        "never a force");
  }

  @Test
  void aFetchBringsEveryHeadAndPrunesDeletedOnesWithoutMovingTheBase() throws Exception {
    Path child = child();
    git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    git(child, "commit", "--quiet", "--allow-empty", "-m", "work");
    sync.pushCycle();
    String baseHead = line(estate.base().resolve(CHILD), "rev-parse", "HEAD");
    String released = GitFixtures.advanceChildOrigin(tmp, estate);
    git(estate.childOrigin(), "branch", "-D", BRANCH + "-fix");

    assertEquals(0, sync.fetchAll());

    Path baseChild = estate.base().resolve(CHILD);
    assertEquals(released, line(baseChild, "rev-parse", "origin/main"));
    assertFalse(
        git(baseChild, "branch", "--remotes").contains(BRANCH + "-fix"),
        "a branch the release deleted is pruned");
    assertEquals(baseHead, line(baseChild, "rev-parse", "HEAD"), "the base clone never moves");
    assertEquals(released, line(child, "rev-parse", "origin/main"), "and agents see it at once");
  }

  // --- credential separation (security pass) ---------------------------------------------------

  private Path committedOnBranch() throws Exception {
    Path child = child();
    git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    git(child, "commit", "--quiet", "--allow-empty", "-m", "work");
    return child;
  }

  @Test
  void anAgentWhoseEnvHoldsNoTokenOfItsOwnIsNeverPushed() throws Exception {
    committedOnBranch();
    // Something in the env, but not the agent's token: there is nothing to push as.
    credential.set(Optional.of(Map.of("QITS_COMMISSIONED_CLIENT_SECRET", "not-a-token")));

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals("", git(estate.childOrigin(), "branch", "--list", BRANCH + "-fix"));
  }

  @Test
  void aCredentialHelperPlantedInTheSharedConfigStopsThePush() throws Exception {
    committedOnBranch();
    Path stolen = tmp.resolve("stolen");
    git(
        estate.base().resolve(CHILD),
        "config",
        "credential.helper",
        "!f() { cat > " + stolen + "; }; f");

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals("", git(estate.childOrigin(), "branch", "--list", BRANCH + "-fix"));
    assertFalse(Files.exists(stolen));
  }

  @Test
  void aRedirectedOriginOrAProxyStopsThePushAndTheFetch() throws Exception {
    committedOnBranch();
    Path elsewhere = tmp.resolve("elsewhere.git");
    git(tmp, "init", "--quiet", "--bare", elsewhere.toString());
    Path child = estate.base().resolve(CHILD);
    git(child, "config", "remote.origin.url", elsewhere.toString());

    assertTrue(sync.pushCycle().isEmpty(), "never pushed where an agent pointed the origin");
    assertEquals("", git(elsewhere, "branch", "--list"));
    assertEquals(1, sync.fetchAll(), "nor fetched from there with the workspace's credential");

    git(child, "config", "remote.origin.url", estate.childOrigin().toString());
    git(child, "config", "http.proxy", "http://127.0.0.1:9");
    assertTrue(sync.pushCycle().isEmpty());
    git(child, "config", "--unset", "http.proxy");
    git(child, "config", "url." + elsewhere + ".pushInsteadOf", estate.childOrigin().toString());
    assertTrue(sync.pushCycle().isEmpty());

    git(child, "config", "--remove-section", "url." + elsewhere);
    assertEquals(1, sync.pushCycle().size(), "a clean config pushes again");
  }

  @Test
  void aPrePushHookPlantedInTheSharedRepositoryNeverRunsWithTheAgentsCredential() throws Exception {
    committedOnBranch();
    Path ran = tmp.resolve("ran");
    Path hook = estate.base().resolve(".git/modules/child/hooks/pre-push");
    Files.createDirectories(hook.getParent());
    Files.writeString(hook, "#!/bin/sh\nenv > " + ran + "\n");
    hook.toFile().setExecutable(true);

    assertEquals(1, sync.pushCycle().size());
    assertFalse(Files.exists(ran));
  }

  @Test
  void aWorktreeAnotherAgentAddedUnderThisAgentsDirectoryIsNotPushedAsThisAgent() throws Exception {
    worktrees.ensure("agent-1", BRANCH);
    // Agents share the OS user: agent 2 could add a worktree of its own inside agent 1's
    // directory. Only the worktree the daemon made for agent 1 is pushed with agent 1's token.
    Path planted = estate.agents().resolve("agent-1").resolve("planted");
    git(
        estate.base().resolve(CHILD),
        "worktree",
        "add",
        "--quiet",
        "-b",
        BRANCH + "-smuggled",
        planted.toString(),
        "origin/main");
    git(planted, "commit", "--quiet", "--allow-empty", "--no-verify", "-m", "smuggled");

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals("", git(estate.childOrigin(), "branch", "--list", BRANCH + "-smuggled"));
  }
}
