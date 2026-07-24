package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.DaemonQitsConfig.ServiceDecl;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonEvent;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jboss.logging.Logger;

/**
 * The in-container supervisor for the workspace's <b>services</b> (dev servers) — the tail of the
 * daemon's startup sequence (clone → config → bootstrap → <b>services</b>) and the PID-1 owner of
 * their process lifecycle (docs/epics/qits-workspace-daemon/ Part 4). Because the daemon is the
 * container's init, it supervises services natively: no tmux session, no {@code /proc} straggler
 * scan, no host {@code docker exec} liveness poll.
 *
 * <p><b>The container owns the lifecycle; the host projects it.</b> This supervisor makes every
 * spawn/restart/backoff/policy/group-kill decision locally and reports only the <em>outcome</em> as
 * a {@link DaemonEvent} — so a service keeps crash-restarting while qits is down or the socket is
 * bouncing, and a qits restart re-adopts running services from the reconnect {@linkplain
 * #reportAll() re-report} rather than a host probe. The host state machine, backoff scheduling, and
 * liveness poll it used to run are retired in daemon-backed mode.
 *
 * <p>Framework-free and native-image-lean (no CDI/Vert.x/JGit): each service is {@code setsid bash
 * -lc <start>} in {@code /workspace}, so it leads its own session ({@code process.pid()} == session
 * id) and the whole tree — including an escaped forked JVM (Quarkus dev) that reparents to PID 1 —
 * is group-killed by <em>session</em> ({@code pkill -<SIG> -s <sid>}), which survives reparenting
 * in a way the old process-group kill did not. Never throws out to the caller: a spawn failure or a
 * runtime error settles that service to {@code CRASHED}, preserving the daemon's "never exit on
 * failure" invariant.
 */
public final class ServiceSupervisor {

  private static final Logger LOG = Logger.getLogger(ServiceSupervisor.class);
  private static final int BUFFER_SIZE = 4096;
  private static final int DEFAULT_MAX_RESTARTS = 3;
  private static final String DEFAULT_RESTART_POLICY = "ON_FAILURE";
  private static final String DEFAULT_STOP_SIGNAL = "TERM";

  private final String workspaceId;
  private final File workingDir;
  private final Consumer<DaemonMessage> emit;
  private final Supplier<List<ServiceDecl>> configServices;
  private final long readyGraceMs;
  private final long backoffInitialMs;
  private final long backoffMaxMs;
  private final long stopGraceMs;

