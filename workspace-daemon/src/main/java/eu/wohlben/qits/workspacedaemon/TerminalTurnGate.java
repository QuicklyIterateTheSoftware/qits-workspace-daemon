package eu.wohlben.qits.workspacedaemon;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Holds the turns typed into an interactive harness until it has started (qits-1152).
 *
 * <p>A TUI takes keystrokes only once it is up; what is typed before that is lost or lands in a
 * half-drawn screen. So a turn for a {@code TERMINAL} command waits here until the harness's first
 * {@code SessionStart} hook ({@link #started}), and then all held turns are typed in order. A
 * command that is already started gets its turn at once.
 *
 * <p>The hook is the signal, but it is not trusted to come: a harness whose hooks do not reach the
 * daemon would hold its turns for ever. After {@code timeout} the held turns are typed anyway, with
 * a WARN, and the command counts as started from then on.
 *
 * <p>Typing runs on this class's own thread, never on the hook's: the hook arrives on the Vert.x
 * event loop, and a PTY write can block. One lock orders everything, so a turn that arrives while
 * a flush is pending is held and typed after the turns before it.
 */
final class TerminalTurnGate implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(TerminalTurnGate.class);

  /** The default for {@code qits.workspace-daemon.agent-ready-timeout}. */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  /** Types one turn into a command's PTY; false when the command is not running. */
  @FunctionalInterface
  interface Typist {
    boolean type(String commandId, String text);
  }

  private final Typist typist;
  private final Duration timeout;
  private final ScheduledExecutorService executor;

  /** Commands that have started (or timed out): their turns go straight through. */
  private final Set<String> started = ConcurrentHashMap.newKeySet();

  /** Turns waiting for a command to start, guarded by {@code this}. */
  private final Map<String, Held> held = new HashMap<>();

  private static final class Held {
    final List<String> turns = new ArrayList<>();
    ScheduledFuture<?> fallback;
  }

  TerminalTurnGate(Typist typist, Duration timeout) {
    this.typist = typist;
    this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "terminal-turn-gate");
              thread.setDaemon(true);
              return thread;
            });
  }

  /**
   * Types {@code text} into the command's PTY, or holds it until the command has started.
   *
   * @return true when typed or held; false when the command is started and no longer running
   */
  synchronized boolean type(String commandId, String text) {
    if (started.contains(commandId)) {
      return typist.type(commandId, text);
    }
    Held turns = held.computeIfAbsent(commandId, id -> new Held());
    turns.turns.add(text);
    if (turns.fallback == null) {
      turns.fallback =
          executor.schedule(
              () -> timedOut(commandId), Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    }
    return true;
  }

  /** The harness's {@code SessionStart} hook: type what is held, in order, off the caller's thread. */
  void started(String commandId) {
    if (commandId == null || started.contains(commandId)) {
      return;
    }
    try {
      executor.execute(() -> flush(commandId));
    } catch (java.util.concurrent.RejectedExecutionException closed) {
      // The daemon is stopping; nothing will be typed into anything.
    }
  }

  /** The command ended: forget it, and say so when turns were still held for it. */
  synchronized void forget(String commandId) {
    if (commandId == null) {
      return;
    }
    started.remove(commandId);
    Held turns = held.remove(commandId);
    if (turns == null) {
      return;
    }
    turns.fallback.cancel(false);
    LOG.warnf(
        "command %s ended before its harness started; %d held turn(s) were not typed",
        commandId, Integer.valueOf(turns.turns.size()));
  }

  /** Whether the command has started; for tests. */
  boolean isStarted(String commandId) {
    return started.contains(commandId);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private synchronized void timedOut(String commandId) {
    Held turns = held.get(commandId);
    if (turns == null) {
      return;
    }
    LOG.warnf(
        "command %s sent no SessionStart within %s; typing its %d held turn(s) anyway",
        commandId, timeout, Integer.valueOf(turns.turns.size()));
    flush(commandId);
  }

  private synchronized void flush(String commandId) {
    started.add(commandId);
    Held turns = held.remove(commandId);
    if (turns == null) {
      return;
    }
    turns.fallback.cancel(false);
    for (String text : turns.turns) {
      if (!typist.type(commandId, text)) {
        LOG.warnf("command %s stopped before a held turn could be typed", commandId);
        return;
      }
    }
  }
}
