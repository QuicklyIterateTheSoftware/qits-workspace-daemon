package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.commands.CommandRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ControlSocket#stopAgents} is the narrow seam {@link ControlSocket#stop} leans on: the
 * daemon's shutdown must reach every live agent with a real {@code SIGTERM} before it closes
 * anything else, because {@code claude --remote-control} archives its claude.ai session only on
 * that signal and, under tini, a plain {@code docker stop} would otherwise reach only the daemon and
 * leave the agents to be {@code SIGKILL}ed with the container. {@code ControlSocket} itself is too
 * heavy to construct in a unit test (it wires a live websocket client, the editor, the tunnel, …),
 * so this drives the extracted static method directly against a real {@link CommandRegistry} and a
 * real spawned process — the same kind of real-process coverage {@code CommandRegistryTest} and
 * {@code EditorSupervisorTest} use for their own shutdown paths.
 */
@EnabledOnOs(OS.LINUX)
class ControlSocketStopAgentsTest {

  @Test
  void stopAgentsSignalsALiveSessionAndLetsItArchiveBeforeExiting(@TempDir Path workspace)
      throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    Path marker = workspace.resolve("archived");
    Path ready = workspace.resolve("ready");

    // Stands in for `claude --remote-control`: it only writes its "archived the session" marker
    // when it actually receives SIGTERM, not when it is simply killed. `ready` is touched only once
    // the trap is actually armed, so the test cannot race a SIGTERM against the shell still starting
    // up (the default disposition for TERM is to die without running anything).
    registry.spawn(
        "agent",
        "trap 'touch " + marker + "; exit 0' TERM; sleep 600 & touch " + ready + "; wait",
        Map.of(),
        (id, code, manual) -> {},
        null);

    waitUntil(() -> Files.exists(ready), 15_000, "the agent's TERM trap to be armed");

    ControlSocket.stopAgents(registry);

    waitUntil(() -> Files.exists(marker), 15_000, "the trapped SIGTERM to have run");
    assertTrue(Files.exists(marker), "stopAgents must SIGTERM the live session, not SIGKILL it");
  }

  @Test
  void stopAgentsIsANoOpWithNoRegistry() {
    // A daemon whose commands API never wired (no qits.workspace-daemon.url, see wireAgents) has a
    // null field; stop() must not NPE on it.
    ControlSocket.stopAgents(null);
  }

  private static void waitUntil(
      java.util.function.BooleanSupplier condition, long timeoutMillis, String what)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    assertTrue(condition.getAsBoolean(), "timed out waiting for " + what);
  }
}
