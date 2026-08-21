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
 * <p><b>The clone is project-scoped</b>: {@code <gitBase>/<projectId>/<repoName>}, from the git base
 * plus the {@code …_PROJECT_ID} and {@code …_REPO_NAME} env. That is qits-githost's one public
 * repository address. The flat {@code <gitBase>/<repoName>} form this used to build worked only
 * while a repository's storage id was its name, which collides globally the moment a second project
 * holds a repository of the same name — the defect the project segment removes. With either half of
 * the scope absent the daemon falls back to the id-addressed route ({@code <gitBase>/<repoId>}),
 * which is internal storage addressing and exists here only for containers created before the
 * scoped form shipped.
 *
 * <p>Committed <b>relative</b> submodule urls resolve natively against the project segment, and an
 * <b>absolute</b> one is redirected to the sibling below the same segment by basename. Submodules
 * are discovered from the checkout's own {@code .gitmodules} in a bounded, depth-capped walk (the
 * daemon has no DB) — a submodule that can't be fetched (e.g. one never imported into the project)
 * is skipped with a warning rather than failing the whole provision. An existing checkout (reconnect
 * after a restart) is never re-cloned: it re-emits {@link Provisioned} from the current {@code
 * HEAD}, after {@link #alignExistingCheckoutOrigin} retargets its origin.
 *
 * <p><b>The git base must be injected.</b> The git host is <b>qits-githost</b>, which serves {@code
 * /git/<projectId>/<repoName>} (the path convention: {@code /<segment>/git/…}, verbatim through the
 * gateway <em>and</em> on {@code qits-net}, so the prefix is not a gateway rewrite the daemon may
 * drop when it dials a container directly). The control socket, by contrast, is qits-workspaces.
 * This class used to derive the base from the dial-home authority plus {@code /artifacts/git} when
 * {@code qits.workspace-daemon.git-base-url} was unset. That derivation is gone: it named a
 * pre-split host that serves no git at all, so it could only turn a missing configuration into a
 * connection error against a live-but-wrong service. Unset now emits a {@link DaemonLog} {@code
 * WARN} and refuses to self-clone.
 *
 * <p>The base's own prefix does <b>not</b> disturb relative submodule resolution. Git treats the
 * superproject's remote as a <em>directory</em> and {@code ../} drops one whole segment (its own
 * rule, not RFC 3986 — URI resolution would discard {@code <repoName>} as a filename first and land
 * a level too high). So {@code ../sibling} against {@code …/git/<projectId>/<repoName>} yields
 * {@code …/git/<projectId>/sibling}: the sibling route qits-githost serves for the same project.
 * Verified against real git, not assumed.
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

  /**
   * The identity + coordinates the daemon self-provisions from (its injected env).
   *
   * <p>{@code gitBaseUrl} is the qits-githost git base ({@code qits.workspace-daemon.git-base-url},
   * e.g. {@code http://qits-githost:8080/git}). Blank means the daemon cannot clone — there is no
   * derivation left to fall back to (see the class javadoc).
   */
  public record Env(
      String workspaceId,
      String repoId,
      String branch,
      String projectId,
      String repoName,
      String gitBaseUrl) {}

  private Provisioner() {}

  /**
   * Clone + submodule-materialize {@code /workspace} from {@code env}, emitting streamed output and
   * exactly one terminal {@link Provisioned}/{@link ProvisionFailed}. Never throws — any error is
   * reported as {@link ProvisionFailed}, keeping the daemon's "never exit on failure" invariant.
   * Returns {@code true} when it emitted {@link Provisioned} (a usable checkout exists), {@code
   * false} when it emitted {@link ProvisionFailed} — so the caller knows whether the daemon's next
   * startup steps (config read, bootstrap) have a checkout to run against.
   */
  public static boolean provision(Env env, Consumer<DaemonMessage> emit) {
    try {
      String gitBase = gitBase(env, emit);
      if (gitBase == null) {
        emit.accept(
            new ProvisionFailed(
                env.workspaceId(),
                "no git host: qits.workspace-daemon.git-base-url"
                    + " (QITS_WORKSPACE_DAEMON_GIT_BASE_URL) is unset"));
        return false;
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
        if (!alignExistingCheckoutOrigin(WORKSPACE_DIR, gitBase, env, emit)) {
          emit.accept(
              new ProvisionFailed(
                  env.workspaceId(),
                  "could not align the existing checkout with its project-scoped origin"));
          return false;
        }
        materializeSubmodules(gitBase, env, ".", 0, emit);
        emit.accept(new Provisioned(env.workspaceId(), head()));
        return true;
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
        return false;
      }
      materializeSubmodules(gitBase, env, ".", 0, emit);
      emit.accept(new Provisioned(env.workspaceId(), head()));
      return true;
    } catch (RuntimeException e) {
      emit.accept(
          new ProvisionFailed(env.workspaceId(), "self-provision error: " + e.getMessage()));
      return false;
    }
  }

  /**
   * The git base every clone url is built on: the injected {@code
   * qits.workspace-daemon.git-base-url}, or {@code null} when the host supplied none — announced as
   * a {@code WARN} and reported by the caller as {@link ProvisionFailed}.
   *
   * <p>There used to be a fallback here: the dial-home authority plus {@code /artifacts/git}, taken
   * with a {@code WARN}. It assumed one authority routed every segment, and it named qits-artifacts,
   * which stopped serving git at the byte-plane split. A guess at a host that cannot answer buys
   * nothing over saying the configuration is missing, and it costs the operator a connection error
   * pointing at the wrong service. An address with no derivable form fails loudly — the same rule
   * {@code qits.actions-mcp.url} already follows.
   */
  static String gitBase(Env env, Consumer<DaemonMessage> emit) {
    String configured = env.gitBaseUrl();
    if (configured != null && !configured.isBlank()) {
      return trimTrailingSlash(configured.trim());
    }
    emit.accept(
        new DaemonLog(
            "WARN",
            "No qits.workspace-daemon.git-base-url injected — refusing to self-clone. The git host"
                + " is qits-githost and its address is not derivable from the control socket's"
                + " (qits-workspaces'); whoever creates this container has to state it."));
    return null;
  }

  private static String trimTrailingSlash(String base) {
    String out = base;
    while (out.length() > 1 && out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }

  /**
   * The project-scoped clone url ({@code <gitBase>/<projectId>/<repoName>}) — qits-githost's public
   * repository address, and the one relative submodule urls resolve against. Falls back to the
   * id-addressed storage route ({@code <gitBase>/<repoId>}) when either half of the scope is
   * missing, which is a container created before the scoped form shipped.
   */
  static String rootUrl(String gitBase, Env env) {
    if (nameAddressed(env)) {
      return gitBase + "/" + env.projectId() + "/" + env.repoName();
    }
    return gitBase + "/" + env.repoId();
  }

  /**
   * Where a submodule's committed url is pointed instead: the sibling repository of the same name,
   * below this checkout's own scheme. A project's repositories are siblings <em>under the project
   * segment</em> ({@code <gitBase>/<projectId>/<basename>}); an id-addressed checkout keeps the flat
   * form, which is what the storage-id-equals-name world it was created in serves.
   */
  static String siblingUrl(String gitBase, Env env, String submoduleUrl) {
    String sibling = basename(submoduleUrl);
    return nameAddressed(env)
        ? gitBase + "/" + env.projectId() + "/" + sibling
        : gitBase + "/" + sibling;
  }

  /**
   * Whether the project-scoped route ({@code /git/<projectId>/<repoName>}) exists for this workspace
   * — the ONE predicate for both the clone url and the preserved-checkout retarget. <b>Both</b> env
   * vars must be present: a repository name alone addressed a repository only while storage ids were
   * names, and a project id alone names no servable route.
   */
  static boolean nameAddressed(Env env) {
    return env.projectId() != null
        && !env.projectId().isBlank()
        && env.repoName() != null
        && !env.repoName().isBlank();
  }

  /**
   * Upgrade a preserved checkout from the legacy id-addressed origin to the project-scoped one.
   *
   * <p>A container recreate deliberately preserves {@code /workspace}: it may contain commits the
   * daemon already pushed, and replacing the container must not replace the durable working volume.
   * That means provisioning's existing-checkout path can inherit an origin such as {@code
   * /git/<uuid>} — or the flat {@code /git/<repoName>} this daemon built before the project segment
   * — from a container created earlier. Relative submodule urls then resolve beside the wrong
   * segment and every update quietly skips, even though the new container has both project id and
   * repository name injected.
   *
   * <p>Only a fully scoped checkout is migrated. With either scope value absent the id-addressed
   * origin remains the only route this daemon can prove exists. {@code submodule sync} is part of
   * the migration: a failed earlier update may already have cached the wrongly resolved sibling urls
   * in {@code .git/config}, and changing {@code remote.origin.url} alone does not replace them.
   */
  static boolean alignExistingCheckoutOrigin(
      File workspace, String gitBase, Env env, Consumer<DaemonMessage> emit) {
    if (!nameAddressed(env)) {
      return true;
    }
    String scopedOrigin = rootUrl(gitBase, env);
    int remoteUpdate =
        runStreaming(
            List.of(
                "git",
                "-C",
                workspace.getPath(),
                "remote",
                "set-url",
                "origin",
                scopedOrigin),
            emit);
    if (remoteUpdate != 0) {
      return false;
    }
    return runStreaming(
            List.of(
                "git",
                "-C",
                workspace.getPath(),
                "submodule",
                "sync",
                "--recursive"),
            emit)
        == 0;
  }

  /**
   * Materialize one level of the checkout's submodules (from its committed {@code .gitmodules})
   * then descend — the in-container port of the host's {@code materializeSubmodules}, sourced from
   * the checkout rather than the DB. Every committed url, relative or absolute, is rewritten to
   * {@link #siblingUrl} by basename, so the fetch lands on the served sibling and carries no {@code
   * .git} suffix. A submodule whose gitlink isn't on this branch is skipped; a submodule whose
   * update fails (e.g. never imported, so no served sibling) is skipped with a warning rather than
   * failing the provision. Whatever prefix the git base carries is invisible here — the rewrite
   * rebuilds every url from that same base.
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
   *
   * <p><b>Following the workspace branch is inferred from its name</b> ({@link
   * #checkoutWorkspaceBranch}). The container is handed one branch name and no flag saying whether
   * the workspace forked a whole tree, so any sibling that happens to carry a branch of that name
   * is followed — including the case where the workspace branch <em>is</em> the sibling's main
   * branch, which moves it off the recorded gitlink. That is right for an aggregate workspace (the
   * host proved the branch was new in every repository before creating it) and it is a widening for
   * an ordinary one. Settling it needs the host to say which kind of workspace this is, i.e. a new
   * injected key on both sides, not a guess here.
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
      // Normalize every submodule to the sibling route this checkout's own scheme serves. Committed
      // relative URLs end in `.git`; native Git resolution preserves that suffix, while qits-githost
      // deliberately serves the repository without it. basename strips the suffix for relative and
      // absolute URLs alike and also keeps external absolute origins inside this project's imported
      // siblings.
      if (!url.isEmpty()) {
        runStreaming(
            List.of(
                "git",
                "-C",
                rel,
                "config",
                "submodule." + sub.name() + ".url",
                siblingUrl(gitBase, env, url)),
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
      checkoutWorkspaceBranch(childRel(rel, sub.path()), env.branch(), emit);
      present.add(sub);
    }
    for (Submodule sub : present) {
      materializeSubmodules(gitBase, env, childRel(rel, sub.path()), depth + 1, emit);
    }
  }

  private record Submodule(String name, String path) {}

  /**
   * Follow the workspace branch in a submodule that carries it, once git has materialized the
   * recorded gitlink.
   *
   * <p>An aggregate workspace creates the same branch in every repository of its closure, and a
   * checkout parked on a detached gitlink can commit nothing to that branch. A repository without
   * the branch keeps the detached gitlink — the behaviour every workspace had before.
   *
   * <p><b>The question is asked of the remote, not of the local clone.</b> The clone was made at
   * the gitlink and knows nothing about a branch the host created after it. {@code ls-remote
   * --exit-code} separates the three answers that matter: {@code 0} the branch is there, {@code 2}
   * it is not (keep the gitlink, silently — that is the ordinary case), anything else the question
   * could not be asked at all. The last one is announced: an unreachable or unauthenticated origin
   * otherwise reads exactly like "no such branch" and produces a pinned checkout the user cannot
   * commit from, with nothing in the provision log saying why.
   *
   * <p><b>Local work outranks the remote.</b> A container recreate deliberately preserves {@code
   * /workspace}, so a submodule may already sit on this branch holding commits nobody pushed yet.
   * The branch is therefore created from {@code origin} only when it does not exist locally, and
   * never moved onto it — {@code switch -C} would have been one command and would have discarded
   * those commits on the next boot.
   *
   * <p>{@code branch} is injected env and reaches git as a bare argument, so a leading dash is
   * refused here rather than read as an option.
   */
  static void checkoutWorkspaceBranch(String child, String branch, Consumer<DaemonMessage> emit) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return;
    }
    Captured exists =
        capture(
            List.of(
                "git",
                "-C",
                child,
                "ls-remote",
                "--exit-code",
                "--heads",
                "origin",
                "refs/heads/" + branch));
    if (exists.exitCode() == 2) {
      return;
    }
    if (exists.exitCode() != 0) {
      emit.accept(
          new DaemonLog(
              "WARN",
              "could not ask "
                  + child
                  + " whether it carries the workspace branch '"
                  + branch
                  + "' (ls-remote exited "
                  + exists.exitCode()
                  + ") — keeping the recorded gitlink"));
      return;
    }
    // Forced refspec: a branch force-pushed since the last boot must not strand every later boot
    // on a rejected non-fast-forward fetch.
    int fetched =
        runStreaming(
            List.of(
                "git",
                "-C",
                child,
                "fetch",
                "origin",
                "+refs/heads/" + branch + ":refs/remotes/origin/" + branch),
            emit);
    int selected = fetched == 0 ? switchToWorkspaceBranch(child, branch, emit) : fetched;
    if (selected != 0) {
      emit.accept(
          new DaemonLog("WARN", "could not select workspace branch '" + branch + "' in " + child));
      return;
    }
    emit.accept(
        new DaemonLog(
            "INFO", child + " follows the workspace branch '" + branch + "', not its gitlink"));
  }

  /**
   * Check out the workspace branch: the existing local branch as it stands, else a fresh tracking
   * branch at the fetched remote tip. {@code --no-guess} keeps the first form from quietly becoming
   * the second, so "keep local work" is a decision this method makes rather than one git's DWIM
   * makes for it.
   */
  private static int switchToWorkspaceBranch(
      String child, String branch, Consumer<DaemonMessage> emit) {
    Captured localBranch =
        capture(
            List.of("git", "-C", child, "show-ref", "--verify", "--quiet", "refs/heads/" + branch));
    return runStreaming(
        localBranch.exitCode() == 0
            ? List.of("git", "-C", child, "switch", "--no-guess", branch)
            : List.of(
                "git", "-C", child, "switch", "--create", branch, "--track", "origin/" + branch),
        emit);
  }

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
