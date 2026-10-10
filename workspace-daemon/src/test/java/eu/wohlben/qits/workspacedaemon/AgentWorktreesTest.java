package eu.wohlben.qits.workspacedaemon;

import static eu.wohlben.qits.workspacedaemon.GitFixtures.CHILD;
import static eu.wohlben.qits.workspacedaemon.GitFixtures.WRAPPER;
import static eu.wohlben.qits.workspacedaemon.GitFixtures.git;
import static eu.wohlben.qits.workspacedaemon.GitFixtures.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent-worktree layout (D1, D6, D22, D23) against real repositories: a wrapper with one
 * submodule, both with bare origins, and a base clone made from them — the scratch-repo proof the
 * plan rests on, kept as a test.
 */
@EnabledOnOs(OS.LINUX)
class AgentWorktreesTest {

  private static final String BRANCH = "ticket/qits-1";

  @TempDir Path tmp;

  private GitFixtures.Estate estate;
  private AgentWorktrees worktrees;

  @BeforeEach
  void estate() throws Exception {
    estate = GitFixtures.estate(tmp);
    worktrees = new AgentWorktrees(estate.base(), estate.agents(), WRAPPER);
  }

  @Test
  void theWrapperIsOnItsBranchAndEachSubmoduleDetachedAtOriginMain() throws Exception {
    String baseHead = line(estate.base(), "rev-parse", "HEAD");

    Path dir = worktrees.ensure("agent-1", BRANCH);

    assertEquals(estate.agents().resolve("agent-1").resolve(WRAPPER), dir);
    assertEquals(BRANCH, line(dir, "symbolic-ref", "--short", "HEAD"));
    Path child = dir.resolve(CHILD);
    assertEquals("HEAD", line(child, "rev-parse", "--abbrev-ref", "HEAD"), "detached");
    assertEquals(
        line(estate.base().resolve(CHILD), "rev-parse", "origin/main"),
        line(child, "rev-parse", "HEAD"));
    assertTrue(Files.exists(child.resolve("lib.txt")), "the submodule's files are there");
    assertEquals(baseHead, line(estate.base(), "rev-parse", "HEAD"), "the base never moves");
    assertEquals("", git(estate.base(), "status", "--porcelain"), "and stays clean");
  }

  @Test
  void aSubmoduleStartsAtItsOriginMainRatherThanTheRecordedGitlink() throws Exception {
    String released = GitFixtures.advanceChildOrigin(tmp, estate);
    git(estate.base().resolve(CHILD), "fetch", "--quiet", "origin");

    Path dir = worktrees.ensure("agent-1", BRANCH);

    assertEquals(released, line(dir.resolve(CHILD), "rev-parse", "HEAD"));
  }

  @Test
  void aWrapperBranchTheGitHostHasIsContinued() throws Exception {
    Path elsewhere = tmp.resolve("elsewhere");
    git(tmp, "clone", "--quiet", estate.wrapperOrigin().toString(), elsewhere.toString());
    GitFixtures.identity(elsewhere);
    git(elsewhere, "switch", "--quiet", "-c", BRANCH);
    git(elsewhere, "commit", "--quiet", "--allow-empty", "-m", "docs on the branch");
    git(elsewhere, "push", "--quiet", "origin", BRANCH);

    Path dir = worktrees.ensure("agent-1", BRANCH);

    assertEquals(line(elsewhere, "rev-parse", "HEAD"), line(dir, "rev-parse", "HEAD"));
  }

  @Test
  void ensuringAgainKeepsTheWorkInTheWorktree() throws Exception {
    Path dir = worktrees.ensure("agent-1", BRANCH);
    Files.writeString(dir.resolve("notes.md"), "unfinished\n");
    Files.writeString(dir.resolve(CHILD).resolve("lib.txt"), "edited\n");

    worktrees.ensure("agent-1", BRANCH);

    assertEquals("unfinished\n", Files.readString(dir.resolve("notes.md")));
    assertEquals("edited\n", Files.readString(dir.resolve(CHILD).resolve("lib.txt")));
  }

