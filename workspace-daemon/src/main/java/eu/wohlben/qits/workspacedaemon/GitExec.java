package eu.wohlben.qits.workspacedaemon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 *
 * <p><b>Every call ends by its deadline</b> (qits-2365). Both pipes are drained on {@link #DRAIN}
 * while this thread waits on the process with the deadline, and a process still running at it is
 * killed with every descendant. The order used to be the other way round: stdout was read to EOF
 * first, and git holds stdout open until it exits, so a push or fetch whose transfer stalled
 * blocked the caller for good and the deadline was never consulted — one stalled push silenced
 * every other repository's on the one origin-sync thread. A {@link #network} call is bounded twice:
 * libcurl aborts a transfer that stays below {@link Provisioner#lowSpeedLimitBytesPerSecond} for
 * {@link Provisioner#lowSpeedTimeSeconds}, the same window the provisioner's clones use (qits-1750,
 * qits-2172), and the wall-clock deadline catches whatever that does not, a stalled local hook
 * included.
 */
final class GitExec {

  /** One finished git process. */
  record Out(int exit, String stdout, String stderr) {
    boolean ok() {
      return exit == 0;
    }

    /** Whether the process was killed at its deadline; {@link #message()} then says "timed out". */
    boolean timedOut() {
      return exit == TIMED_OUT;
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
   * The environment names that carry a secret of the workspace: its own token, the API token every
   * daemon route needs, and the agent configuration document, which holds external MCP servers'
   * header values. Removed from every git process, and blanked in every harness process ({@link
   * AgentScopedCommands}). Not the whole scrub: {@link #secret} also takes any name that says
   * {@code SECRET} or {@code PASSWORD}, so a secret a container inherits under a name nobody listed
   * here is scrubbed all the same.
   */
  static final List<String> SECRETS =
      List.of(
          "QITS_TOKEN",
          "QITS_WORKSPACE_DAEMON_TOKEN",
          "QITS_WORKSPACE_DAEMON_API_TOKEN",
          "QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION");

  /** The names a credential for the git host is read from by the image's credential helper. */
  static final List<String> GIT_CREDENTIALS = List.of("QITS_TOKEN");

  /** The commit identity names: an agent's harness env carries its own (qits-1666). */
  static final List<String> GIT_IDENTITY =
      List.of("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL");

  /** Off on every call: neither may run code an agent planted in a shared repository. */
  private static final List<String> SAFETY =
      List.of("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false");

  /** The deadline of a call that names none. */
  static final Duration TIMEOUT = Duration.ofSeconds(300);

  /** The exit code of a call killed at its deadline. */
  static final int TIMED_OUT = -1;

  /**
   * How long the pipes may stay open after git exited: an orphan it left behind (a detached
   * maintenance run) could hold them for good, and its output is not git's.
   */
  private static final Duration DRAIN_GRACE = Duration.ofSeconds(5);

  /**
   * Drains stdout and stderr, two threads per running call. Its own pool, never the common one: a
   * drain blocks for the life of the process, and the common pool is sized for work that does not.
   */
  private static final ExecutorService DRAIN =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-git-drain");
            thread.setDaemon(true);
            return thread;
          });

  private GitExec() {}

  /** Whether {@code name} names a secret no git process and no harness may inherit. */
  static boolean secret(String name) {
    String upper = name.toUpperCase(java.util.Locale.ROOT);
    return SECRETS.contains(name) || upper.contains("SECRET") || upper.contains("PASSWORD");
  }

  /** A local git call: no credential at all. */
  static Out git(Path dir, String... args) {
    return run(dir, Map.of(), TIMEOUT, args);
  }

  /**
   * A local call that commits as {@code identity}: the names in {@link #GIT_IDENTITY} and nothing
   * else, laid over the daemon's own (which is the platform's).
   */
  static Out committing(Path dir, Map<String, String> identity, String... args) {
    return run(dir, only(identity, GIT_IDENTITY), TIMEOUT, args);
  }

  /** The entries of {@code env} under {@code names} that hold a value. */
  static Map<String, String> only(Map<String, String> env, List<String> names) {
    Map<String, String> only = new java.util.HashMap<>();
    for (String name : names) {
      String value = env == null ? null : env.get(name);
      if (value != null && !value.isBlank()) {
        only.put(name, value);
      }
    }
    return only;
  }

  /**
   * A call that talks to the git host, carrying exactly {@code credential} — the names in {@link
   * #GIT_CREDENTIALS} and nothing else, so a caller cannot widen it by accident.
   */
  static Out network(Path dir, Map<String, String> credential, String... args) {
    return network(dir, credential, TIMEOUT, args);
  }

  /** The same, killed at {@code timeout} rather than at {@link #TIMEOUT}. */
  static Out network(Path dir, Map<String, String> credential, Duration timeout, String... args) {
    Map<String, String> env = new java.util.HashMap<>(only(credential, GIT_CREDENTIALS));
    env.put("GIT_HTTP_LOW_SPEED_LIMIT", String.valueOf(Provisioner.lowSpeedLimitBytesPerSecond));
    env.put("GIT_HTTP_LOW_SPEED_TIME", String.valueOf(Provisioner.lowSpeedTimeSeconds));
    return run(dir, env, timeout, args);
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

  /**
   * Run {@code git} with {@code env} laid over the scrubbed environment, killed with its
   * descendants at {@code timeout}. Package-private so a test can give it a short deadline.
   */
  static Out run(Path dir, Map<String, String> env, Duration timeout, String... args) {
    List<String> argv = new ArrayList<>(args.length + SAFETY.size() + 1);
    argv.add("git");
    argv.addAll(SAFETY);
    argv.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(argv);
    if (dir != null && dir.toFile().isDirectory()) {
      builder.directory(dir.toFile());
    }
    Map<String, String> merged = environment(builder.environment(), env);
    builder.environment().clear();
    builder.environment().putAll(merged);
    builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      return new Out(127, "", String.valueOf(e.getMessage()));
    }
    Drain stdout = new Drain(process.getInputStream());
    Drain stderr = new Drain(process.getErrorStream());
    Future<?> stdoutDone = DRAIN.submit(stdout);
    Future<?> stderrDone = DRAIN.submit(stderr);
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
        kill(process);
        settle(stdoutDone, DRAIN_GRACE.toNanos());
        settle(stderrDone, DRAIN_GRACE.toNanos());
        return new Out(
            TIMED_OUT,
            stdout.text(),
            ("timed out after " + timeout.toSeconds() + "s " + stderr.text()).strip());
      }
      long grace = Math.max(0, Math.min(DRAIN_GRACE.toNanos(), deadline - System.nanoTime()));
      settle(stdoutDone, grace);
      settle(stderrDone, grace);
      return new Out(process.exitValue(), stdout.text(), stderr.text());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      kill(process);
      return new Out(130, stdout.text(), "interrupted");
    }
  }

  /** Kill {@code process} and everything it started, children first so none is orphaned. */
  private static void kill(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /** Wait up to {@code nanos} for a drain; one still blocked keeps what it has read so far. */
  private static void settle(Future<?> drain, long nanos) throws InterruptedException {
    try {
      drain.get(nanos, TimeUnit.NANOSECONDS);
    } catch (ExecutionException | TimeoutException e) {
      // An orphan holds the pipe, or the process died under the read: what was read is the output.
    }
  }

  /** Reads one pipe to EOF, keeping what it has read readable while it still runs. */
  private static final class Drain implements Runnable {
    private final InputStream stream;
    private final ByteArrayOutputStream read = new ByteArrayOutputStream();

    Drain(InputStream stream) {
      this.stream = stream;
    }

    @Override
    public void run() {
      byte[] buffer = new byte[8192];
      try (stream) {
        for (int n; (n = stream.read(buffer)) >= 0; ) {
          read.write(buffer, 0, n);
        }
      } catch (IOException e) {
        // The process died under us; its exit code carries the outcome.
      }
    }

    String text() {
      return read.toString(StandardCharsets.UTF_8);
    }
  }
}
