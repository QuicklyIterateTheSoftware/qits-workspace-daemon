package eu.wohlben.qits.workspacedaemon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs one {@code git} command in a stated directory and keeps stdout and stderr apart.
 *
 * <p>The agent-worktree code parses stdout ({@code worktree list --porcelain}, {@code rev-parse})
 * and must not see a warning in it; {@link Out#message()} joins both for a log or a push
 * classification.
 *
 * <p><b>No git process the daemon starts carries a credential it does not need</b> (qits-1152).
 * Agents share the OS user and the repositories' config with the daemon, so a git process can run
 * code an agent planted: a hook, an fsmonitor, a clean or smudge filter. Three rules keep that code
 * away from the secrets:
 *
 * <ul>
 *   <li>Every process starts from the daemon's environment with every secret removed ({@link
 *       #SECRETS}, and any name with {@code SECRET} or {@code PASSWORD} in it). A {@link #git} call
 *       carries none.
 *   <li>Every process runs with hooks and fsmonitor switched off on the command line, which wins
 *       over any repository config.
 *   <li>Only a {@link #network} call carries a credential, and only the one its caller names: the
 *       workspace's for a fetch, one agent's for a push of that agent's branch. The caller checks
 *       the repository's config first ({@link GitConfigGuard}).
 * </ul>
 */
final class GitExec {

  /** One finished git process. */
  record Out(int exit, String stdout, String stderr) {
    boolean ok() {
      return exit == 0;
    }

    /** stdout without its trailing newline. */
    String line() {
      return stdout.strip();
    }

    /** stdout and stderr on one line, for a log or an error message. */
    String message() {
      return (stdout + " " + stderr).strip().replace('\n', ' ');
    }
  }

  /**
   * The environment names that carry a secret of the workspace: its own token, its commissioned
   * client, the API token every daemon route needs, and the agent configuration document, which
   * holds external MCP servers' header values. Removed from every git process, and blanked in every
   * harness process ({@link AgentScopedCommands}).
   */
  static final List<String> SECRETS =
      List.of(
          "QITS_TOKEN",
          "QITS_COMMISSIONED_CLIENT_ID",
          "QITS_COMMISSIONED_CLIENT_SECRET",
          "QITS_WORKSPACE_DAEMON_TOKEN",
          "QITS_WORKSPACE_DAEMON_API_TOKEN",
          "QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION");

  /** The names a credential for the git host is read from by the image's credential helper. */
  static final List<String> GIT_CREDENTIALS =
      List.of("QITS_TOKEN", "QITS_COMMISSIONED_CLIENT_ID", "QITS_COMMISSIONED_CLIENT_SECRET");

  /** Off on every call: neither may run code an agent planted in a shared repository. */
  private static final List<String> SAFETY =
      List.of("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false");

  private static final long TIMEOUT_SECONDS = 300;

  private GitExec() {}

  /** Whether {@code name} names a secret no git process and no harness may inherit. */
  static boolean secret(String name) {
    String upper = name.toUpperCase(java.util.Locale.ROOT);
    return SECRETS.contains(name) || upper.contains("SECRET") || upper.contains("PASSWORD");
  }

  /** A local git call: no credential at all. */
  static Out git(Path dir, String... args) {
    return run(dir, Map.of(), args);
  }

  /**
   * A call that talks to the git host, carrying exactly {@code credential} — the names in {@link
   * #GIT_CREDENTIALS} and nothing else, so a caller cannot widen it by accident.
   */
  static Out network(Path dir, Map<String, String> credential, String... args) {
    Map<String, String> only = new java.util.HashMap<>();
    for (String name : GIT_CREDENTIALS) {
      String value = credential.get(name);
      if (value != null && !value.isBlank()) {
        only.put(name, value);
      }
    }
    return run(dir, only, args);
  }

  /** The workspace's own git credential, from the daemon's environment. */
  static Map<String, String> workspaceCredential() {
    Map<String, String> credential = new java.util.HashMap<>();
    for (String name : GIT_CREDENTIALS) {
      String value = System.getenv(name);
      if (value != null && !value.isBlank()) {
        credential.put(name, value);
      }
    }
    return credential;
  }

  /**
   * The environment a git process gets: the daemon's, minus every secret, plus {@code credential}.
   * Package-private so a test can read it.
   */
  static Map<String, String> environment(
      Map<String, String> inherited, Map<String, String> credential) {
    Map<String, String> env = new java.util.HashMap<>(inherited);
    env.keySet().removeIf(GitExec::secret);
    env.putAll(credential);
    // The image's global config names the credential helper (GIT_CONFIG_GLOBAL, root-owned).
    // Without
    // it git would read $HOME/.gitconfig, and HOME is the workspace volume every agent can write.
    if (!env.containsKey("GIT_CONFIG_GLOBAL")) {
      env.put("GIT_CONFIG_GLOBAL", "/dev/null");
    }
    // Never wait on a prompt: a missing credential must fail, not hang the sync thread.
    env.put("GIT_TERMINAL_PROMPT", "0");
    return env;
  }

  private static Out run(Path dir, Map<String, String> credential, String... args) {
    List<String> argv = new ArrayList<>(args.length + SAFETY.size() + 1);
    argv.add("git");
    argv.addAll(SAFETY);
    argv.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(argv);
    if (dir != null && dir.toFile().isDirectory()) {
      builder.directory(dir.toFile());
    }
    Map<String, String> env = environment(builder.environment(), credential);
    builder.environment().clear();
    builder.environment().putAll(env);
    builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      return new Out(127, "", String.valueOf(e.getMessage()));
    }
    CompletableFuture<String> stderr =
        CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
    String stdout = read(process.getInputStream());
    try {
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new Out(-1, stdout, "timed out after " + TIMEOUT_SECONDS + "s");
      }
      return new Out(process.exitValue(), stdout, stderr.join());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return new Out(130, stdout, "interrupted");
    }
  }

  private static String read(InputStream stream) {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      // The process died under us; its exit code carries the outcome.
      return "";
    }
  }
}
