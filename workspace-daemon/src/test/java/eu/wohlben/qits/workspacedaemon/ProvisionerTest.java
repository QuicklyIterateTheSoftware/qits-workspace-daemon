package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.Provisioner.Env;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Container-free coverage of the {@link Provisioner}'s pure decision helpers — the git base,
 * project-scoped vs. id addressing, {@code .gitmodules} parsing, and basename normalization. The
 * end-to-end
 * clone + submodule walk (which touches {@code /workspace} and real git) is the extended
 * real-docker IT's job; here we pin the logic that decides <em>what</em> the daemon clones and how
 * it addresses submodule redirects, mirroring {@link WorkspaceDescriberTest}'s parse-only approach.
 */
class ProvisionerTest {

  private static final String GIT_BASE = "http://qits-githost:8080/git";

  private static Env env(String projectId, String repoName) {
    return env(projectId, repoName, "");
  }

  private static Env env(String projectId, String repoName, String gitBaseUrl) {
    return new Env("ws-1", "repo-abc", "feature", projectId, repoName, gitBaseUrl);
  }

  /** Collects the messages a decision emits, so a silent refusal fails the test. */
  private static List<DaemonMessage> emitted(Env env) {
    List<DaemonMessage> out = new ArrayList<>();
    Provisioner.gitBase(env, out::add);
    return out;
  }

  @Test
  void anInjectedGitBaseIsTakenSilently() {
    Env env = env("proj-1", "my-repo", GIT_BASE + "/");

    assertEquals(
        GIT_BASE,
        Provisioner.gitBase(env, m -> {}),
        "trailing slash trimmed, so rootUrl never doubles it");
    assertTrue(
        emitted(env).isEmpty(), "a configured host is not an assumption worth warning about");
  }

  /**
   * The derivation this replaced built {@code <control-socket authority>/artifacts/git}, a host that
   * serves no git since the byte-plane split. A missing setting has to read as a missing setting.
   */
  @Test
  void noInjectedGitBaseRefusesToCloneRatherThanGuessingAHost() {
    Env env = env("proj-1", "my-repo");

    assertNull(Provisioner.gitBase(env, m -> {}));
    assertTrue(
        emitted(env).stream()
            .anyMatch(
                m ->
                    m instanceof DaemonLog log
                        && "WARN".equals(log.level())
                        && log.message().contains("qits.workspace-daemon.git-base-url")),
        "the refusal names the key nobody set");
  }

  @Test
  void rootUrlIsProjectScopedWhenBothScopeValuesArePresent() {
    assertEquals(
        GIT_BASE + "/proj-1/my-repo", Provisioner.rootUrl(GIT_BASE, env("proj-1", "my-repo")));
  }

  /**
   * The public address is {@code (projectId, repoName)}; half of it addresses nothing. A container
   * created before the scoped form shipped carries the id-addressed storage route instead — the one
   * route this daemon can still prove exists.
   */
  @Test
  void rootUrlFallsBackToTheIdAddressedRouteWhenEitherHalfIsBlank() {
    assertEquals(GIT_BASE + "/repo-abc", Provisioner.rootUrl(GIT_BASE, env("", "")));
    assertEquals(GIT_BASE + "/repo-abc", Provisioner.rootUrl(GIT_BASE, env("proj-1", "")));
    assertEquals(GIT_BASE + "/repo-abc", Provisioner.rootUrl(GIT_BASE, env("", "my-repo")));
    assertFalse(Provisioner.nameAddressed(env("proj-1", "")));
    assertFalse(Provisioner.nameAddressed(env("", "my-repo")));
    assertTrue(Provisioner.nameAddressed(env("proj-1", "my-repo")));
  }

  /** A project's repositories are siblings under the project segment, never below the bare base. */
  @Test
  void aSubmoduleIsRedirectedToTheSiblingUnderTheSameProjectSegment() {
    assertEquals(
        GIT_BASE + "/proj-1/sibling",
        Provisioner.siblingUrl(GIT_BASE, env("proj-1", "my-repo"), "../sibling.git"));
    assertEquals(
        GIT_BASE + "/proj-1/sibling",
        Provisioner.siblingUrl(GIT_BASE, env("proj-1", "my-repo"), "https://github.com/o/sibling"),
        "an external absolute origin stays inside this project's imported siblings");
    assertEquals(
        GIT_BASE + "/sibling",
        Provisioner.siblingUrl(GIT_BASE, env("", ""), "../sibling.git"),
        "an id-addressed checkout keeps the flat form its storage-id-equals-name world serves");
  }

  @Test
  void aPreservedCheckoutIsRetargetedToTheProjectScopedOrigin(@TempDir Path checkout)
      throws IOException, InterruptedException {
    Env env = env("proj-1", "my-repo");
    git(checkout, "init");
    git(checkout, "remote", "add", "origin", GIT_BASE + "/legacy-uuid");

    assertTrue(
        Provisioner.alignExistingCheckoutOrigin(checkout.toFile(), GIT_BASE, env, ignored -> {}));
    assertEquals(
        GIT_BASE + "/proj-1/my-repo",
        git(checkout, "remote", "get-url", "origin").trim(),
        "a preserved volume must not keep resolving relative submodules beside its UUID");
  }

