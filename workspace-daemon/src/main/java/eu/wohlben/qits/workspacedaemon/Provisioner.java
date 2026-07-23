package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The <b>autonomous self-clone</b>: on boot the workspace-daemon clones {@code /workspace} for its
 * own repository/branch and materializes submodules — entirely from its injected env, with no
 * instruction from qits — then emits the terminal {@link Provisioned} (with the checked-out {@code
 * HEAD}) or {@link ProvisionFailed}. qits only awaits that event (docs/epics/qits-workspace-daemon/
 * Part 1). Framework-free (no Vert.x, no CDI, no JGit — native-image lean) so it forks the {@code
 * git} CLI via {@link ProcessBuilder}, mirroring {@link WorkspaceDescriber}, and unit-tests
 * directly against a collecting {@code Consumer}.
 *
 * <p>The clone is name-addressed ({@code <gitBase>/<projectId>/<repoName>}, derived from the
 * dial-home url + the {@code …_PROJECT_ID}/{@code …_REPO_NAME} env) so committed <b>relative</b>
 * submodule urls resolve natively against the served project siblings; an <b>absolute</b> committed
 * url is redirected to the name-addressed sibling by basename. Submodules are discovered from the
 * checkout's own {@code .gitmodules} in a bounded, depth-capped walk (the daemon has no DB) — a
 * submodule that can't be fetched (e.g. one never imported into the project) is skipped with a
 * warning rather than failing the whole provision. An existing checkout (reconnect after a restart)
 * is never re-cloned: it re-emits {@link Provisioned} from the current {@code HEAD}.
 */
public final class Provisioner {

  /** The correlation id all provision output ({@link CommandChunk}) is tagged with. */
  static final String PROVISION_CORRELATION_ID = DaemonProtocol.PROVISION_CORRELATION_ID;

  /** Where the branch clone lives in every workspace container (image {@code WORKDIR}). */
  private static final File WORKSPACE_DIR = new File("/workspace");

  /**
   * The cycle backstop for the bounded submodule walk (mirrors the host's {@code
   * MAX_SUBMODULE_DEPTH}).
   */
  private static final int MAX_SUBMODULE_DEPTH = 10;

  private static final int BUFFER_SIZE = 4096;

  /** The identity + coordinates the daemon self-provisions from (its injected env). */
  public record Env(
      String workspaceId,
      String repoId,
      String branch,
      String projectId,
      String repoName,
      String dialHomeUrl) {}

  private Provisioner() {}

  /**
   * Clone + submodule-materialize {@code /workspace} from {@code env}, emitting streamed output and
   * exactly one terminal {@link Provisioned}/{@link ProvisionFailed}. Never throws — any error is
   * reported as {@link ProvisionFailed}, keeping the daemon's "never exit on failure" invariant.
   */
  public static void provision(Env env, Consumer<DaemonMessage> emit) {
    try {
      String gitBase = gitBase(env.dialHomeUrl());
      if (gitBase == null) {
        emit.accept(
            new ProvisionFailed(
                env.workspaceId(),
                "cannot derive git host from dial-home url: " + env.dialHomeUrl()));
        return;
      }
      // Idempotent: an existing checkout (reconnect/restart in a still-provisioned container) is
      // never re-cloned — it may hold unpushed commits. But still re-run the submodule walk before
      // reporting done: a prior boot may have died after the root clone but before (or during)
      // materialization, and `submodule update --init` is a no-op on already-present submodules, so
      // this completes a partial checkout rather than reporting an incomplete one as Provisioned.
      if (new File(WORKSPACE_DIR, ".git").exists()) {
        emit.accept(
            new DaemonLog(
                "INFO",
                "/workspace already checked out — skipping root clone, re-checking submodules."));
        materializeSubmodules(gitBase, env, ".", 0, emit);
        emit.accept(new Provisioned(env.workspaceId(), head()));
        return;
      }
      String rootUrl = rootUrl(gitBase, env);
      emit.accept(new DaemonLog("INFO", "self-cloning " + rootUrl + " into /workspace"));
      List<String> cloneArgv = new ArrayList<>(List.of("git", "clone"));
      if (env.branch() != null && !env.branch().isBlank()) {
        cloneArgv.add("--branch");
        cloneArgv.add(env.branch());
      }
      cloneArgv.add(rootUrl);
      cloneArgv.add(WORKSPACE_DIR.getPath());
      int cloneExit = runStreaming(cloneArgv, emit);
      if (cloneExit != 0) {
        emit.accept(
            new ProvisionFailed(
                env.workspaceId(), "git clone exited " + cloneExit + " (" + rootUrl + ")"));
        return;
      }
      materializeSubmodules(gitBase, env, ".", 0, emit);
      emit.accept(new Provisioned(env.workspaceId(), head()));
    } catch (RuntimeException e) {
      emit.accept(
          new ProvisionFailed(env.workspaceId(), "self-provision error: " + e.getMessage()));
    }
  }

