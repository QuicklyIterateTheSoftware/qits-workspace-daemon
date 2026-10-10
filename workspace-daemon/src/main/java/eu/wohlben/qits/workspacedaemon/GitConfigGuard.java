package eu.wohlben.qits.workspacedaemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Refuses to talk to the git host from a repository whose config an agent could have turned against
 * a credential (qits-1152).
 *
 * <p>Agents share the OS user with the daemon, so they can write the base clone's config, which
 * every agent worktree shares. A {@code credential.helper} there would be handed the next agent's
 * token on {@code store}; an {@code http.proxy} or a {@code url.*.insteadOf} would carry it to
 * another address; a changed {@code remote.origin.url} would push one agent's branch with another
 * agent's credential to wherever it points. None of these is ever set by the daemon, so their
 * presence in a repository's own config ({@code --local}, {@code --worktree}) means somebody wrote
 * them: the daemon then fetches and pushes nothing in that repository and says so.
 *
 * <p>The global config ({@code GIT_CONFIG_GLOBAL}, root-owned in the image) and the system config
 * are trusted; {@link GitExec} points the global one at {@code /dev/null} when it is unset.
 */
final class GitConfigGuard {

  /** Key prefixes no repository config of the daemon's may carry; lower case, as git reports. */
  private static final List<String> DENIED =
      List.of(
          "url.",
          "http.",
          "https.",
          "credential",
          "include.",
          "includeif.",
          "core.sshcommand",
          "core.askpass",
          "core.gitproxy",
          "core.hookspath",
          "core.fsmonitor",
          "protocol.",
          "remote.origin.pushurl",
          "remote.origin.proxy",
          "remote.origin.uploadpack",
          "remote.origin.receivepack",
          "remote.origin.vcs");

  private GitConfigGuard() {}

  /**
   * What is wrong with {@code repository}'s own config, empty when nothing is.
   *
   * @param expectedOrigin the origin url the daemon set, or null to skip that check
   */
  static List<String> violations(Path repository, String expectedOrigin) {
    List<String> found = new ArrayList<>();
    for (String scope : List.of("--local", "--worktree")) {
      GitExec.Out listed = GitExec.git(repository, "config", scope, "--includes", "--list");
      if (listed.ok()) {
        check(listed.stdout(), expectedOrigin, found);
      }
    }
    return found.stream().distinct().toList();
  }

  /** Every {@code config} and {@code config.worktree} file under a git dir, checked as above. */
  static List<String> violationsUnder(Path gitDir) {
    List<String> found = new ArrayList<>();
    if (!Files.isDirectory(gitDir)) {
      return found;
    }
    try (Stream<Path> walk = Files.walk(gitDir, 12)) {
      for (Path file :
          walk.filter(Files::isRegularFile)
              .filter(
                  f ->
                      f.getFileName().toString().equals("config")
                          || f.getFileName().toString().equals("config.worktree"))
              .toList()) {
        GitExec.Out listed =
            GitExec.git(gitDir, "config", "--file", file.toString(), "--includes", "--list");
        List<String> here = new ArrayList<>();
        check(listed.stdout(), null, here);
        here.forEach(v -> found.add(gitDir.relativize(file) + ": " + v));
      }
    } catch (IOException e) {
      found.add("could not read " + gitDir + ": " + e.getMessage());
    }
    return found;
  }

  private static void check(String list, String expectedOrigin, List<String> found) {
    for (String line : list.split("\n")) {
      int eq = line.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = line.substring(0, eq).toLowerCase(Locale.ROOT);
      String value = line.substring(eq + 1);
      if (denied(key, value)) {
        found.add(key);
      } else if (expectedOrigin != null
          && key.equals("remote.origin.url")
          && !value.equals(expectedOrigin)) {
        found.add("remote.origin.url");
      }
    }
  }

  /** Whether {@code key} (lower case) may not be set in a repository's own config. */
  static boolean denied(String key, String value) {
    for (String prefix : DENIED) {
      if (key.startsWith(prefix)) {
        return true;
      }
    }
    // A submodule update command runs on `submodule update`, which the base clone's boot runs.
    return key.startsWith("submodule.") && key.endsWith(".update") && value.startsWith("!");
  }
}
