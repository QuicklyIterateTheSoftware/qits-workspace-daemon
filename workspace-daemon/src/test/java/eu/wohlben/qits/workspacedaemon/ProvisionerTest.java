package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.Provisioner.Env;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Container-free coverage of the {@link Provisioner}'s pure decision helpers — url derivation,
 * name-vs-id addressing, {@code .gitmodules} parsing, and basename normalization. The end-to-end
 * clone + submodule walk (which touches {@code /workspace} and real git) is the extended
 * real-docker IT's job; here we pin the logic that decides <em>what</em> the daemon clones and how
 * it addresses submodule redirects, mirroring {@link WorkspaceDescriberTest}'s parse-only approach.
 */
class ProvisionerTest {

  private static Env env(String projectId, String repoName) {
    return new Env(
        "ws-1",
        "repo-abc",
        "feature",
        projectId,
        repoName,
        "ws://qits:8080/api/workspace-daemon/ws-1");
  }

  @Test
  void gitBaseDerivesHttpFromWsDialHome() {
    assertEquals(
        "http://qits:8080/git", Provisioner.gitBase("ws://qits:8080/api/workspace-daemon/ws-1"));
  }

  @Test
  void gitBaseDerivesHttpsFromWss() {
    assertEquals(
        "https://host:443/git", Provisioner.gitBase("wss://host:443/api/workspace-daemon/x"));
  }

  @Test
  void gitBaseOmitsPortWhenAbsent() {
    assertEquals("http://host/git", Provisioner.gitBase("ws://host/api/workspace-daemon/x"));
  }

  @Test
  void gitBaseNullOnBlankOrUnparseable() {
    assertNull(Provisioner.gitBase(null));
    assertNull(Provisioner.gitBase(""));
    assertNull(Provisioner.gitBase("::::not a url"));
  }

  @Test
  void rootUrlIsNameAddressedWhenProjectScopePresent() {
    assertEquals(
        "http://qits:8080/git/proj-1/my-repo",
        Provisioner.rootUrl("http://qits:8080/git", env("proj-1", "my-repo")));
  }

  @Test
  void rootUrlFallsBackToIdAddressedWhenScopeBlank() {
    assertEquals(
        "http://qits:8080/git/repo-abc", Provisioner.rootUrl("http://qits:8080/git", env("", "")));
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

  @Test
  void basenameStripsGitSuffixAndPath() {
    assertEquals("foo", Provisioner.basename("https://h/o/foo.git"));
    assertEquals("foo", Provisioner.basename("/abs/foo.git"));
    assertEquals("foo", Provisioner.basename("git@host:o/foo.git"));
    assertEquals("foo", Provisioner.basename("../foo.git"));
    assertEquals("foo", Provisioner.basename("foo"));
  }
}
