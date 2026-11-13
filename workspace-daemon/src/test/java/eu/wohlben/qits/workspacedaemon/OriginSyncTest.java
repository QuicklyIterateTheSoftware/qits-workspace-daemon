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
  void aBranchWhoseWorkAReleaseTagHoldsIsNotPushedAgain() throws Exception {
    // qits-1688: the release deleted the branch and kept the work in its tag. Pushing the branch
    // again would bring it back.
    Path child = child();
    git(child, "switch", "--quiet", "-c", BRANCH + "-fix");
    git(child, "commit", "--quiet", "--allow-empty", "-m", "released");
    git(child, "push", "--quiet", "origin", "HEAD:refs/tags/2026.1.1");
    sync.fetchAll();

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals("", git(estate.childOrigin(), "branch", "--list", BRANCH + "-fix"));
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
    credential.set(Optional.of(Map.of("QITS_AGENT_ID", "not-a-token")));

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

  // --- agent-local repositories (qits-1886) ----------------------------------------------------

  private static final String NEWLIB = "libs/newlib";

  /** The agent adds {@code ../newlib.git} as a submodule in its own wrapper worktree. */
  private Path addedByTheAgent(String agentId) throws Exception {
    Path wrapper = worktrees.ensure(agentId, agentId.equals("agent-1") ? BRANCH : BRANCH + "-2");
    git(
        wrapper,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        "--quiet",
        "../newlib.git",
        NEWLIB);
    Path newlib = wrapper.resolve(NEWLIB);
    GitFixtures.identity(newlib);
    return newlib;
  }

  private static String commitOnBranch(Path repository, String branch) throws Exception {
    git(repository, "switch", "--quiet", "-c", branch);
    git(repository, "commit", "--quiet", "--allow-empty", "-m", "work");
    return line(repository, "rev-parse", "HEAD");
  }

  @Test
  void aRepositoryTheAgentAddsAsASubmoduleIsPushedUnderItsOwnName() throws Exception {
    Path newlibOrigin = GitFixtures.sibling(tmp, "newlib");
    Path newlib = addedByTheAgent("agent-1");
    String head = commitOnBranch(newlib, BRANCH);

    List<AgentBranchPushed> pushed = sync.pushCycle();

    AgentBranchPushed expected = new AgentBranchPushed("agent-1", "newlib", BRANCH, head);
    assertEquals(List.of(expected), pushed);
    assertEquals(List.of(expected), sent, "and the push goes home as a frame");
    assertEquals(head, line(newlibOrigin, "rev-parse", BRANCH));
    assertEquals(0, sync.fetchAll(), "and it is fetched like the base clone's repositories");
  }

  @Test
  void aSubmoduleInitialisedFromTheAgentsWrapperBranchIsPushed() throws Exception {
    Path newlibOrigin = GitFixtures.sibling(tmp, "newlib");
    // Somebody else lands the gitlink on the agent's wrapper branch; the agent merges it and
    // initialises the submodule in its own worktree.
    Path elsewhere = tmp.resolve("elsewhere");
    git(tmp, "clone", "--quiet", estate.wrapperOrigin().toString(), elsewhere.toString());
    GitFixtures.identity(elsewhere);
    git(
        elsewhere,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        "--quiet",
        "--name",
        "newlib",
        "../newlib.git",
        NEWLIB);
    git(elsewhere, "commit", "--quiet", "-m", "add newlib");
    git(elsewhere, "push", "--quiet", "origin", "HEAD:refs/heads/" + BRANCH);
    Path wrapper = worktrees.ensure("agent-1", BRANCH);
    git(wrapper, "pull", "--quiet", "--ff-only", "origin", BRANCH);
    git(
        wrapper,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "update",
        "--init",
        "--quiet",
        "--",
        NEWLIB);
    Path newlib = wrapper.resolve(NEWLIB);
    GitFixtures.identity(newlib);
    String head = commitOnBranch(newlib, BRANCH + "-lib");

    List<AgentBranchPushed> pushed =
        sync.pushCycle().stream().filter(p -> p.repository().equals("newlib")).toList();

    assertEquals(
        List.of(new AgentBranchPushed("agent-1", "newlib", BRANCH + "-lib", head)), pushed);
    assertEquals(head, line(newlibOrigin, "rev-parse", BRANCH + "-lib"));
  }

  @Test
  void aSubmoduleOfAnAgentLocalRepositoryResolvesAgainstItsParentAndIsPushed() throws Exception {
    GitFixtures.sibling(tmp, "newlib");
    Path innerOrigin = GitFixtures.sibling(tmp, "inner");
    Path newlib = addedByTheAgent("agent-1");
    git(
        newlib,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        "--quiet",
        "../inner.git",
        "inner");
    Path inner = newlib.resolve("inner");
    GitFixtures.identity(inner);
    String head = commitOnBranch(inner, BRANCH);

    assertEquals(
        List.of(new AgentBranchPushed("agent-1", "inner", BRANCH, head)), sync.pushCycle());
    assertEquals(head, line(innerOrigin, "rev-parse", BRANCH));
  }

  @Test
  void theDefaultBranchAndADetachedHeadOfAnAgentLocalRepositoryAreNotPushed() throws Exception {
    Path newlibOrigin = GitFixtures.sibling(tmp, "newlib");
    Path newlib = addedByTheAgent("agent-1");
    String before = line(newlibOrigin, "rev-parse", "main");
    git(newlib, "commit", "--quiet", "--allow-empty", "-m", "on main");

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals(before, line(newlibOrigin, "rev-parse", "main"));

    git(newlib, "checkout", "--quiet", "--detach");
    git(newlib, "commit", "--quiet", "--allow-empty", "-m", "nowhere to go");
    assertTrue(sync.pushCycle().isEmpty());
    assertEquals(before, line(newlibOrigin, "rev-parse", "main"));
  }

  @Test
  void aCloneOfItsOwnPlantedAtADeclaredPathIsNotPushed() throws Exception {
    Path newlibOrigin = GitFixtures.sibling(tmp, "newlib");
    Path wrapper = worktrees.ensure("agent-1", BRANCH);
    // A plain clone at a path .gitmodules declares: its git dir is its own, not one git made under
    // this agent's worktree git dir.
    Path planted = wrapper.resolve(NEWLIB);
    git(tmp, "clone", "--quiet", newlibOrigin.toString(), planted.toString());
    GitFixtures.identity(planted);
    Path gitmodules = wrapper.resolve(".gitmodules");
    git(wrapper, "config", "-f", gitmodules.toString(), "submodule.newlib.path", NEWLIB);
    git(wrapper, "config", "-f", gitmodules.toString(), "submodule.newlib.url", "../newlib.git");
    commitOnBranch(planted, BRANCH);

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals("", git(newlibOrigin, "branch", "--list", BRANCH));
  }

  @Test
  void aCheckoutPointingIntoAnotherAgentsModulesIsNotPushedAsThisAgent() throws Exception {
    Path newlibOrigin = GitFixtures.sibling(tmp, "newlib");
    Path theirs = addedByTheAgent("agent-2");
    String head = commitOnBranch(theirs, BRANCH + "-2");
    String theirGitDir = line(theirs, "rev-parse", "--absolute-git-dir");
    // Agent 1's wrapper declares the same path, and a .git file there points at agent 2's clone.
    Path wrapper = worktrees.ensure("agent-1", BRANCH);
    Path gitmodules = wrapper.resolve(".gitmodules");
    git(wrapper, "config", "-f", gitmodules.toString(), "submodule.newlib.path", NEWLIB);
    git(wrapper, "config", "-f", gitmodules.toString(), "submodule.newlib.url", "../newlib.git");
    Files.createDirectories(wrapper.resolve(NEWLIB));
    Files.writeString(wrapper.resolve(NEWLIB).resolve(".git"), "gitdir: " + theirGitDir + "\n");

    List<AgentBranchPushed> pushed = sync.pushCycle();

    assertEquals(
        List.of(new AgentBranchPushed("agent-2", "newlib", BRANCH + "-2", head)),
        pushed,
        "pushed once, as the agent whose git made it");
    assertEquals(head, line(newlibOrigin, "rev-parse", BRANCH + "-2"));
  }

  @Test
  void aRedirectedOriginOnAnAgentLocalRepositoryStopsThePushAndTheFetch() throws Exception {
    GitFixtures.sibling(tmp, "newlib");
    Path newlib = addedByTheAgent("agent-1");
    commitOnBranch(newlib, BRANCH);
    String origin = line(newlib, "config", "--get", "remote.origin.url");
    Path elsewhere = tmp.resolve("elsewhere.git");
    git(tmp, "init", "--quiet", "--bare", elsewhere.toString());
    git(newlib, "config", "remote.origin.url", elsewhere.toString());

    assertTrue(sync.pushCycle().isEmpty(), "its own origin is never its own expectation");
    assertEquals("", git(elsewhere, "branch", "--list"));
    assertEquals(1, sync.fetchAll(), "nor fetched from there with the workspace's credential");

    git(newlib, "config", "remote.origin.url", origin);
    assertEquals(1, sync.pushCycle().size(), "a clean config pushes again");
  }

  @Test
  void anAgentLocalHeadWaitsForTheCredentialAndIsNotPushedTwice() throws Exception {
    GitFixtures.sibling(tmp, "newlib");
    Path newlib = addedByTheAgent("agent-1");
    commitOnBranch(newlib, BRANCH);
    credential.set(Optional.empty());

    assertTrue(sync.pushCycle().isEmpty(), "never pushed as the workspace");

    credential.set(Optional.of(Map.of("QITS_TOKEN", "agent-token")));
    assertEquals(1, sync.pushCycle().size());
    assertTrue(sync.pushCycle().isEmpty(), "an unmoved head is not pushed twice");

    git(newlib, "commit", "--quiet", "--allow-empty", "-m", "more work");
    assertEquals(1, sync.pushCycle().size(), "a new commit is pushed again");
  }

  // --- agent-local checkouts at base paths and in the sibling url's spelling (qits-2166) --------

  /**
   * The agent's own {@code submodule update --init} of the base clone's child, where no worktree of
   * it was made for the agent: the daemon's worktree is removed and the directory left empty.
   */
  private Path initialisedByTheAgent(String agentId, String wrapperBranch) throws Exception {
    Path wrapper = worktrees.ensure(agentId, wrapperBranch);
    Path child = wrapper.resolve(CHILD);
    git(estate.base().resolve(CHILD), "worktree", "remove", "--force", child.toString());
    Files.createDirectories(child);
    git(
        wrapper,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "update",
        "--init",
        "--quiet",
        "--",
        CHILD);
    GitFixtures.identity(child);
    assertTrue(
        line(child, "rev-parse", "--absolute-git-dir")
            .startsWith(line(wrapper, "rev-parse", "--absolute-git-dir") + "/modules/"),
        "a standalone clone under the wrapper worktree's git dir");
    return child;
  }

  @Test
  void aBaseSubmoduleTheAgentInitialisedItselfIsPushedAndFetched() throws Exception {
    Path child = initialisedByTheAgent("agent-1", BRANCH);
    String head = commitOnBranch(child, BRANCH + "-fix");

    List<AgentBranchPushed> pushed = sync.pushCycle();

    assertEquals(
        List.of(new AgentBranchPushed("agent-1", "child", BRANCH + "-fix", head)), pushed);
    assertEquals(head, line(estate.childOrigin(), "rev-parse", BRANCH + "-fix"));
    assertEquals(0, sync.fetchAll(), "and it is fetched like the base clone's repositories");
  }

  @Test
  void aSubmoduleClonedFromTheBaseClonesSiblingUrlWithoutDotGitIsPushed() throws Exception {
    Path newlibOrigin = GitFixtures.sibling(tmp, "newlib");
    Path elsewhere = tmp.resolve("elsewhere");
    git(tmp, "clone", "--quiet", estate.wrapperOrigin().toString(), elsewhere.toString());
    GitFixtures.identity(elsewhere);
    git(
        elsewhere,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        "--quiet",
        "--name",
        "newlib",
        "../newlib.git",
        NEWLIB);
    git(elsewhere, "commit", "--quiet", "-m", "add newlib");
    git(elsewhere, "push", "--quiet", "origin", "HEAD:refs/heads/" + BRANCH);
    // The boot's materialisation wrote the served sibling's url, without .git, into the config
    // every worktree shares, and its update left the submodule uninitialised in the base clone.
    String siblingUrl = tmp.resolve("newlib").toString();
    git(estate.base(), "config", "submodule.newlib.url", siblingUrl);
    Path wrapper = worktrees.ensure("agent-1", BRANCH);
    git(
        wrapper,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "update",
        "--init",
        "--quiet",
        "--",
        NEWLIB);
    Path newlib = wrapper.resolve(NEWLIB);
    GitFixtures.identity(newlib);
    assertEquals(siblingUrl, line(newlib, "config", "--get", "remote.origin.url"));
    String head = commitOnBranch(newlib, BRANCH + "-lib");

    List<AgentBranchPushed> pushed = sync.pushCycle();

    assertEquals(
        List.of(new AgentBranchPushed("agent-1", "newlib", BRANCH + "-lib", head)), pushed);
    assertEquals(head, line(newlibOrigin, "rev-parse", BRANCH + "-lib"));
    assertEquals(0, sync.fetchAll());
  }

  @Test
  void aCheckoutAtABasePathPointingIntoAnotherAgentsModulesIsNotPushedAsThisAgent()
      throws Exception {
    Path theirs = initialisedByTheAgent("agent-2", BRANCH + "-2");
    String head = commitOnBranch(theirs, BRANCH + "-2-fix");
    String theirGitDir = line(theirs, "rev-parse", "--absolute-git-dir");
    Path child = worktrees.ensure("agent-1", BRANCH).resolve(CHILD);
    git(estate.base().resolve(CHILD), "worktree", "remove", "--force", child.toString());
    Files.createDirectories(child);
    Files.writeString(child.resolve(".git"), "gitdir: " + theirGitDir + "\n");

    List<AgentBranchPushed> pushed = sync.pushCycle();

    assertEquals(
        List.of(new AgentBranchPushed("agent-2", "child", BRANCH + "-2-fix", head)),
        pushed,
        "pushed once, as the agent whose git made it");
  }

  @Test
  void anAgentLocalOriginInNeitherSpellingStopsThePushAndTheFetch() throws Exception {
    Path child = initialisedByTheAgent("agent-1", BRANCH);
    commitOnBranch(child, BRANCH + "-fix");
    String origin = line(child, "config", "--get", "remote.origin.url");
    Path elsewhere = GitFixtures.sibling(tmp, "elsewhere");
    git(child, "config", "remote.origin.url", elsewhere.toString());

    assertTrue(sync.pushCycle().isEmpty());
    assertEquals("", git(elsewhere, "branch", "--list", BRANCH + "-fix"));
    assertEquals(1, sync.fetchAll());

    git(child, "config", "remote.origin.url", origin);
    assertEquals(1, sync.pushCycle().size(), "a clean config pushes again");
  }

  @Test
  void theWorktreesTheDaemonMadeArePushedOnceEachWithoutAWarning() throws Exception {
    Path wrapper = worktrees.ensure("agent-1", BRANCH);
    git(wrapper, "commit", "--quiet", "--allow-empty", "-m", "docs");
    String child = commitOnBranch(wrapper.resolve(CHILD), BRANCH + "-fix");

    List<AgentBranchPushed> pushed = sync.pushCycle();

    assertEquals(2, pushed.size(), pushed.toString());
    assertEquals(WRAPPER, pushed.get(0).repository());
    assertEquals(new AgentBranchPushed("agent-1", "child", BRANCH + "-fix", child), pushed.get(1));
    assertEquals(0, sync.fetchAll());
    assertTrue(sync.warned.isEmpty(), "an ordinary submodule is passed over without a word");
  }
}