  /**
   * The flat form a pre-cutover container was handed. Retargeting it needs both scope values, and
   * with either absent the inherited origin is the only route left — so it stays.
   */
  @Test
  void aPreservedCheckoutWithoutBothScopeValuesKeepsItsOrigin(@TempDir Path checkout)
      throws IOException, InterruptedException {
    git(checkout, "init");
    git(checkout, "remote", "add", "origin", GIT_BASE + "/my-repo");

    assertTrue(
        Provisioner.alignExistingCheckoutOrigin(
            checkout.toFile(), GIT_BASE, env("", "my-repo"), ignored -> {}));
    assertEquals(GIT_BASE + "/my-repo", git(checkout, "remote", "get-url", "origin").trim());
  }

  private static String git(Path directory, String... arguments)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), output);
    return output;
  }

  /** No checkout is better than a checkout from a host nobody named. */
  @Test
  void aProvisionWithNoGitBaseFailsInsteadOfCloning() {
    List<DaemonMessage> out = new ArrayList<>();

    assertFalse(Provisioner.provision(env("proj-1", "my-repo"), out::add));
    assertTrue(
        out.getLast() instanceof ProvisionFailed failed
            && failed.message().contains("qits.workspace-daemon.git-base-url"));
  }

  /**
   * Git's own rule for a relative submodule url, which is <b>not</b> {@link URI#resolve} — it
   * treats the superproject remote as a directory and {@code ../} drops one whole segment, where
   * RFC 3986 would first discard {@code my-repo} as a filename and land a level too high.
   *
   * <p>Verified against real git rather than assumed: a superproject whose {@code origin} is {@code
   * http://qits-githost:8080/git/proj-1/my-repo} with {@code submodule.sib.url=../sib} resolves,
   * under {@code git submodule sync}, to {@code http://qits-githost:8080/git/proj-1/sib}.
   */
  private static String gitRelative(String remote, String relative) {
    return remote.substring(0, remote.lastIndexOf('/')) + "/" + relative.substring("../".length());
  }

  /**
   * A relative submodule url has to land on a repository of the <b>same project</b>. That works
   * because the project segment sits above the repository name in the clone url, so dropping one
   * segment keeps it.
   */
  @Test
  void aRelativeSubmoduleUrlStaysInsideTheProjectSegment() {
    assertEquals(
        GIT_BASE + "/proj-1/sibling",
        gitRelative(Provisioner.rootUrl(GIT_BASE, env("proj-1", "my-repo")), "../sibling"),
        "a relative submodule url lands on this project's sibling repository");
    assertEquals(
        GIT_BASE + "/sibling",
        gitRelative(Provisioner.rootUrl(GIT_BASE, env("", "")), "../sibling"),
        "id-addressed: one segment below the base, as the flat world served it");
  }

  @Test
  void parseSubmodulesReadsNameAndPathIgnoringJunk() {
    String getRegexp =
        "submodule.child-a.path child-a\n"
            + "submodule.shared.path libs/shared\n"
            + "\n"
            + "not-a-submodule-line\n";
    List<?> subs = Provisioner.parseSubmodules(getRegexp);
    assertEquals(2, subs.size());
    assertTrue(subs.toString().contains("child-a"));
    assertTrue(subs.toString().contains("libs/shared"));
  }

  /**
   * The aggregate case: the host created the workspace branch in this sibling too, so the
   * materialized submodule has to leave the gitlink and follow it — a detached checkout can commit
   * to nothing.
   */
  @Test
  void aSubmoduleWhoseOriginCarriesTheWorkspaceBranchFollowsIt(@TempDir Path tmp)
      throws IOException, InterruptedException {
    Path origin = originWith(tmp, "adhoc-changes");
    Path child = materializedAtGitlink(tmp, origin);

    Provisioner.checkoutWorkspaceBranch(child.toString(), "adhoc-changes", ignored -> {});

    assertEquals("adhoc-changes", git(child, "rev-parse", "--abbrev-ref", "HEAD").trim());
    assertEquals(
        git(origin, "rev-parse", "adhoc-changes").trim(),
        git(child, "rev-parse", "HEAD").trim(),
        "the branch tip, not the recorded gitlink");
  }

  /** Every ordinary workspace's case, and it must stay exactly as it was. */
  @Test
  void aSubmoduleWithoutThatBranchKeepsItsDetachedGitlink(@TempDir Path tmp)
      throws IOException, InterruptedException {
    Path origin = originWith(tmp, "adhoc-changes");
    Path child = materializedAtGitlink(tmp, origin);
    String gitlink = git(child, "rev-parse", "HEAD").trim();

    Provisioner.checkoutWorkspaceBranch(child.toString(), "some-other-workspace", ignored -> {});

    assertEquals("HEAD", git(child, "rev-parse", "--abbrev-ref", "HEAD").trim(), "still detached");
    assertEquals(gitlink, git(child, "rev-parse", "HEAD").trim());
  }

  /**
   * A container recreate preserves {@code /workspace}, so provisioning runs again over a checkout
   * that may hold commits nobody pushed. Selecting the branch must never move it.
   */
  @Test
  void anExistingLocalBranchKeepsItsUnpushedCommits(@TempDir Path tmp)
      throws IOException, InterruptedException {
    Path origin = originWith(tmp, "adhoc-changes");
    Path child = materializedAtGitlink(tmp, origin);
    git(child, "switch", "--create", "adhoc-changes", "--track", "origin/adhoc-changes");
    git(child, "commit", "--allow-empty", "-m", "work nobody pushed yet");
    String unpushed = git(child, "rev-parse", "HEAD").trim();
    git(origin, "switch", "adhoc-changes");
    git(origin, "commit", "--allow-empty", "-m", "meanwhile, on the host");

    Provisioner.checkoutWorkspaceBranch(child.toString(), "adhoc-changes", ignored -> {});

    assertEquals(unpushed, git(child, "rev-parse", "HEAD").trim(), "a force-move would lose this");
    assertEquals(
        git(origin, "rev-parse", "adhoc-changes").trim(),
        git(child, "rev-parse", "refs/remotes/origin/adhoc-changes").trim(),
        "the remote tip is still fetched, so the user can merge it");
  }

  /**
   * An origin that cannot answer reads exactly like an origin without the branch. Only the log
   * separates them, and without it a workspace nobody can commit from looks provisioned.
   */
  @Test
  void anUnreachableOriginSaysSoRatherThanReadingAsNoSuchBranch(@TempDir Path tmp)
      throws IOException, InterruptedException {
    Path origin = originWith(tmp, "adhoc-changes");
    Path child = materializedAtGitlink(tmp, origin);
    git(child, "remote", "set-url", "origin", tmp.resolve("gone").toString());
    List<DaemonMessage> log = new ArrayList<>();

    Provisioner.checkoutWorkspaceBranch(child.toString(), "adhoc-changes", log::add);

    assertEquals("HEAD", git(child, "rev-parse", "--abbrev-ref", "HEAD").trim());
    assertTrue(
        log.stream().anyMatch(m -> m instanceof DaemonLog entry && "WARN".equals(entry.level())),
        "the question could not be asked, and that is not the same as a no");
  }

  /** A branch name is a bare git argument here, so it may never start reading as an option. */
  @Test
  void aBranchNameThatWouldReadAsAnOptionIsRefused(@TempDir Path tmp)
      throws IOException, InterruptedException {
    Path origin = originWith(tmp, "adhoc-changes");
    Path child = materializedAtGitlink(tmp, origin);
    String gitlink = git(child, "rev-parse", "HEAD").trim();

    Provisioner.checkoutWorkspaceBranch(child.toString(), "--orphan", ignored -> {});

    assertEquals(gitlink, git(child, "rev-parse", "HEAD").trim());
  }

  /** A repository serving {@code main} plus {@code branch}, with a commit on each. */
  private static Path originWith(Path tmp, String branch) throws IOException, InterruptedException {
    Path origin = Files.createDirectories(tmp.resolve("origin"));
    git(origin, "init", "--quiet", "--initial-branch=main");
    git(origin, "config", "user.email", "test@example.invalid");
    git(origin, "config", "user.name", "Test");
    git(origin, "commit", "--allow-empty", "-m", "first");
    git(origin, "branch", branch);
    git(origin, "switch", "--quiet", branch);
    git(origin, "commit", "--allow-empty", "-m", "branch work");
    git(origin, "switch", "--quiet", "main");
    return origin;
  }

  /** What {@code git submodule update --init} leaves behind: a clone detached at the gitlink. */
  private static Path materializedAtGitlink(Path tmp, Path origin)
      throws IOException, InterruptedException {
    Path child = tmp.resolve("child");
    git(tmp, "clone", "--quiet", origin.toString(), child.toString());
    git(child, "config", "user.email", "test@example.invalid");
    git(child, "config", "user.name", "Test");
    git(child, "switch", "--quiet", "--detach", "main");
    return child;
  }

  @Test
  void basenameStripsGitSuffixAndPath() {
    assertEquals("foo", Provisioner.basename("https://h/o/foo.git"));
    assertEquals("foo", Provisioner.basename("/abs/foo.git"));
    assertEquals("foo", Provisioner.basename("git@host:o/foo.git"));
    assertEquals("foo", Provisioner.basename("../foo.git"));
    assertEquals("foo", Provisioner.basename("foo"));
  }
}