  @Test
  void twoAgentsCannotShareABranch() throws Exception {
    worktrees.ensure("agent-1", BRANCH);

    AgentWorktrees.AgentWorktreeException refused =
        assertThrows(
            AgentWorktrees.AgentWorktreeException.class, () -> worktrees.ensure("agent-2", BRANCH));
    assertEquals(409, refused.status());
  }

  @Test
  void anUnsafeIdOrBranchIsRefusedBeforeGitSeesIt() {
    assertEquals(
        400,
        assertThrows(
                AgentWorktrees.AgentWorktreeException.class,
                () -> worktrees.ensure("../escape", BRANCH))
            .status());
    assertThrows(
        AgentWorktrees.AgentWorktreeException.class, () -> worktrees.ensure("agent-1", "--force"));
    assertThrows(
        AgentWorktrees.AgentWorktreeException.class, () -> worktrees.ensure("agent-1", "a..b"));
    assertThrows(
        AgentWorktrees.AgentWorktreeException.class,
        () -> worktrees.ensure("agent-1", "main"),
        "an agent never works on the default branch");
  }

  @Test
  void theCommitGuardRefusesTheDefaultBranchAndADetachedHeadAndNamesTheBranch() throws Exception {
    worktrees.installGuards();
    Path child = worktrees.ensure("agent-1", BRANCH).resolve(CHILD);
    Files.writeString(child.resolve("lib.txt"), "work\n");
    git(child, "add", "lib.txt");

    String detached = refusedCommit(child);
    assertTrue(detached.contains("detached HEAD"), detached);
    assertTrue(detached.contains("git switch -c " + BRANCH + "-<name>"), detached);

    git(child, "switch", "--quiet", "main");
    String onMain = refusedCommit(child);
    assertTrue(onMain.contains("on main"), onMain);

    git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    git(child, "commit", "--quiet", "-m", "on the agent's own branch");
    assertEquals(BRANCH + "-fix", line(child, "symbolic-ref", "--short", "HEAD"));
  }

  private static String refusedCommit(Path dir) throws Exception {
    Process commit =
        new ProcessBuilder("git", "-C", dir.toString(), "commit", "--quiet", "-m", "x")
            .redirectErrorStream(true)
            .start();
    String output = new String(commit.getInputStream().readAllBytes());
    assertEquals(1, commit.waitFor(), output);
    return output;
  }

  @Test
  void theCleanupCheckFindsDirtyFilesAndCommitsNoRemoteHasEvenOnADetachedHead() throws Exception {
    Path dir = worktrees.ensure("agent-1", BRANCH);
    assertTrue(worktrees.cleanupCheck("agent-1").clean(), "a fresh worktree has nothing to lose");

    Files.writeString(dir.resolve("notes.md"), "unfinished\n");
    Path child = dir.resolve(CHILD);
    Files.writeString(child.resolve("lib.txt"), "work\n");
    git(child, "commit", "--quiet", "--no-verify", "-am", "left on a detached HEAD");

    AgentWorktrees.CleanupCheck check = worktrees.cleanupCheck("agent-1");

    assertFalse(check.clean());
    assertEquals(List.of(WRAPPER), check.dirty(), "a moved gitlink in the wrapper is not dirt");
    assertEquals(1, check.unpushed().size());
    AgentWorktrees.Leftover leftover = check.unpushed().get(0);
    assertEquals("child", leftover.repository());
    assertNull(leftover.branch());
    assertEquals(1, leftover.commits());
  }

  @Test
  void statusNamesEachRepositorysBranchAndWhetherItIsPushed() throws Exception {
    Path child = worktrees.ensure("agent-1", BRANCH).resolve(CHILD);
    git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    git(child, "commit", "--quiet", "--allow-empty", "-m", "work");

    List<AgentWorktrees.RepoState> states = worktrees.status("agent-1");

    assertEquals(2, states.size());
    AgentWorktrees.RepoState wrapper = states.get(0);
    assertEquals(WRAPPER, wrapper.repository());
    assertEquals("", wrapper.path());
    assertEquals(BRANCH, wrapper.branch());
    AgentWorktrees.RepoState sub = states.get(1);
    assertEquals("child", sub.repository());
    assertEquals(CHILD, sub.path());
    assertEquals(BRANCH + "-fix", sub.branch());
    assertEquals(line(child, "rev-parse", "HEAD"), sub.head());
    assertFalse(sub.pushed());
    assertEquals(1, sub.unpushedCommits());

    git(child, "push", "--quiet", "origin", BRANCH + "-fix");
    assertTrue(worktrees.status("agent-1").get(1).pushed());
  }