  private final ConcurrentHashMap<String, Supervised> running = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(
          1,
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-service-timer");
            thread.setDaemon(true);
            return thread;
          });

  public ServiceSupervisor(
      String workspaceId,
      File workingDir,
      Consumer<DaemonMessage> emit,
      Supplier<List<ServiceDecl>> configServices,
      long readyGraceMs,
      long backoffInitialMs,
      long backoffMaxMs,
      long stopGraceMs) {
    this.workspaceId = workspaceId;
    this.workingDir = workingDir;
    this.emit = emit;
    this.configServices = configServices;
    this.readyGraceMs = readyGraceMs;
    this.backoffInitialMs = backoffInitialMs;
    this.backoffMaxMs = backoffMaxMs;
    this.stopGraceMs = stopGraceMs;
  }

  /** Per-service supervision state, all mutated under {@link #lock}. */
  private static final class Supervised {
    private final ServiceDecl decl;
    private final Object lock = new Object();
    private volatile Process process;
    private volatile long sid;
    private volatile String state = DaemonEvent.State.STARTING;
    private int restartCount;
    private volatile boolean stopRequested;
    private ScheduledFuture<?> pending;

    Supervised(ServiceDecl decl) {
      this.decl = decl;
    }
  }

  /**
   * Start every auto-start service from the in-container config (the tail of the boot sequence, run
   * only after {@code Bootstrapped{ok:true}}). Idempotent per name — an already-running service is
   * left alone.
   */
  public void startAutoStart() {
    for (ServiceDecl decl : configServices.get()) {
      if (Boolean.TRUE.equals(decl.autoStart())) {
        launchNew(decl);
      }
    }
  }

  /**
   * Manual/subsequent start ({@code StartDaemon}). Resolves the definition from the in-container
   * config by name, overlaying a non-blank {@code scriptOverride}/{@code envOverride} (a "try this
   * edit" start of a service not yet in the committed config). An already-running service just
   * re-reports its current state.
   */
  public void start(String name, String scriptOverride, Map<String, String> envOverride) {
    Supervised existing = running.get(name);
    if (existing != null) {
      emit.accept(new DaemonEvent(workspaceId, name, existing.state, null));
      return;
    }
    ServiceDecl decl = resolve(name, scriptOverride, envOverride);
    if (decl == null) {
      emit.accept(
          new DaemonLog(
              "WARN",
              "start requested for unknown service '" + name + "' with no script — ignored."));
      return;
    }
    launchNew(decl);
  }

  /**
   * Deliver a signal to a running service ({@code SignalDaemon}) — the stop request. Marks it
   * stop-requested (so the restart policy does not resurrect it), signals the whole session group,
   * and schedules a force-kill after the stop grace if it hasn't exited.
   */
  public void signal(String name, String signalName) {
    Supervised s = running.get(name);
    if (s == null) {
      return;
    }
    String signal = normalizeSignal(signalName);
    synchronized (s.lock) {
      s.stopRequested = true;
      cancelPending(s);
    }
    long sid = s.sid;
    pkill(signal, sid);
    scheduler.schedule(
        () -> {
          Process p = s.process;
          if (p != null && p.isAlive()) {
            pkill("KILL", sid);
          }
        },
        stopGraceMs,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Re-report the current state of every running service — the reconnect-adoption signal (called
   * from {@code ControlSocket.onConnected}). On a qits restart the host lost its in-memory
   * projection; this rebuilds it from the source of truth (the live children) instead of a host
   * {@code /proc}/tmux probe. A no-op on first connect (nothing running yet).
   */
  public void reportAll() {
    for (Supervised s : running.values()) {
      emit.accept(new DaemonEvent(workspaceId, s.decl.name(), s.state, null));
    }
  }

  /**
   * Stop the scheduler on daemon shutdown; the children die with PID 1 as the container is torn
   * down.
   */
  public void close() {
    scheduler.shutdownNow();
  }

  // ---- internals -----------------------------------------------------------

  private ServiceDecl resolve(String name, String scriptOverride, Map<String, String> envOverride) {
    ServiceDecl fromConfig = null;
    for (ServiceDecl decl : configServices.get()) {
      if (name.equals(decl.name())) {
        fromConfig = decl;
        break;
      }
    }
    boolean hasOverride = scriptOverride != null && !scriptOverride.isBlank();
    if (fromConfig == null) {
      if (!hasOverride) {
        return null;
      }
      // A start of a service absent from the committed config: synthesize a minimal, never-restart
      // definition from the override so an ad-hoc "run this" still works.
      return new ServiceDecl(
          name,
          name,
          null,
          scriptOverride,
          null,
          null,
          null,
          "NEVER",
          0,
          DEFAULT_STOP_SIGNAL,
          envOverride,
          null,
          List.of());
    }
    if (!hasOverride && (envOverride == null || envOverride.isEmpty())) {
      return fromConfig;
    }
    return new ServiceDecl(
        fromConfig.id(),
        fromConfig.name(),
        fromConfig.description(),
        hasOverride ? scriptOverride : fromConfig.start(),
        fromConfig.readyPattern(),
        fromConfig.otel(),
        fromConfig.autoStart(),
        fromConfig.restartPolicy(),
        fromConfig.maxRestarts(),
        fromConfig.stopSignal(),
        (envOverride != null && !envOverride.isEmpty()) ? envOverride : fromConfig.environment(),
        fromConfig.webView(),
        fromConfig.healthChecks());
  }

  private void launchNew(ServiceDecl decl) {
    Supervised s = new Supervised(decl);
    if (running.putIfAbsent(decl.name(), s) != null) {
      return; // already supervised
    }
    launch(s);
  }

  private void launch(Supervised s) {
    ServiceDecl decl = s.decl;
    String name = decl.name();
    if (decl.start() == null || decl.start().isBlank()) {
      emit.accept(new DaemonLog("WARN", "service '" + name + "' has no start command — ignored."));
      settle(s, DaemonEvent.State.CRASHED, null);
      return;
    }
    // setsid → the service leads its own session; process.pid() is the session id, so pkill -s
    // reaches the whole tree (including a reparented forked JVM). See class doc.
    ProcessBuilder builder = new ProcessBuilder("setsid", "bash", "-lc", decl.start());
    if (workingDir != null && workingDir.isDirectory()) {
      builder.directory(workingDir);
    }
    overlayEnv(builder, decl.environment());
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      emit.accept(
          new DaemonLog("ERROR", "service '" + name + "' failed to start: " + e.getMessage()));
      settle(s, DaemonEvent.State.CRASHED, null);
      return;
    }
    synchronized (s.lock) {
      s.process = process;
      s.sid = process.pid();
      s.state = DaemonEvent.State.STARTING;
    }
    emit.accept(new DaemonEvent(workspaceId, name, DaemonEvent.State.STARTING, null));

    String corr = DaemonProtocol.serviceCorrelationId(name);
    Pattern ready = compileReady(decl.readyPattern(), name);
    Runnable onReady = ready == null ? null : () -> markReady(s);
    pumpThread(process.getInputStream(), Stream.STDOUT, corr, ready, onReady).start();
    pumpThread(process.getErrorStream(), Stream.STDERR, corr, ready, onReady).start();

    if (ready == null) {
      // No readyPattern: declare READY after a grace, as the host did (a dev server with no
      // detectable banner is assumed up once it hasn't crashed for readyGraceMs).
      synchronized (s.lock) {
        if (!s.stopRequested) {
          s.pending = scheduler.schedule(() -> markReady(s), readyGraceMs, TimeUnit.MILLISECONDS);
        }
      }
    }

    Thread waiter =
        new Thread(
            () -> {
              try {
                int code = process.waitFor();
                handleExit(s, code);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "workspace-daemon-service-wait-" + name);
    waiter.setDaemon(true);
    waiter.start();
  }

  private void markReady(Supervised s) {
    synchronized (s.lock) {
      if (!DaemonEvent.State.STARTING.equals(s.state)) {
        return; // readiness only matters on the way up
      }
      cancelPending(s);
      s.state = DaemonEvent.State.READY;
    }
    emit.accept(new DaemonEvent(workspaceId, s.decl.name(), DaemonEvent.State.READY, null));
  }

  private void handleExit(Supervised s, int exitCode) {
    String name = s.decl.name();
    synchronized (s.lock) {
      cancelPending(s);
      if (s.stopRequested) {
        s.state = DaemonEvent.State.STOPPED;
        running.remove(name, s);
        emit.accept(new DaemonEvent(workspaceId, name, DaemonEvent.State.STOPPED, exitCode));
        return;
      }
      String policy = normalizePolicy(s.decl.restartPolicy());
      boolean wantRestart =
          "ALWAYS".equals(policy) || ("ON_FAILURE".equals(policy) && exitCode != 0);
      int maxRestarts = s.decl.maxRestarts() != null ? s.decl.maxRestarts() : DEFAULT_MAX_RESTARTS;
      if (wantRestart && s.restartCount < maxRestarts) {
        long backoff =
            Math.min(backoffInitialMs * (1L << Math.min(s.restartCount, 20)), backoffMaxMs);
        s.state = DaemonEvent.State.RESTARTING;
        emit.accept(new DaemonEvent(workspaceId, name, DaemonEvent.State.RESTARTING, exitCode));
        s.pending =
            scheduler.schedule(
                () -> {
                  synchronized (s.lock) {
                    if (s.stopRequested) {
                      return;
                    }
                    s.restartCount++;
                  }
                  launch(s);
                },
                backoff,
                TimeUnit.MILLISECONDS);
        return;
      }
      // No restart (policy NEVER, clean exit under ON_FAILURE, or restarts exhausted): a clean exit
      // is STOPPED, anything else is CRASHED.
      boolean crashed = wantRestart || exitCode != 0;
      s.state = crashed ? DaemonEvent.State.CRASHED : DaemonEvent.State.STOPPED;
      running.remove(name, s);
      emit.accept(new DaemonEvent(workspaceId, name, s.state, exitCode));
    }
  }

  /** Settle a service to a terminal state without a live process (spawn failure). */
  private void settle(Supervised s, String state, Integer exitCode) {
    synchronized (s.lock) {
      cancelPending(s);
      s.state = state;
      running.remove(s.decl.name(), s);
    }
    emit.accept(new DaemonEvent(workspaceId, s.decl.name(), state, exitCode));
  }

  private void cancelPending(Supervised s) {
    if (s.pending != null) {
      s.pending.cancel(false);
      s.pending = null;
    }
  }

  private void pkill(String signal, long sid) {
    if (sid <= 0) {
      return;
    }
    try {
      new ProcessBuilder("pkill", "-" + signal, "-s", Long.toString(sid))
          .redirectErrorStream(true)
          .start();
    } catch (IOException e) {
      emit.accept(new DaemonLog("WARN", "pkill -s " + sid + " failed: " + e.getMessage()));
    }
  }

  private static void overlayEnv(ProcessBuilder builder, Map<String, String> environment) {
    if (environment == null) {
      return;
    }
    Map<String, String> env = builder.environment();
    environment.forEach(
        (key, value) -> {
          if (key != null && value != null) {
            env.put(key, value);
          }
        });
  }

  private static Pattern compileReady(String readyPattern, String serviceName) {
    if (readyPattern == null || readyPattern.isBlank()) {
      return null;
    }
    try {
      return Pattern.compile(readyPattern);
    } catch (PatternSyntaxException e) {
      LOG.warnf(
          "service '%s' has an invalid readyPattern, using grace instead: %s",
          serviceName, e.getMessage());
      return null;
    }
  }

  private static String normalizePolicy(String policy) {
    if (policy == null || policy.isBlank()) {
      return DEFAULT_RESTART_POLICY;
    }
    return policy.trim().toUpperCase(Locale.ROOT).replace('-', '_');
  }

  private static String normalizeSignal(String signal) {
    if (signal == null || signal.isBlank()) {
      return DEFAULT_STOP_SIGNAL;
    }
    String s = signal.trim().toUpperCase(Locale.ROOT);
    return s.startsWith("SIG") ? s.substring(3) : s;
  }

  private Thread pumpThread(
      InputStream stream, Stream channel, String correlationId, Pattern ready, Runnable onReady) {
    Thread thread =
        new Thread(
            () -> pump(stream, channel, correlationId, ready, onReady),
            "workspace-daemon-service-" + channel.name().toLowerCase(Locale.ROOT));
    thread.setDaemon(true);
    return thread;
  }

  /**
   * Stream one channel as {@link CommandChunk}s tagged {@code correlationId}, and — when a {@code
   * ready} pattern is set — test each completed line against it, firing {@code onReady} on the
   * first match. Line-buffered so a readyPattern that matches a whole line is not defeated by a
   * chunk boundary splitting it.
   */
  private void pump(
      InputStream stream, Stream channel, String correlationId, Pattern ready, Runnable onReady) {
    byte[] buffer = new byte[BUFFER_SIZE];
    StringBuilder lineBuffer = ready == null ? null : new StringBuilder();
    boolean matched = false;
    try (stream) {
      int read;
      while ((read = stream.read(buffer)) != -1) {
        if (read <= 0) {
          continue;
        }
        String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
        emit.accept(new CommandChunk(correlationId, channel, text));
        if (ready != null && !matched) {
          lineBuffer.append(text);
          matched = scanForReady(lineBuffer, ready, onReady);
        }
      }
    } catch (IOException e) {
      // Stream closed under us (process exited) — the waiter carries the outcome.
    }
  }

  /**
   * Test each complete line in {@code lineBuffer} against {@code ready}; fire {@code onReady} once.
   */
  private static boolean scanForReady(StringBuilder lineBuffer, Pattern ready, Runnable onReady) {
    int newline;
    while ((newline = lineBuffer.indexOf("\n")) != -1) {
      String line = lineBuffer.substring(0, newline);
      lineBuffer.delete(0, newline + 1);
      if (ready.matcher(line).find()) {
        onReady.run();
        return true;
      }
    }
    // Keep the (unbounded-in-theory) partial line bounded: a very long line with no newline still
    // gets a chance to match, but don't let the buffer grow without limit on binary output.
    if (lineBuffer.length() > 64 * 1024) {
      if (ready.matcher(lineBuffer).find()) {
        onReady.run();
        return true;
      }
      lineBuffer.setLength(0);
    }
    return false;
  }
}
