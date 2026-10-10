package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Real git repositories for the agent-worktree tests: a wrapper and a submodule, each with a bare
 * origin, and a base clone with the submodule materialized — the layout {@link Provisioner} leaves
 * at {@code /workspace/base}, built here without a git host.
 */
final class GitFixtures {

  /** The wrapper's name, its directory under each agent, and the {@code repository} reported. */
  static final String WRAPPER = "wrapper";

  /** The submodule's path in the wrapper; its origin is {@code child.git}, so its name is child. */
  static final String CHILD = "libs/child";

  private GitFixtures() {}

  /** Run git in {@code dir}, failing the test when it fails; answers stdout and stderr. */
  static String git(Path dir, String... args) throws IOException, InterruptedException {
    List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toString()));
    argv.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), String.join(" ", argv) + "\n" + output);
    return output;
  }

  /** Same, answering stdout trimmed. */
  static String line(Path dir, String... args) throws IOException, InterruptedException {
    return git(dir, args).strip();
  }

  /** The two bare origins under {@code tmp}, the base clone, and its git identity. */
  record Estate(Path wrapperOrigin, Path childOrigin, Path base, Path agents) {}

  static Estate estate(Path tmp) throws IOException, InterruptedException {
    Path childOrigin = bare(tmp.resolve("child.git"));
    Path wrapperOrigin = bare(tmp.resolve("wrapper.git"));

    Path seed = tmp.resolve("seed-child");
    git(tmp, "clone", "--quiet", childOrigin.toString(), seed.toString());
    identity(seed);
    Files.writeString(seed.resolve("lib.txt"), "child\n");
    git(seed, "add", "lib.txt");
    git(seed, "commit", "--quiet", "-m", "child");
    git(seed, "push", "--quiet", "origin", "HEAD:main");

    Path wrapperSeed = tmp.resolve("seed-wrapper");
    git(tmp, "clone", "--quiet", wrapperOrigin.toString(), wrapperSeed.toString());
    identity(wrapperSeed);
    Files.writeString(wrapperSeed.resolve("README.md"), "# wrapper\n");
    git(wrapperSeed, "add", "README.md");
    git(
        wrapperSeed,
        "-c",
        "protocol.file.allow=always",
        "submodule",
        "add",
        "--quiet",
        "--name",
        "child",
        childOrigin.toString(),
        CHILD);
    git(wrapperSeed, "commit", "--quiet", "-m", "wrapper");
    git(wrapperSeed, "push", "--quiet", "origin", "HEAD:main");

    Path base = tmp.resolve("workspace").resolve("base");
    git(tmp, "clone", "--quiet", wrapperOrigin.toString(), base.toString());
    git(base, "-c", "protocol.file.allow=always", "submodule", "update", "--init", "--quiet");
    identity(base);
    identity(base.resolve(CHILD));
    return new Estate(wrapperOrigin, childOrigin, base, tmp.resolve("workspace").resolve("agents"));
  }

  /** A commit straight on the child's origin, as a release merging into main would make. */
  static String advanceChildOrigin(Path tmp, Estate estate)
      throws IOException, InterruptedException {
    Path seed = tmp.resolve("seed-child");
    git(seed, "pull", "--quiet", "origin", "main");
    Files.writeString(seed.resolve("lib.txt"), "released\n");
    git(seed, "commit", "--quiet", "-am", "released");
    git(seed, "push", "--quiet", "origin", "HEAD:main");
    return line(seed, "rev-parse", "HEAD");
  }

  private static Path bare(Path dir) throws IOException, InterruptedException {
    Files.createDirectories(dir);
    git(dir, "init", "--quiet", "--bare", "--initial-branch=main");
    return dir;
  }

  static void identity(Path repository) throws IOException, InterruptedException {
    git(repository, "config", "user.email", "agent@example.invalid");
    git(repository, "config", "user.name", "Agent");
  }
}