  @Test
  void removingAnAgentRemovesItsWorktreesAndTheBranchesTheyWereOn() throws Exception {
    Path dir = worktrees.ensure("agent-1", BRANCH);
    git(dir.resolve(CHILD), "switch", "--quiet", "-c", BRANCH + "-fix");
    worktrees.ensure("agent-2", "ticket/qits-2");

    worktrees.remove("agent-1");

    assertFalse(Files.exists(estate.agents().resolve("agent-1")));
    assertEquals("", git(estate.base(), "branch", "--list", BRANCH));
    assertEquals("", git(estate.base().resolve(CHILD), "branch", "--list", BRANCH + "-fix"));
    assertFalse(git(estate.base(), "worktree", "list").contains("agent-1"));
    assertFalse(git(estate.base().resolve(CHILD), "worktree", "list").contains("agent-1"));
    assertTrue(worktrees.exists("agent-2"), "another agent is left alone");
    assertEquals(List.of("agent-2"), worktrees.agentIds());
  }

  // --- path confinement (security pass) ----------------------------------------------------------

  @Test
  void anAgentDirectoryThatIsASymbolicLinkIsNoAgentAndIsNeverMadeInto() throws Exception {
    Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
    Files.createDirectories(estate.agents());
    Files.createSymbolicLink(estate.agents().resolve("agent-9"), elsewhere);

    assertFalse(worktrees.exists("agent-9"));
    assertEquals(
        409,
        assertThrows(
                AgentWorktrees.AgentWorktreeException.class,
                () -> worktrees.ensure("agent-9", BRANCH))
            .status());
    assertEquals(0, Files.list(elsewhere).count(), "nothing was written through the link");
  }

  @Test
  void aWrapperWorktreeSwappedForALinkToAnotherAgentIsNotThatAgentsAnyMore() throws Exception {
    Path other = worktrees.ensure("agent-2", "ticket/qits-2");
    Path dir = worktrees.ensure("agent-1", BRANCH);
    AgentWorktrees.deleteTree(dir);
    Files.createSymbolicLink(dir, other);

    assertFalse(worktrees.exists("agent-1"));
    assertTrue(worktrees.exists("agent-2"));
  }

  @Test
  void removingFollowsNoSymbolicLinkOutOfTheAgentsDirectory() throws Exception {
    Path outside = Files.createDirectories(tmp.resolve("outside"));
    Files.writeString(outside.resolve("keep.txt"), "keep\n");
    Path dir = worktrees.ensure("agent-1", BRANCH);
    Files.createSymbolicLink(dir.resolve("escape"), outside);
    Files.createSymbolicLink(worktrees.agentDir("agent-1").resolve("escape"), outside);

    worktrees.remove("agent-1");

    assertTrue(Files.exists(outside.resolve("keep.txt")));
    assertFalse(Files.exists(worktrees.agentDir("agent-1")));
  }

  @Test
  void aSubmodulePathThatCouldLeaveTheWorktreeIsRefused() {
    assertTrue(AgentWorktrees.confined("libs/child"));
    assertFalse(AgentWorktrees.confined("../escape"));
    assertFalse(AgentWorktrees.confined("libs/../../escape"));
    assertFalse(AgentWorktrees.confined("/abs"));
    assertFalse(AgentWorktrees.confined("-c"));
    assertFalse(AgentWorktrees.confined("a//b"));
  }

  @Test
  void anAgentIdIsADirectoryNameAndNothingMore() {
    assertTrue(AgentWorktrees.validAgentId("3f2504e0-4f89-11d3-9a0c-0305e82c3301"));
    for (String bad : new String[] {"..", ".", "../x", "a/b", "-rf", "", "a\\b", "x\n"}) {
      assertFalse(AgentWorktrees.validAgentId(bad), bad);
    }
  }
}
