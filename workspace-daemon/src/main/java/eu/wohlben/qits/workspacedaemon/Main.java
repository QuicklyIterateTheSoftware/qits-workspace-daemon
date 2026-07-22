package eu.wohlben.qits.workspacedaemon;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/**
 * Entry point for {@code workspace-daemon} — the in-container client daemon
 * (docs/epics/qits-workspace-daemon/). A Quarkus command-mode app with no web stack, compiled to a
 * GraalVM native binary and shipped in the workspace image at {@code
 * /usr/local/bin/qits-workspace-daemon} (its ENTRYPOINT). The {@link
 * eu.wohlben.qits.domain.repository.control.WorkspaceContainerFactory} makes it the container's
 * process (PID 1's child under tini), replacing {@code sleep infinity}.
 *
 * <p>Unlike the {@code cli} command-mode app (which runs a command and exits), {@code
 * workspace-daemon} <em>blocks forever</em> on {@link Quarkus#waitForExit()}: it is the container's
 * long-lived process, so it must never return on its own. {@link ControlSocket} owns the dial-home
 * connection and reconnects indefinitely; a down backend or missing dial-home URL leaves the
 * container alive exactly as {@code sleep infinity} would.
 */
@QuarkusMain
public class Main {

  public static void main(String... args) {
    Quarkus.run(DaemonApplication.class, args);
  }

  public static class DaemonApplication implements QuarkusApplication {

    @Inject ControlSocket controlSocket;

    @Override
    public int run(String... args) {
      controlSocket.start();
      Quarkus.waitForExit();
      return 0;
    }
  }
}
