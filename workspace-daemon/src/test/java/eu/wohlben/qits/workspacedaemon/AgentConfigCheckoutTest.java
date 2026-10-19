package eu.wohlben.qits.workspacedaemon;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent's {@code qits-agent-config} checkout (qits-1382) against a real bare origin, reached
 * over a {@code file://} git base the way the daemon reaches qits-githost.
 */
@EnabledOnOs(OS.LINUX)
class AgentConfigCheckoutTest {

  static final String REPOSITORY_ID = "22222222-2222-2222-2222-222222222222";
  static final String REPOSITORY_PATH = "project-1/qits-agent-config";
  private static final String BRANCH = "ticket/qits-1";
  private static final Map<String, String> ENV = Map.of("QITS_TOKEN", "agent-token");

  @TempDir Path tmp;

  private Path origin;
  private Path seed;
  private Path root;
  private final List<DaemonMessage> sent = new CopyOnWriteArrayList<>();
  private AgentConfigCheckout configs;

  @BeforeEach
  void origin() throws Exception {
    origin = seedOrigin(tmp);
    seed = tmp.resolve("seed-config");
    root = tmp.resolve("workspace").resolve("agent-config");
    configs = new AgentConfigCheckout(root, "file://" + tmp + "/", sent::add);
  }

  @AfterEach
  void close() {
    configs.close();
  }

  /**
   * A bare {@code <tmp>/project-1/qits-agent-config} whose {@code main} holds a memory and a
   * settings file; the seed clone it was pushed from is kept at {@code <tmp>/seed-config}.
   */
  static Path seedOrigin(Path tmp) throws Exception {
    Path origin = Files.createDirectories(tmp.resolve(REPOSITORY_PATH));
    git(origin, "init", "--quiet", "--bare", "--initial-branch=main");
    Path seed = tmp.resolve("seed-config");
    git(tmp, "clone", "--quiet", origin.toString(), seed.toString());
    GitFixtures.identity(seed);
    Files.createDirectories(seed.resolve("memory"));
    Files.writeString(seed.resolve("memory").resolve("MEMORY.md"), "- from main\n");
    Files.writeString(seed.resolve("settings.json"), "{\"enabledPlugins\":{}}\n");
    git(seed, "add", "-A");
    git(seed, "commit", "--quiet", "-m", "seed");
    git(seed, "push", "--quiet", "origin", "HEAD:main");
    return origin;
  }

  private Optional<Path> ensure() {
    return configs.ensure(
        "agent-1",
        BRANCH,
        new AgentConfigCheckout.AgentConfig(REPOSITORY_ID, REPOSITORY_PATH),
        ENV,
        "qits-1");
  }

  private Path gitDir() {
    return root.resolve("agent-1").resolve("git");
  }

  @Test
  void aFreshCheckoutLandsOnMainsContentOnTheAgentsBranch() throws Exception {
    Path tree = ensure().orElseThrow();

    assertEquals(root.resolve("agent-1").resolve("tree"), tree);
    assertEquals("- from main\n", Files.readString(tree.resolve("memory/MEMORY.md")));
    assertEquals(
        BRANCH, line(gitDir(), "--git-dir=" + gitDir(), "symbolic-ref", "--short", "HEAD"));
    assertNoDotGit(tree);
  }

  @Test
  void aBranchTheGitHostAlreadyHasIsCheckedOutInsteadOfMain() throws Exception {
    git(seed, "switch", "--quiet", "-c", BRANCH);
    Files.writeString(seed.resolve("memory/MEMORY.md"), "- from the branch\n");
    git(seed, "commit", "--quiet", "-am", "earlier session");
    git(seed, "push", "--quiet", "origin", BRANCH);

    Path tree = ensure().orElseThrow();

    assertEquals("- from the branch\n", Files.readString(tree.resolve("memory/MEMORY.md")));
    assertFalse(configs.dirty("agent-1"), "nothing the git host lacks");
  }

  @Test
  void aSecondEnsureTouchesNothing() throws Exception {
    Path tree = ensure().orElseThrow();
    Files.writeString(tree.resolve("memory/MEMORY.md"), "- not synced yet\n");

    assertEquals(Optional.of(tree), ensure());
    assertEquals("- not synced yet\n", Files.readString(tree.resolve("memory/MEMORY.md")));
  }

  @Test
  void aCheckoutThatCannotBeMadeLeavesNothingBehind() {
    Optional<Path> tree =
        configs.ensure(
            "agent-1",
            BRANCH,
            new AgentConfigCheckout.AgentConfig(REPOSITORY_ID, "project-1/nowhere"),
            ENV,
            "qits-1");

    assertTrue(tree.isEmpty());
    assertFalse(Files.exists(root.resolve("agent-1")));
    assertEquals(Optional.empty(), configs.tree("agent-1"));
  }

  @Test
  void aSyncCommitsTheThreePathsOnlyAndPushesTheBranch() throws Exception {
    Path tree = ensure().orElseThrow();
    Files.writeString(tree.resolve("memory/MEMORY.md"), "- learned something\n");
    Files.createDirectories(tree.resolve("skills/review"));
    Files.writeString(tree.resolve("skills/review/SKILL.md"), "# review\n");
    Files.writeString(tree.resolve("other.txt"), "never committed\n");
    assertTrue(configs.dirty("agent-1"));

    configs.sync("agent-1");

    String head = line(origin, "rev-parse", BRANCH);
    assertEquals(
        "chore(qits-1): agent memories, skills and plugins",
        line(origin, "log", "-1", "--format=%s", BRANCH));
    assertEquals(
        "memory/MEMORY.md\nsettings.json\nskills/review/SKILL.md",
        line(origin, "ls-tree", "-r", "--name-only", BRANCH));
    assertEquals(List.of(new AgentBranchPushed("agent-1", REPOSITORY_ID, BRANCH, head)), sent);
    assertFalse(configs.dirty("agent-1"), "an untracked file outside the three paths is no work");
    assertTrue(Files.exists(tree.resolve("other.txt")), "and stays where it is");
  }

  @Test
  void aSyncWithNothingChangedMakesNoCommitAndNoPush() throws Exception {
    ensure().orElseThrow();
    String before = line(gitDir(), "--git-dir=" + gitDir(), "rev-parse", BRANCH);

    configs.sync("agent-1");

    assertEquals(before, line(gitDir(), "--git-dir=" + gitDir(), "rev-parse", BRANCH));
    assertEquals("", git(origin, "branch", "--list", BRANCH));
    assertTrue(sent.isEmpty());
  }

  @Test
  void aCommitTheGitHostLacksIsDirtyUntilItIsPushed() throws Exception {
    Path tree = ensure().orElseThrow();
    Files.writeString(tree.resolve("settings.json"), "{\"enabledPlugins\":{\"x@y\":true}}\n");
    git(
        tree,
        "--git-dir=" + gitDir(),
        "--work-tree=" + tree,
        "commit",
        "--quiet",
        "-am",
        "by hand");
    assertTrue(configs.dirty("agent-1"), "committed, not pushed");

    configs.sync("agent-1");

    assertFalse(configs.dirty("agent-1"));
    assertEquals(
        line(gitDir(), "--git-dir=" + gitDir(), "rev-parse", BRANCH),
        line(origin, "rev-parse", BRANCH));
  }

  @Test
  void anAgentWithoutACheckoutIsNeverDirtyAndItsSyncIsANoOp() {
    assertFalse(configs.dirty("agent-1"));
    configs.sync("agent-1");
    assertTrue(sent.isEmpty());
  }

  @Test
  void removeDeletesTheWholeCheckout() throws Exception {
    ensure().orElseThrow();

    configs.remove("agent-1");

    assertFalse(Files.exists(root.resolve("agent-1")));
    assertEquals(Optional.empty(), configs.tree("agent-1"));
  }

  @Test
  void theCommitIsScopedByTheQualifiedWorkIdOrNamesTheAgent() {
    assertEquals(
        "chore(qits-123): agent memories, skills and plugins",
        AgentConfigCheckout.message("qits-123", "agent-1"));
    assertEquals(
        "chore(agent-config): agent-1",
        AgentConfigCheckout.message("00000000-0000-4000-8000-000000000001", "agent-1"));
    assertEquals("chore(agent-config): agent-1", AgentConfigCheckout.message(null, "agent-1"));
  }

  private static void assertNoDotGit(Path tree) throws Exception {
    try (Stream<Path> walk = Files.walk(tree)) {
      assertEquals(
          List.of(),
          walk.filter(p -> p.getFileName().toString().equals(".git")).toList(),
          "the work tree carries no .git");
    }
  }
}
