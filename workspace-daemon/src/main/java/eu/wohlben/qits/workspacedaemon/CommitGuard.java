package eu.wohlben.qits.workspacedaemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.jboss.logging.Logger;

/**
 * Installs the {@code pre-commit} hook that keeps agents off the default branch (D23).
 *
 * <p>In the wrapper git already refuses: the base clone holds {@code main}, so an agent worktree
 * cannot check it out. A submodule is not protected that way: the base clone's submodules are
 * detached, so their local {@code main} is free, and an agent can commit to it. The git host
 * refuses the push, but the commit would still be work that goes nowhere. So the hook refuses a
 * commit on the repository's default branch or on a detached HEAD, and says which branch to create.
 *
 * <p>The hook lives in the repository's <b>common</b> git dir, so one file covers the base clone
 * and every agent worktree made from it. {@code --no-verify} gets past it; that is accepted,
 * because the cleanup check still finds commits nobody pushed (D5).
 *
 * <p>The branch hint is worked out when the hook runs: the agent's wrapper worktree is on {@code
 * <prefix>/<workId>}, and a submodule branch must be {@code <prefix>/<workId>-<name>} (D9).
 */
final class CommitGuard {

  private static final Logger LOG = Logger.getLogger(CommitGuard.class);

  /** The first line after the shebang: how this daemon knows a hook is its own. */
  static final String MARKER = "# qits-workspace-daemon commit guard (qits-1152)";

  private CommitGuard() {}

  /**
   * Write the hook into {@code repository}'s common git dir. A failure is logged and swallowed: a
   * missing guard costs a nudge, and must never fail a provision.
   *
   * @param repository a checkout (the base clone or one of its submodules)
   * @param defaultBranch the branch commits are refused on
   * @param agentsRoot where agent worktrees live ({@code /workspace/agents})
   * @param wrapperName the wrapper worktree's directory name under each agent
   */
  static void install(Path repository, String defaultBranch, Path agentsRoot, String wrapperName) {
    GitExec.Out common = GitExec.git(repository, "rev-parse", "--git-common-dir");
    if (!common.ok() || common.line().isEmpty()) {
      LOG.warnf("No commit guard for %s: %s", repository, common.message());
      return;
    }
    Path hooks = repository.resolve(common.line()).normalize().resolve("hooks");
    Path hook = hooks.resolve("pre-commit");
    try {
      Files.createDirectories(hooks);
      Files.writeString(
          hook, script(defaultBranch, agentsRoot, wrapperName), StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (IOException | UnsupportedOperationException e) {
      LOG.warnf("No commit guard for %s: %s", repository, e.getMessage());
    }
  }

  /** The hook's text. Package-private so a test can read it. */
  static String script(String defaultBranch, Path agentsRoot, String wrapperName) {
    return "#!/bin/sh\n"
        + MARKER
        + "\n"
        + "# Refuses commits on the default branch or on a detached HEAD. --no-verify gets past"
        + " it;\n"
        + "# the cleanup check still finds commits that were never pushed.\n"
        + "default="
        + quote(defaultBranch)
        + "\n"
        + "agents="
        + quote(agentsRoot.toString())
        + "\n"
        + "wrapper="
        + quote(wrapperName)
        + "\n"
        + "branch=$(git symbolic-ref --quiet --short HEAD 2>/dev/null)\n"
        + "if [ -n \"$branch\" ] && [ \"$branch\" != \"$default\" ]; then\n"
        + "  exit 0\n"
        + "fi\n"
        + "top=$(git rev-parse --show-toplevel 2>/dev/null)\n"
        + "hint=''\n"
        + "own=''\n"
        + "case \"$top\" in\n"
        + "  \"$agents\"/*)\n"
        + "    rest=${top#\"$agents\"/}\n"
        + "    own=\"$agents/${rest%%/*}/$wrapper\"\n"
        // A hook runs with GIT_DIR set to this repository; asking another one needs it unset.
        + "    hint=$(env -u GIT_DIR -u GIT_WORK_TREE -u GIT_INDEX_FILE -u GIT_COMMON_DIR git -C"
        + " \"$own\" symbolic-ref --quiet --short HEAD 2>/dev/null)\n"
        + "    ;;\n"
        + "esac\n"
        + "[ -n \"$hint\" ] || hint='<prefix>/<workId>'\n"
        + "if [ -n \"$branch\" ]; then where=\"on $default\"; else where='on a detached HEAD'; fi\n"
        + "if [ \"$top\" = \"$own\" ]; then\n"
        + "  echo \"qits: commits $where are refused here. Switch to your branch: git switch"
        + " $hint\" >&2\n"
        + "else\n"
        + "  echo \"qits: commits $where are refused here. Create a branch: git switch -c"
        + " $hint-<name>\" >&2\n"
        + "fi\n"
        + "exit 1\n";
  }

  /** POSIX single quotes: safe for any value, since only {@code '} is special inside them. */
  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
