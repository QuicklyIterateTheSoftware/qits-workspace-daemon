package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.Provisioner.Env;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
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
 * end-to-end clone + submodule walk against a live qits-githost is the extended real-docker IT's
 * job; here we pin the logic that decides <em>what</em> the daemon clones and how it addresses
 * submodule redirects, mirroring {@link WorkspaceDescriberTest}'s parse-only approach.
 *
 * <p>The base-clone cases below do drive real {@code git} against local origins, because what they
 * prove <em>is</em> the clone. They never touch {@code /workspace}: every command the {@link
 * Provisioner} forks addresses its checkout absolutely, so a stated base directory is enough.
 */
class ProvisionerTest {

  private static final String GIT_BASE = "http://qits-githost:8080/git";

  private static Env env(String projectId, String repoName) {
    return env(projectId, repoName, "");
  }

  private static Env env(String projectId, String repoName, String gitBaseUrl) {
    return new Env("ws-1", "repo-abc", projectId, repoName, gitBaseUrl);
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
   * The derivation this replaced built {@code <control-socket authority>/artifacts/git}, a host
   * that serves no git since the byte-plane split. A missing setting has to read as a missing
   * setting.
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
   * The base clone (qits-1152): the wrapper's default branch, cloned into the base directory, and
   * reported with its HEAD. No branch is asked for, because a workspace has none.
   */
  @Test
  void theBaseCloneIsTheWrappersDefaultBranch(@TempDir Path tmp)
      throws IOException, InterruptedException {
    Path gitBase = tmp.resolve("git");
    Path origin = servedWrapper(gitBase, "proj-1", "my-wrapper");
    git(origin, "switch", "--quiet", "-c", "ticket/qits-1");
    git(origin, "commit", "--allow-empty", "-m", "elsewhere");
    git(origin, "switch", "--quiet", "main");
    Path base = tmp.resolve("workspace").resolve("base");
    List<DaemonMessage> out = new ArrayList<>();

    assertTrue(
        Provisioner.provision(
            base.toFile(),
            new Env("ws-1", "repo-abc", "proj-1", "my-wrapper", gitBase.toUri().toString()),
            out::add));

    assertEquals("main", git(base, "rev-parse", "--abbrev-ref", "HEAD").trim());
    assertTrue(
        out.getLast() instanceof Provisioned provisioned
            && provisioned.head().equals(git(origin, "rev-parse", "main").trim()));
    assertTrue(
        git(base, "rev-parse", "--verify", "refs/remotes/origin/ticket/qits-1").length() > 0,
        "every branch head is there for an agent worktree to start from");
  }

  /** A second boot over the same volume keeps the clone: it is never cloned twice. */
  @Test
  void anExistingBaseCloneIsKept(@TempDir Path tmp) throws IOException, InterruptedException {
    Path gitBase = tmp.resolve("git");
    servedWrapper(gitBase, "proj-1", "my-wrapper");
    Path base = tmp.resolve("base");
    Env env = new Env("ws-1", "repo-abc", "proj-1", "my-wrapper", gitBase.toUri().toString());
    assertTrue(Provisioner.provision(base.toFile(), env, ignored -> {}));
    Files.writeString(base.resolve("marker.txt"), "still here");

    assertTrue(Provisioner.provision(base.toFile(), env, ignored -> {}));
    assertTrue(Files.exists(base.resolve("marker.txt")));
  }

  /**
   * Agents share the OS user and could write the base clone's config while the last container ran.
   * The next boot must not run git over it with the workspace's credential.
   */
  @Test
  void aBaseCloneWhoseConfigWasTamperedWithIsNotTouchedAtBoot(@TempDir Path tmp)
      throws IOException, InterruptedException {
    Path gitBase = tmp.resolve("git");
    servedWrapper(gitBase, "proj-1", "my-wrapper");
    Path base = tmp.resolve("base");
    Env env = new Env("ws-1", "repo-abc", "proj-1", "my-wrapper", gitBase.toUri().toString());
    assertTrue(Provisioner.provision(base.toFile(), env, ignored -> {}));
    git(base, "config", "credential.helper", "!cat > " + tmp.resolve("stolen"));
    git(base, "config", "remote.origin.url", "http://evil.example/x");
    List<DaemonMessage> out = new ArrayList<>();

    assertTrue(Provisioner.provision(base.toFile(), env, out::add));

    assertTrue(
        out.stream()
            .anyMatch(
                m ->
                    m instanceof DaemonLog log
                        && "WARN".equals(log.level())
                        && log.message().contains("credential.helper")),
        out.toString());
    assertEquals(
        "http://evil.example/x",
        git(base, "config", "--get", "remote.origin.url").trim(),
        "nothing ran: not even the origin repair, which would be a git call over a planted config");
  }

  /**
   * A served wrapper at the address the clone is built from: {@code <gitBase>/<projectId>/<name>}.
   */
  private static Path servedWrapper(Path gitBase, String projectId, String repoName)
      throws IOException, InterruptedException {
    Path origin = Files.createDirectories(gitBase.resolve(projectId).resolve(repoName));
    git(origin, "init", "--quiet", "--initial-branch=main");
    git(origin, "config", "user.email", "test@example.invalid");
    git(origin, "config", "user.name", "Test");
    Files.writeString(origin.resolve("README.md"), "# " + repoName + "\n");
    git(origin, "add", "README.md");
    git(origin, "commit", "-m", "the wrapper");
    return origin;
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
