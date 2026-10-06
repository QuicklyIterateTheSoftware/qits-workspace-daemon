package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Notices a coding agent that was <em>killed</em> rather than finished, and says so (qits-951).
 *
 * <p>A SIGKILLed agent runs no hook — no {@code Stop}, no {@code SessionEnd} — so {@link
 * HookWebhook} never hears that it is gone, and the host keeps whatever state it last heard. On a
 * runner that was {@code IDLE}: the cgroup's OOM killer took a working agent at the 4 GiB cap, its
 * CHAT and TERMINAL commands both read {@code EXITED 137}, the daemon (PID 1) survived, and the
 * workspace read RUNNING / IDLE / no error — a turn that died mid-work, indistinguishable from one
 * that completed. The process exit is the one signal that survives the kill, so it is the one this
 * reads.
 *
 * <p>Driven by the command list's change callback ({@code CommandLifecycleService} fires it on
 * every launch and every status transition), scanning the store rather than hooking the exit
 * listener: the agent launch path belongs to the harness library and passes its own exit listener,
 * while the change callback is the seam this daemon already owns. The scan is cheap — the store is
 * bounded at {@link CommandStore#MAX_COMMANDS} — and the change callback must not block, which two
 * small sysfs reads do not.
 *
 * <ul>
 *   <li><b>Only agent commands</b> ({@code agentType} set) and only {@link CommandStatus#EXITED}
 *       with {@value #SIGKILL_EXIT}. A {@code TERMINATED} command was killed by somebody asking
 *       for it — {@code DELETE /commands/{id}}, or the daemon stopping its agents on shutdown —
 *       and is not news; any other exit code is the harness ending on its own terms.
 *   <li><b>OOM or not is decided per command</b>: the {@code oom_kill} count is read when the
 *       command is first seen running, and an exit 137 after it rose is the OOM killer. One OOM
 *       event that takes several processes (the measured case counted {@code oom_kill 4}) therefore
 *       classifies every agent it took, however the exits interleave with the reads. No readable
 *       counter is reported as a plain SIGKILL, never guessed to be an OOM.
 *   <li><b>Each command is reported once</b>, and forgotten when the store evicts it.
 * </ul>
 */
final class AgentKillWatch {

  private static final Logger LOG = Logger.getLogger(AgentKillWatch.class);

  /** 128 + SIGKILL(9): what a shell, and the registry, report for a process killed by signal 9. */
  static final int SIGKILL_EXIT = 137;

  /** Where a kill is reported to — {@link HookWebhook#killed} in production. */
  @FunctionalInterface
  interface Sink {
    void killed(String commandId, String hookEvent, int exitCode, String message);
  }

  private final CommandStore store;
  private final CgroupMemory cgroup;
  private final Sink sink;

  /** The {@code oom_kill} count when each running agent command was first seen; -1 unreadable. */
  private final Map<String, Long> baselines = new HashMap<>();

  /** Agent commands already seen finished, so each is reported (or passed over) exactly once. */
  private final Set<String> settled = new HashSet<>();

  AgentKillWatch(CommandStore store, CgroupMemory cgroup, Sink sink) {
    this.store = store;
    this.cgroup = cgroup;
    this.sink = sink;
  }

  /**
   * The command list changed: baseline new agents, and report any agent that has died by SIGKILL
   * since the last call. Synchronized because the callback fires on request and reader threads
   * alike; never throws, because a throw here would land in a process reader thread.
   */
  synchronized void commandsChanged() {
    try {
      scan();
    } catch (RuntimeException e) {
      LOG.warnf(e, "workspace-daemon could not check its agents for a kill");
    }
  }

  private void scan() {
    List<Command> commands = store.listByLaunchedAtDesc();
    Set<String> present = new HashSet<>();
    for (Command command : commands) {
      present.add(command.id());
      if (command.agentType() == null || settled.contains(command.id())) {
        continue;
      }
      if (command.isRunning()) {
        if (!baselines.containsKey(command.id())) {
          baselines.put(command.id(), cgroup.oomKills().orElse(-1));
        }
        continue;
      }
      settled.add(command.id());
      Long baseline = baselines.remove(command.id());
      if (command.status() == CommandStatus.EXITED
          && command.exitCode() != null
          && command.exitCode() == SIGKILL_EXIT) {
        report(command, baseline);
      }
    }
    // The store evicts the oldest commands past its bound; nothing about them is left to report.
    baselines.keySet().retainAll(present);
    settled.retainAll(present);
  }

  private void report(Command command, Long baseline) {
    OptionalLong now = cgroup.oomKills();
    // No baseline is a command that started and died between two callbacks: anything counted at
    // all is then the best evidence there is. An unreadable counter at either end is no evidence.
    long before = baseline == null ? 0 : baseline;
    boolean oom = now.isPresent() && before >= 0 && now.getAsLong() > before;
    OptionalLong cap = cgroup.limitBytes();
    String limit = cap.isPresent() ? ", memory cap " + CgroupMemory.describe(cap.getAsLong()) : "";
    String what =
        "the " + command.agentType() + " agent (" + command.kind() + " command " + command.id() + ")";
    String how = oom ? "the out-of-memory killer" : "SIGKILL";
    String message =
        what + " was killed by " + how + " (exit code " + SIGKILL_EXIT + limit + ")";
    LOG.warnf("workspace-daemon: %s", message);
    sink.killed(
        command.id(), oom ? AgentEvent.OOM_KILLED : AgentEvent.KILLED, SIGKILL_EXIT, message);
  }
}
