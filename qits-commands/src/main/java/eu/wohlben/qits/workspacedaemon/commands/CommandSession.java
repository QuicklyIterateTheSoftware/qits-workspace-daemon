package eu.wohlben.qits.workspacedaemon.commands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One live PTY process, decoupled from any client connection. A single daemon reader thread drains
 * the terminal's merged stdout/stderr and, under the session monitor, both appends to a bounded
 * raw-output ring buffer (for replay on re-attach) and fans the chunk out to every attached {@link
 * CommandOutputSink}. Sinks attach and detach freely while the process keeps running; nothing about
 * the process lifecycle is tied to a connection. The process ends only by exiting itself or via
 * {@link #terminate()}.
 *
 * <p>Output broadcast happens under the monitor so a freshly attached sink — which replays the ring
 * buffer and is added under the same monitor — never interleaves replayed and live output. The cost
 * is that a stuck client briefly stalls the pump; dead sinks are pruned on the next write.
 *
 * <p><b>What the move changed.</b> The host's version drove a pty4j {@code PtyProcess} that was a
 * {@code docker exec -it} client, and every termination path was shaped by the gap between that
 * client and the real process: killing the client would "orphan the process alive", so the session
 * read a pid file <em>through another {@code docker exec}</em> and signalled the group, falling back
 * to restarting the whole container when nothing landed. Here the process is this daemon's own
 * child. The pid file stays — a compound script's children still need {@code kill -- -pgid} to
 * reach them — but reading it is a local file read, signalling is a local {@code kill}, and the
 * last-resort container restart is gone: a daemon cannot restart the container it is the process
 * of, and {@link ProcessHandle} reaches the descendants that the pgid path would have.
 */
final class CommandSession {

  private static final Logger LOG = System.getLogger(CommandSession.class.getName());

  /** How much recent raw output to retain for replay on re-attach. */
  private static final int RING_CAPACITY_BYTES = 256 * 1024;

  /** A runaway no-newline line is truncated to this many characters when captured. */
  private static final int MAX_LOG_LINE_CHARS = 16 * 1024;

  private final String commandId;
  private final Process process;

  /** The terminal the process holds the slave end of; the session owns and closes it. */
  private final Pty pty;

  private final long graceMillis;

  private final CommandExitListener exitListener;
  private final Runnable onComplete;

  /** Captures lines (may be null to disable logging); see line-framing below. */
  private final CommandLogWriter logWriter;

  /** Monotonic ordinal shared by both channels, so the log has a stable total order. */
  private final AtomicLong logSeq = new AtomicLong();

  /** In-progress OUTPUT line (reader thread only) and STDIN line (under {@link #stdinLock}). */
  private final StringBuilder outLine = new StringBuilder();

  private final StringBuilder inLine = new StringBuilder();

  /** Recent raw output chunks, total bounded by {@link #RING_CAPACITY_BYTES}, for replay. */
  private final Deque<byte[]> ring = new ArrayDeque<>();

  private int ringBytes;

  /** Attached output destinations; mutated and iterated only under the session monitor. */
  private final Deque<CommandOutputSink> sinks = new ArrayDeque<>();

  /** Serializes stdin writes from concurrent clients so their bytes don't interleave. */
  private final Object stdinLock = new Object();

  private volatile boolean terminatedManually;

  /**
   * Signalled once the reader has computed the authoritative exit code and run the exit listener.
   */
  private final CountDownLatch finished = new CountDownLatch(1);

  private volatile int exitCode = -1;

  CommandSession(
      String commandId,
      Process process,
      Pty pty,
      long graceMillis,
      CommandExitListener exitListener,
      Runnable onComplete,
      CommandLogWriter logWriter) {
    this.commandId = commandId;
    this.process = process;
    this.pty = pty;
    this.graceMillis = graceMillis;
    this.exitListener = exitListener;
    this.onComplete = onComplete;
    this.logWriter = logWriter;
  }

  /** Adds a sink before the reader starts (no replay needed — the ring is still empty). */
  synchronized void addInitialSink(CommandOutputSink sink) {
    sinks.add(sink);
  }

  void startReader() {
    Thread reader = new Thread(this::readLoop, "command-" + commandId);
    reader.setDaemon(true);
    reader.start();
  }

  private void readLoop() {
    byte[] buffer = new byte[4096];
    // The terminal master, not process.getInputStream(): the child's stdio is the slave device, so
    // the process object's own streams are empty pipes.
    try (InputStream in = pty.in()) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
        synchronized (this) {
          appendToRing(buffer, read);
          broadcast(text);
        }
        // Raw bytes still drive xterm (above); the framer additionally captures completed lines for
        // the audit log. Done outside the monitor — outLine is touched only by this reader thread.
        frameOutput(text);
      }
    } catch (Exception e) {
      LOG.log(Level.DEBUG, () -> "Output pump ended for command " + commandId, e);
    } finally {
      finish();
    }
  }

  /** Attach a live client: replay the buffered scrollback, then start receiving live output. */
  synchronized void attach(CommandOutputSink sink) {
    if (ringBytes > 0) {
      byte[] snapshot = new byte[ringBytes];
      int pos = 0;
      for (byte[] chunk : ring) {
        System.arraycopy(chunk, 0, snapshot, pos, chunk.length);
        pos += chunk.length;
      }
      try {
        sink.write(new String(snapshot, StandardCharsets.UTF_8));
      } catch (RuntimeException e) {
        LOG.log(Level.DEBUG, () -> "Replay failed for command " + commandId, e);
        return;
      }
    }
    sinks.add(sink);
  }

  synchronized void detach(CommandOutputSink sink) {
    sinks.remove(sink);
  }

  /** Forward a client's keystrokes to the terminal. */
  void input(byte[] data) {
    synchronized (stdinLock) {
      try {
        OutputStream out = pty.out();
        out.write(data);
        out.flush();
      } catch (IOException e) {
        LOG.log(Level.DEBUG, () -> "stdin write failed for command " + commandId, e);
      }
      frameInput(new String(data, StandardCharsets.UTF_8));
    }
  }

  /**
   * Resize the terminal, which makes the kernel raise SIGWINCH in the foreground process group —
   * how a full-screen TUI learns to redraw. On the host this had to travel from the browser through
   * qits to pty4j's outer PTY and on into the container's inner TTY; here it is one ioctl.
   */
  void resize(int cols, int rows) {
    pty.resize(cols, rows);
  }

  /**
   * Send a named signal (e.g. TERM, INT) to the launched script's process <em>group</em>. The script
   * runs under {@code setsid} as a session/group leader with its pgid written to a pid file, so
   * {@code kill -- -pgid} reaches a compound script's children too. Returns false if the signal
   * could not be delivered.
   */
  boolean signal(String signal) {
    return killGroup(signal);
  }

  /**
   * Force-kill the process. Signal the group SIGTERM, escalate to SIGKILL after the grace period,
   * and fall back to {@link ProcessHandle} descendants if neither landed — that fallback replaces
   * the host's "restart the container", which is not available to the process that <em>is</em> the
   * container. The terminal is then closed to unblock the reader's blocking read.
   */
  void terminate() {
    terminatedManually = true;
    killGroup("TERM");
    if (awaitExit(graceMillis) < 0) {
      killGroup("KILL");
      if (awaitExit(TimeUnit.SECONDS.toMillis(2)) < 0) {
        // Nothing reached the group (e.g. the script never wrote its pid file). Walk the process
        // tree instead: setsid may have forked, so the shell can be a grandchild rather than the
        // direct child destroyForcibly() would reach.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
      }
    }
    process.destroyForcibly();
    // Closing the master makes the reader's blocking read return, which is what runs finish().
    pty.close();
    awaitExit(TimeUnit.SECONDS.toMillis(2));
  }

  /**
   * Read the launched script's pgid from its pid file and signal that group.
   *
   * <p>The file is written by the script running in the (untrusted) checkout, so its contents are
   * validated as a plain number before being interpolated into a shell line — the same check the
   * host did, and for the same reason, even though the {@code docker exec} that used to carry it is
   * gone.
   */
  private boolean killGroup(String signal) {
    String pgid;
    try {
      pgid = java.nio.file.Files.readString(CommandRegistry.pidFile(commandId)).trim();
    } catch (IOException | RuntimeException noPidFile) {
      return false;
    }
    if (!pgid.matches("\\d+")) {
      return false;
    }
    try {
      // `kill` via `sh -c` so it works even in an image with no /bin/kill; the signal name is a
      // controlled, validated value and the pgid is digits-only.
      Process kill =
          new ProcessBuilder("sh", "-c", "kill -s " + signal + " -- -" + pgid)
              .redirectErrorStream(true)
              .start();
      return kill.waitFor(5, TimeUnit.SECONDS) && kill.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  boolean isAlive() {
    return process.isAlive();
  }

  /**
   * Block until the reader has finished and computed the exit code (or the timeout elapses).
   * Returns the authoritative exit code, or -1 if it is still running when the timeout elapses.
   * Waiting on the reader's latch — rather than {@code process.exitValue()} — avoids the race where
   * the terminal hits end-of-stream before the OS process is reaped, which would make {@code
   * exitValue()} throw.
   */
  int awaitExit(long timeoutMillis) {
    try {
      if (finished.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
        return exitCode;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return -1;
  }

  private void appendToRing(byte[] buffer, int read) {
    byte[] chunk = new byte[read];
    System.arraycopy(buffer, 0, chunk, 0, read);
    ring.add(chunk);
    ringBytes += read;
    while (ringBytes > RING_CAPACITY_BYTES && ring.size() > 1) {
      ringBytes -= ring.removeFirst().length;
    }
  }

  private void broadcast(String text) {
    for (Iterator<CommandOutputSink> it = sinks.iterator(); it.hasNext(); ) {
      CommandOutputSink sink = it.next();
      if (!sink.isOpen()) {
        it.remove();
        continue;
      }
      try {
        sink.write(text);
      } catch (RuntimeException e) {
        it.remove();
      }
    }
  }

  private void finish() {
    flushPartialLines();
    exitCode = blockingExitCode();
    pty.close();
    try {
      exitListener.onExit(commandId, exitCode, terminatedManually);
    } catch (RuntimeException e) {
      LOG.log(Level.ERROR, () -> "Exit listener failed for command " + commandId, e);
    } finally {
      onComplete.run();
      finished.countDown();
    }
  }

  /** Block until the OS process is reaped so the exit code is reliable (EOF can precede reaping). */
  private int blockingExitCode() {
    try {
      return process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return safeExitValue();
    }
  }

  // --- Line framing for the audit log ---------------------------------------------------------
  // OUTPUT is framed on '\n' (a trailing '\r' is stripped); a bare '\r' stays in the line so a
  // progress bar's content is preserved rather than over-split. STDIN is framed on Enter
  // ('\r'/'\n'). The session assigns the sequence and timestamp at capture time so ordering is
  // fixed at the source.

  private void frameOutput(String text) {
    if (logWriter == null) {
      return;
    }
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '\n') {
        String line = outLine.toString();
        if (line.endsWith("\r")) {
          line = line.substring(0, line.length() - 1);
        }
        emitLog(LogChannel.OUTPUT, line);
        outLine.setLength(0);
      } else {
        outLine.append(ch);
      }
    }
  }

  /** Called under {@link #stdinLock}. */
  private void frameInput(String text) {
    if (logWriter == null) {
      return;
    }
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '\r' || ch == '\n') {
        if (!inLine.isEmpty()) {
          emitLog(LogChannel.STDIN, inLine.toString());
          inLine.setLength(0);
        }
      } else {
        inLine.append(ch);
      }
    }
  }

  /** Flush any trailing partial lines when the process ends (no final newline). */
  private void flushPartialLines() {
    if (logWriter == null) {
      return;
    }
    if (!outLine.isEmpty()) {
      String line = outLine.toString();
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      emitLog(LogChannel.OUTPUT, line);
      outLine.setLength(0);
    }
    synchronized (stdinLock) {
      if (!inLine.isEmpty()) {
        emitLog(LogChannel.STDIN, inLine.toString());
        inLine.setLength(0);
      }
    }
  }

  private void emitLog(LogChannel channel, String content) {
    String capped =
        content.length() > MAX_LOG_LINE_CHARS ? content.substring(0, MAX_LOG_LINE_CHARS) : content;
    logWriter.append(commandId, logSeq.getAndIncrement(), channel, capped, Instant.now());
  }

  private int safeExitValue() {
    try {
      return process.exitValue();
    } catch (IllegalThreadStateException stillRunning) {
      return -1;
    }
  }
}
