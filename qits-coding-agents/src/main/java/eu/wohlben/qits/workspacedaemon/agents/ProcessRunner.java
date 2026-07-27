package eu.wohlben.qits.workspacedaemon.agents;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs a one-off subprocess to completion with a hard timeout, capturing stdout and stderr
 * separately.
 *
 * <p>This is what {@code ContainerRuntime.exec}/{@code execArgv} became. On the host every one of
 * these probes was a {@code docker exec} into the workspace container — the daemon <em>is</em> that
 * container, so the docker prefix is gone and the argv is just the command.
 *
 * <p>An interface rather than a class because there is no mocking framework in this reactor: the
 * three probes that use it would otherwise be untestable without a real {@code claude} on the PATH.
 * {@link LocalProcessExecutor} is the only production implementation.
 *
 * <p>Streams stay separate, unlike a git executor's: prompt refinement treats stdout as the payload,
 * so stderr noise must not leak into it.
 */
public interface ProcessRunner {

  /** The outcome of a finished (or forcibly terminated) invocation. */
  record Result(int exitCode, String stdout, String stderr, boolean timedOut) {

    /**
     * stdout and stderr concatenated — what the host's {@code ContainerRuntime.ExecResult.output()}
     * returned, kept so the probes that scan combined output for a marker read as they did.
     */
    public String output() {
      return (stdout == null ? "" : stdout) + (stderr == null ? "" : stderr);
    }
  }

  Result exec(List<String> command, Path cwd, Map<String, String> env, Duration timeout);
}