  /** {@code ws://host:port/api/…} → {@code http://host:port/git} (or {@code wss}→{@code https}). */
  static String gitBase(String dialHomeUrl) {
    if (dialHomeUrl == null || dialHomeUrl.isBlank()) {
      return null;
    }
    try {
      URI uri = URI.create(dialHomeUrl);
      if (uri.getHost() == null) {
        return null;
      }
      String scheme = "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
      String authority = uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
      return scheme + "://" + authority + "/git";
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The name-addressed clone url ({@code <gitBase>/<projectId>/<repoName>}) so relative submodules
   * resolve natively; falls back to the id-addressed route ({@code <gitBase>/<repoId>}) when no
   * project-scoped name was injected (mirrors the host's {@code cloneUrl}).
   */
  static String rootUrl(String gitBase, Env env) {
    if (env.projectId() != null
        && !env.projectId().isBlank()
        && env.repoName() != null
        && !env.repoName().isBlank()) {
      return gitBase + "/" + env.projectId() + "/" + env.repoName();
    }
    return gitBase + "/" + env.repoId();
  }

  /**
   * Materialize one level of the checkout's submodules (from its committed {@code .gitmodules})
   * then descend — the in-container port of the host's {@code materializeSubmodules}, sourced from
   * the checkout rather than the DB. Relative urls resolve natively; an absolute url is redirected
   * to the name-addressed sibling by basename. A submodule whose gitlink isn't on this branch is
   * skipped; a submodule whose update fails (e.g. never imported, so no served sibling) is skipped
   * with a warning rather than failing the provision.
   *
   * <p><b>Known limitation vs. the host path (accepted for the autonomous model).</b> The host's
   * {@code materializeSubmodules} walks the DB's <em>imported</em> submodule-edge closure, so a
   * submodule the user chose not to import never materializes. The daemon has no DB, so it
   * materializes every submodule the branch's {@code .gitmodules} names. Usually harmless — an
   * un-imported submodule with no served sibling just fails-to-fetch and is skipped. The sharp edge
   * is a name collision: if an un-imported submodule's basename coincides with a <em>different</em>
   * served repo in the same project, its relative/redirected url resolves to that sibling and the
   * update <em>succeeds</em>, pulling in unrelated content the user didn't import. This is the
   * direct cost of "no closure hand-off"; re-scoping would require the host to send the imported
   * closure (the rejected option). Severity is bounded by the project model — a project is one
   * maintainer's curated repo set, so this is a naming mistake in their own project, not an outside
   * threat (see docs/guides/project-model.md).
   */
  private static void materializeSubmodules(
      String gitBase, Env env, String rel, int depth, Consumer<DaemonMessage> emit) {
    if (depth >= MAX_SUBMODULE_DEPTH) {
      return;
    }
    String gitmodules = ".".equals(rel) ? ".gitmodules" : rel + "/.gitmodules";
    Captured listed =
        capture(
            List.of(
                "git", "config", "--file", gitmodules, "--get-regexp", "^submodule\\..*\\.path$"));
    if (listed.exitCode() != 0 || listed.stdout().isBlank()) {
      return;
    }
    List<Submodule> present = new ArrayList<>();
    for (Submodule sub : parseSubmodules(listed.stdout())) {
      // The gitlink may be absent on this workspace's branch (parsed from another branch's
      // .gitmodules) — skip those, exactly like the host's `ls-files --error-unmatch` guard.
      if (capture(List.of("git", "-C", rel, "ls-files", "--error-unmatch", "--", sub.path()))
              .exitCode()
          != 0) {
        continue;
      }
      Captured committedUrl =
          capture(
              List.of(
                  "git",
                  "config",
                  "--file",
                  gitmodules,
                  "--get",
                  "submodule." + sub.name() + ".url"));
      String url = committedUrl.exitCode() == 0 ? committedUrl.stdout().trim() : "";
      boolean relative = url.isEmpty() || url.startsWith("./") || url.startsWith("../");
      // A relative url resolves natively against the name-addressed origin; only an absolute url
      // needs redirecting to the name-addressed sibling (by basename) to stay offline. We can only
      // do that when a project scope is known.
      if (!relative && env.projectId() != null && !env.projectId().isBlank()) {
        runStreaming(
            List.of(
                "git",
                "-C",
                rel,
                "config",
                "submodule." + sub.name() + ".url",
                gitBase + "/" + env.projectId() + "/" + basename(url)),
            emit);
      }
      int update =
          runStreaming(
              List.of("git", "-C", rel, "submodule", "update", "--init", "--", sub.path()), emit);
      if (update != 0) {
        emit.accept(
            new DaemonLog(
                "WARN",
                "skipping submodule '"
                    + sub.name()
                    + "' at "
                    + childRel(rel, sub.path())
                    + " (update exited "
                    + update
                    + ")"));
        continue;
      }
      present.add(sub);
    }
    for (Submodule sub : present) {
      materializeSubmodules(gitBase, env, childRel(rel, sub.path()), depth + 1, emit);
    }
  }

  private record Submodule(String name, String path) {}

  /** Parse {@code git config --get-regexp} output lines ({@code submodule.<name>.path <path>}). */
  static List<Submodule> parseSubmodules(String getRegexpOutput) {
    List<Submodule> out = new ArrayList<>();
    for (String raw : getRegexpOutput.split("\n")) {
      String line = raw.trim();
      if (line.isEmpty()) {
        continue;
      }
      int space = line.indexOf(' ');
      if (space < 0) {
        continue;
      }
      String key = line.substring(0, space);
      String path = line.substring(space + 1).trim();
      if (!key.startsWith("submodule.") || !key.endsWith(".path") || path.isEmpty()) {
        continue;
      }
      String name = key.substring("submodule.".length(), key.length() - ".path".length());
      if (!name.isEmpty()) {
        out.add(new Submodule(name, path));
      }
    }
    return out;
  }

  private static String childRel(String rel, String path) {
    return ".".equals(rel) ? path : rel + "/" + path;
  }

  /**
   * The addressable basename of a submodule url ({@code https://h/o/foo.git} → {@code foo}) —
   * mirrors the host's {@code RepositoryNameRepository.basename} so an absolute url redirects to
   * the same served sibling name.
   */
  static String basename(String url) {
    String u = url == null ? "" : url.trim();
    while (u.length() > 1 && u.endsWith("/")) {
      u = u.substring(0, u.length() - 1);
    }
    int slash = u.lastIndexOf('/');
    String last = slash >= 0 ? u.substring(slash + 1) : u;
    int colon = last.lastIndexOf(':'); // scp-style user@host:path
    if (colon >= 0) {
      last = last.substring(colon + 1);
    }
    if (last.endsWith(".git")) {
      last = last.substring(0, last.length() - 4);
    }
    return last;
  }

  /** The current {@code HEAD} of the checkout, or {@code ""} if unreadable. */
  private static String head() {
    Captured rev = capture(List.of("git", "rev-parse", "HEAD"));
    return rev.exitCode() == 0 ? rev.stdout().trim() : "";
  }

  /**
   * Run a git command in {@code /workspace} (when it exists), streaming stdout+stderr as {@link
   * CommandChunk}s tagged {@link #PROVISION_CORRELATION_ID} so the host can feed the {@code clone}
   * segment, and return its exit code. Mirrors {@link CommandExecutor}'s pump, minus the terminal
   * {@code CommandExit} (a provision is not a command round-trip).
   */
  static int runStreaming(List<String> argv, Consumer<DaemonMessage> emit) {
    ProcessBuilder builder = new ProcessBuilder(argv);
    if (WORKSPACE_DIR.isDirectory()) {
      builder.directory(WORKSPACE_DIR);
    }
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      emit.accept(
          new CommandChunk(
              PROVISION_CORRELATION_ID, Stream.STDERR, String.valueOf(e.getMessage())));
      return 127;
    }
    Thread stderrPump =
        new Thread(
            () -> pump(process.getErrorStream(), Stream.STDERR, emit),
            "workspace-daemon-provision-stderr");
    stderrPump.setDaemon(true);
    stderrPump.start();
    pump(process.getInputStream(), Stream.STDOUT, emit);
    try {
      int exit = process.waitFor();
      stderrPump.join();
      return exit;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return 130;
    }
  }

  private static void pump(InputStream stream, Stream channel, Consumer<DaemonMessage> emit) {
    byte[] buffer = new byte[BUFFER_SIZE];
    try (stream) {
      int read;
      while ((read = stream.read(buffer)) != -1) {
        if (read > 0) {
          emit.accept(
              new CommandChunk(
                  PROVISION_CORRELATION_ID,
                  channel,
                  new String(buffer, 0, read, StandardCharsets.UTF_8)));
        }
      }
    } catch (IOException e) {
      // Stream closed under us (process died) — nothing more to read; the exit code carries the
      // outcome.
    }
  }

  private record Captured(int exitCode, String stdout) {}

  /** Run a short git read in {@code /workspace}, returning its exit + stdout ("" on failure). */
  private static Captured capture(List<String> argv) {
    try {
      ProcessBuilder builder =
          new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD);
      if (WORKSPACE_DIR.isDirectory()) {
        builder.directory(WORKSPACE_DIR);
      }
      Process process = builder.start();
      byte[] out = process.getInputStream().readAllBytes();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new Captured(-1, "");
      }
      return new Captured(process.exitValue(), new String(out, StandardCharsets.UTF_8));
    } catch (Exception e) {
      return new Captured(-1, "");
    }
  }
}
