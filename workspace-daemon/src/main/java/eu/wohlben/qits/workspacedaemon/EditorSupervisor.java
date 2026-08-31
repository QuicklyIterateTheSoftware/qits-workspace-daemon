package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * The in-container supervisor for the workspace's <b>web editor</b> — openvscode-server, when the
 * image carries one — reported home as {@link EditorState} so the host can gate its editor proxy and
 * its splash on something other than a guess.
 *
 * <p><b>Loopback, for the reason {@link WorkspaceApi} is loopback.</b> The editor is spawned with
 * {@code --host 127.0.0.1} and reached only through {@link DaemonStreamTunnel}. Bound to {@code
 * 0.0.0.0} it would be an unauthenticated shell on someone else's checkout, reachable by DNS name
 * from every other container on {@code qits-net} — each of which runs a coding agent over an
 * untrusted working tree. {@code --without-connection-token} is only safe <em>because</em> of that
 * bind: the token would be defending a port that no longer exists, and it would have to be handed to
 * the host to be of any use, which is the shared-secret arrangement the tunnel replaced.
 *
 * <p><b>Absent editor, absent everything.</b> With the switch off, or with no launcher at {@code
 * <installDir>/bin/openvscode-server}, this supervises nothing, announces nothing, and never sends a
 * frame. A plain workspace is
 * bit-for-bit the workspace it was — which is why the capability announcement <em>is</em> the first
 * {@link EditorState} rather than a flag in the {@code Hello} that a stale image could contradict.
 *
 * <p>Framework-free like {@link ServiceSupervisor} and {@link HookWebhook}: {@link ControlSocket}
 * reads the two config keys and hands them in, because a capability class here cannot read
 * configuration. It borrows that supervisor's shape deliberately — {@code setsid} so the whole
 * session can be reaped by session id rather than by pid, a ready grace, exponential backoff between
 * relaunches, and a waiter thread per spawn — and diverges only where the editor is not a
 * checkout-declared dev server: there is no restart <em>policy</em> to honour (it is always ON, the
 * daemon owns this process outright), and its output is not streamed home as {@link
 * eu.wohlben.qits.workspacedaemon.protocol.CommandChunk}s, because no {@code service:<name>} process
 * segment exists for it and the browser has the editor itself to look at.
 *
 * <p><b>Nothing here may take the container down.</b> A spawn that fails, a crash loop that
 * exhausts its budget, an install directory that turns out to be empty — every one of them settles
 * to {@link EditorState.State#ENDED} and logs. The workspace keeps working without an editor.
 */
final class EditorSupervisor {

  private static final Logger LOG = Logger.getLogger(EditorSupervisor.class);

  private static final int BUFFER_SIZE = 4096;

  /** Where the editor-carrying image installs openvscode-server. */
  static final File DEFAULT_INSTALL_DIR = new File("/opt/openvscode-server");

  /** The launcher, relative to the install directory. */
  private static final String LAUNCHER = "bin/openvscode-server";

  /**
   * The line openvscode-server prints once it is serving. A hint, not the contract: a version that
   * words it differently only means readiness is declared by the grace below instead, which is why
   * both exist and why whichever lands first wins. Announcing {@code RUNNING} late costs a splash
   * that lingers; announcing it never would strand the host.
   */
  private static final String READY_MARKER = "web ui available at";

  /** Long enough for a cold node start, short enough that the splash is not the experience. */
  static final long DEFAULT_READY_GRACE_MS = 15_000;

  static final long DEFAULT_BACKOFF_INITIAL_MS = 1_000;
  static final long DEFAULT_BACKOFF_MAX_MS = 30_000;
  static final long DEFAULT_STOP_GRACE_MS = 5_000;

  /**
   * How many times a crashing editor is relaunched before it is reported {@link
   * EditorState.State#ENDED}.
   *
   * <p>Bounded, unlike the reconnect backoff, because {@code ENDED} is what lets the splash stop
   * waiting. An editor that cannot start is a fact the user should be told once, not a spinner for
   * the container's lifetime. The count resets when a launch reaches {@code RUNNING}, so an editor
   * that dies after a day of work gets the full budget again rather than inheriting an exhausted
   * one.
   */
  private static final int MAX_RESTARTS = 5;

  private final File installDir;
  private final File workingDir;
  private final int port;
  private final boolean enabled;
  private final Consumer<DaemonMessage> emit;
  private final long readyGraceMs;
  private final long backoffInitialMs;
  private final long backoffMaxMs;
  private final long stopGraceMs;

  private final Object lock = new Object();
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(
          1,
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-editor-timer");
            thread.setDaemon(true);
            return thread;
          });

  private volatile Process process;
  private volatile long sid;
  private volatile boolean stopRequested;

  /** Whether {@link #start()} found an editor to supervise — the gate on every frame this sends. */
  private volatile boolean supervising;

  /** The last reported state, null until the first transition. */
  private volatile String state;

  private int restarts;
  private ScheduledFuture<?> pending;

  EditorSupervisor(
      File installDir, File workingDir, int port, boolean enabled, Consumer<DaemonMessage> emit) {
    this(
        installDir,
        workingDir,
        port,
        enabled,
        emit,
        DEFAULT_READY_GRACE_MS,
        DEFAULT_BACKOFF_INITIAL_MS,
        DEFAULT_BACKOFF_MAX_MS,
        DEFAULT_STOP_GRACE_MS);
  }

  EditorSupervisor(
      File installDir,
      File workingDir,
      int port,
      boolean enabled,
      Consumer<DaemonMessage> emit,
      long readyGraceMs,
      long backoffInitialMs,
      long backoffMaxMs,
      long stopGraceMs) {
    this.installDir = installDir;
    this.workingDir = workingDir;
    this.port = port;
    this.enabled = enabled;
    this.emit = emit;
    this.readyGraceMs = readyGraceMs;
    this.backoffInitialMs = backoffInitialMs;
    this.backoffMaxMs = backoffMaxMs;
    this.stopGraceMs = stopGraceMs;
  }

  /**
   * Spawn the editor, if there is one to spawn.
   *
   * @return whether this daemon is supervising an editor at all — the one thing the rest of the
   *     daemon needs to know, and what {@link ControlSocket} keys the tunnel's {@code EDITOR} target
   *     on. False announces nothing: no {@link EditorState}, no log home, no behaviour change of any
   *     kind for a workspace whose image has no editor in it.
   */
  boolean start() {
    if (!enabled) {
      LOG.debug("web editor disabled (qits.workspace-daemon.editor-enabled) — supervising nothing.");
      return false;
    }
    if (installDir == null || !installDir.isDirectory()) {
      // The ordinary case for a plain workspace image, so DEBUG rather than WARN: the switch being
      // on in a base image that has no editor layer is a deployment shape, not an error.
      LOG.debugf(
          "web editor enabled but %s is not present — supervising nothing.",
          installDir == null ? "(no install dir)" : installDir);
      return false;
    }
    if (!launcher().isFile()) {
      // The install directory without the launcher in it: not the plain-workspace shape but a
      // broken editor layer, so it says so. Still announces nothing — a STARTING the host would
      // wait on, followed by an ENDED from the failed spawn, is a worse account of "this image is
      // built wrong" than a line in the container log.
      LOG.warnf("web editor enabled but %s is missing — supervising nothing.", launcher());
      return false;
    }
    supervising = true;
    launch();
    return true;
  }

  /** The loopback port the editor serves on; only meaningful while {@link #start()} returned true. */
  int port() {
    return port;
  }

  /**
   * Re-report the current state — the reconnect-adoption signal, called from {@code
   * ControlSocket.onConnected} exactly as {@link ServiceSupervisor#reportAll()} and {@code
   * HookWebhook.reportCurrent()} are. A qits restart lost its projection of this container; this is
   * where it gets it back, and on a first connect it is also the announcement that an editor exists.
   */
  void reportCurrent() {
    String current = state;
    if (current != null) {
      emit.accept(new EditorState(current));
    }
  }

  /**
   * Terminate the editor on daemon shutdown. The container is going away with PID 1 either way, but
   * a supervised process is signalled rather than abandoned: {@code TERM} to the whole session,
   * {@code KILL} after the stop grace, and a terminal {@link EditorState.State#ENDED} so a host that
   * is still on the socket learns the editor went with the daemon rather than inferring it from
   * silence.
   */
  void close() {
    Process running;
    long session;
    synchronized (lock) {
      stopRequested = true;
      cancelPending();
      running = process;
      session = sid;
    }
    terminate(running, session);
    transition(EditorState.State.ENDED);
    scheduler.shutdownNow();
  }

  // ---- internals -----------------------------------------------------------

  /** {@code <installDir>/bin/openvscode-server}. */
  private File launcher() {
    return new File(installDir, LAUNCHER);
  }

  private void launch() {
    // A relaunch scheduled by handleExit outlives the cancel: close() cancels with cancel(false),
    // so a task the scheduler has already picked up runs to completion regardless. Checked here and
    // again after the spawn, because either side of builder.start() is a shutdown this misses —
    // the second one is the costly half, where a live openvscode-server would be left holding the
    // loopback port with nothing to signal it and a STARTING would be reported after the terminal
    // ENDED.
    synchronized (lock) {
      if (stopRequested) {
        return;
      }
    }
    // No shell: the argv is fixed and the only interpolated values are a configured int and the
    // checkout directory this process was handed at construction, so there is nothing here to
    // quote and nothing from the untrusted checkout to quote it against. setsid is for the same
    // reason ServiceSupervisor uses it — the editor forks helpers (extension host, terminals) that
    // reparent to PID 1, and only a kill by SESSION reaches them.
    java.util.List<String> argv =
        new java.util.ArrayList<>(
            java.util.List.of(
                "setsid",
                launcher().getAbsolutePath(),
                "--host",
                "127.0.0.1",
                "--port",
                Integer.toString(port),
                "--without-connection-token"));
    ProcessBuilder builder = new ProcessBuilder();
    if (workingDir != null && workingDir.isDirectory()) {
      builder.directory(workingDir);
      // The workbench opens ON the checkout rather than on a welcome screen asking for it —
      // `--default-folder` is the launch's statement of what this editor is for. Launch-side
      // rather than a `?folder=` query on some hand-off URL, because the editor has three ways in
      // (the SPA's hand-off, a typed address, the proxy's splash) and only the launch covers all
      // of them. Guarded by the same isDirectory check as the cwd: a folder that is not there
      // would put the workbench in a "path does not exist" dialog, which is worse than the
      // welcome screen.
      argv.add("--default-folder");
      argv.add(workingDir.getAbsolutePath());
    }
    builder.command(argv);
    // One merged stream: nothing distinguishes the editor's stderr from its stdout here, because
    // neither is forwarded anywhere — they are read to keep the pipe from filling and scanned for
    // the ready marker.
    builder.redirectErrorStream(true);
    Process started;
    try {
      started = builder.start();
    } catch (IOException e) {
      // An install directory with no launcher in it, or one that is not executable. Terminal: the
      // next attempt would fail identically, and a splash that spins forever is worse than a "no
      // editor here".
      emit.accept(
          new DaemonLog("WARN", "web editor failed to start: " + e.getMessage()));
      transition(EditorState.State.ENDED);
      return;
    }
    Process stranded = null;
    synchronized (lock) {
      if (stopRequested) {
        // close() ran while this spawn was in flight: it read a null process, signalled nothing and
        // has already reported ENDED. Nothing may be published after that, so the session is reaped
        // here instead of being adopted — the supervisor is gone, and an editor nobody supervises
        // outlives the daemon it belongs to.
        stranded = started;
      } else {
        process = started;
        sid = started.pid();
        cancelPending();
        pending = scheduler.schedule(this::markRunning, readyGraceMs, TimeUnit.MILLISECONDS);
      }
    }
    if (stranded != null) {
      // Drained first so the teardown is not waiting on a process blocked writing into a full pipe;
      // the pump closes the stream at EOF. setsid means the pid is the session id, exactly as it is
      // for a spawn that was adopted.
      pump(stranded.getInputStream());
      terminate(stranded, stranded.pid());
      return;
    }
    transition(EditorState.State.STARTING);
    pump(started.getInputStream());
    waiter(started);
  }

  /** {@code TERM} to the whole session, {@code KILL} after the stop grace. */
  private void terminate(Process running, long session) {
    if (running == null || !running.isAlive()) {
      return;
    }
    pkill("TERM", session);
    try {
      if (!running.waitFor(stopGraceMs, TimeUnit.MILLISECONDS)) {
        pkill("KILL", session);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Read the editor's merged output, scanning completed lines for the ready marker. */
  private void pump(InputStream stream) {
    Thread thread =
        new Thread(
            () -> {
              byte[] buffer = new byte[BUFFER_SIZE];
              StringBuilder lines = new StringBuilder();
              boolean matched = false;
              try (stream) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                  if (read <= 0) {
                    continue;
                  }
                  if (matched) {
                    continue; // still draining the pipe, no longer looking for anything
                  }
                  lines.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                  matched = scanForReady(lines);
                }
              } catch (IOException e) {
                // The process exited under us; the waiter carries the outcome.
              }
            },
            "workspace-daemon-editor-out");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Fire {@link #markRunning()} on the first complete line carrying the ready marker. The partial
   * tail is kept bounded, so a burst of binary output cannot grow this without limit.
   */
  private boolean scanForReady(StringBuilder lines) {
    int newline;
    while ((newline = lines.indexOf("\n")) != -1) {
      String line = lines.substring(0, newline);
      lines.delete(0, newline + 1);
      if (line.toLowerCase(Locale.ROOT).contains(READY_MARKER)) {
        markRunning();
        return true;
      }
    }
    if (lines.length() > 64 * 1024) {
      lines.setLength(0);
    }
    return false;
  }

  private void waiter(Process started) {
    Thread thread =
        new Thread(
            () -> {
              try {
                handleExit(started.waitFor());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "workspace-daemon-editor-wait");
    thread.setDaemon(true);
    thread.start();
  }

  /** Readiness: the marker, or the grace — whichever arrives first, and only on the way up. */
  private void markRunning() {
    synchronized (lock) {
      if (stopRequested || !EditorState.State.STARTING.equals(state)) {
        return;
      }
      cancelPending();
      // A launch that got up earns a fresh restart budget: a crash after hours of work is not the
      // continuation of whatever crash loop happened at boot.
      restarts = 0;
      transition(EditorState.State.RUNNING);
    }
  }

  private void handleExit(int exitCode) {
    long backoff;
    synchronized (lock) {
      cancelPending();
      process = null;
      if (stopRequested) {
        transition(EditorState.State.ENDED);
        return;
      }
      if (restarts >= MAX_RESTARTS) {
        emit.accept(
            new DaemonLog(
                "WARN",
                "web editor exited "
                    + exitCode
                    + " and has exhausted its restart budget ("
                    + MAX_RESTARTS
                    + ") — no editor in this container."));
        transition(EditorState.State.ENDED);
        return;
      }
      restarts++;
      backoff = Math.min(backoffInitialMs * (1L << Math.min(restarts - 1, 20)), backoffMaxMs);
      // Back to STARTING before the relaunch, not at it: the editor is not serving during the
      // backoff, and a host still showing RUNNING would proxy into a closed port for that whole
      // window. transition() dedupes, so a crash that never reached RUNNING emits nothing here.
      transition(EditorState.State.STARTING);
      pending = scheduler.schedule(this::launch, backoff, TimeUnit.MILLISECONDS);
    }
    LOG.debugf("web editor exited %d — relaunching in %d ms", exitCode, backoff);
  }

  /**
   * Move to {@code next} and report it, <b>only if it is a change</b>.
   *
   * <p>The dedupe is what keeps the wire honest in the two places a state is reached twice: a
   * relaunch sets {@code STARTING} at the exit and again at the spawn, and a shutdown while the
   * waiter is already settling {@code ENDED} arrives from both threads. Neither should read to the
   * host as a transition that happened.
   */
  private void transition(String next) {
    synchronized (lock) {
      // Not supervising means start() never claimed an editor: nothing is announced at all, which
      // includes a close() on a supervisor that never launched anything.
      if (!supervising || next.equals(state)) {
        return;
      }
      state = next;
      emit.accept(new EditorState(next));
    }
  }

  private void cancelPending() {
    if (pending != null) {
      pending.cancel(false);
      pending = null;
    }
  }

  /** Signal the editor's whole session, the way {@link ServiceSupervisor} signals a service's. */
  private void pkill(String signal, long session) {
    if (session <= 0) {
      return;
    }
    try {
      new ProcessBuilder("pkill", "-" + signal, "-s", Long.toString(session))
          .redirectErrorStream(true)
          .start();
    } catch (IOException e) {
      LOG.debugf("web editor pkill -s %d failed: %s", session, e.getMessage());
    }
  }
}
