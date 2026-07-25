package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.GitStatus;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Watches {@code /workspace} <em>from inside the container</em> and reports working-tree
 * cleanliness to qits as an unsolicited {@link GitStatus}: once on {@link #start()} (the boot
 * report), again on demand via {@link #reportCurrent()} (socket reconnect adoption), and whenever a
 * file event moves the working-tree marker. It is the in-daemon successor to the host's {@code
 * WorkspaceWatchService} — the container now dials its own status home instead of qits reaching in
 * with {@code docker exec inotifywait}.
 *
 * <p>The watch is a single local {@code inotifywait -m -r} fork (no {@code docker exec} prefix — it
 * already runs in the container, like {@link Provisioner}/{@link WorkspaceDescriber} fork git). A
 * burst of raw events opens one coalescing window; when it settles the marker is recomputed once
 * and a {@link GitStatus} is emitted only if it moved.
 *
 * <p>Dedup is on the full working-tree <b>marker</b> (sha256 of {@code git status --porcelain=v2
 * --branch -uall} + {@code git diff}), not the {@code clean} boolean — the same algorithm the host
 * {@code WorkingTreeMarker} used. This preserves the "files changed" signal on a dirty→dirty edit
 * (a second file touched while the tree is already dirty) that a bare boolean would swallow, while
 * still ignoring churn under an excluded/gitignored path. The heavy build dirs and the noisy {@code
 * .git/objects}/{@code .git/logs} are excluded, but {@code .git/index}/{@code HEAD}/{@code refs}
 * stay watched so a {@code git commit} (which touches only {@code .git}, never a work-tree file) is
 * seen and reported clean.
 */
final class GitStatusMonitor {

  private static final Logger LOG = Logger.getLogger(GitStatusMonitor.class);

  /** Where the branch clone lives in every workspace container (image {@code WORKDIR}). */
  private static final File WORKSPACE_DIR = new File("/workspace");

  private final String workspaceId;
  private final String repoId;
  private final String branch;
  private final String parent;
  private final Consumer<DaemonMessage> send;
  private final long coalesceMs;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-git-status");
            thread.setDaemon(true);
            return thread;
          });
  private final Set<String> openWindow = ConcurrentHashMap.newKeySet();

  private volatile Process process;
  private volatile Thread reader;
  private volatile boolean closed;

  /** The last marker we reported on; {@code null} until the first (boot) report. */
  private volatile String lastMarker;

  /** The last {@link GitStatus} emitted, replayed by {@link #reportCurrent()} on reconnect. */
  private volatile GitStatus last;

  GitStatusMonitor(
      String workspaceId,
      String repoId,
      String branch,
      String parent,
      Consumer<DaemonMessage> send,
      long coalesceMs) {
    this.workspaceId = workspaceId;
    this.repoId = repoId;
    this.branch = branch;
    this.parent = parent;
    this.send = send;
    this.coalesceMs = coalesceMs;
  }

  /**
   * Emit the initial status (the boot report) and start watching. Called after the checkout is
   * provisioned so {@code git status} has a tree to read.
   */
  void start() {
    settleFromGit(); // lastMarker is null ⇒ this always emits the boot report
    List<String> argv = watchArgv();
    try {
      Process started =
          new ProcessBuilder(argv)
              .directory(WORKSPACE_DIR.isDirectory() ? WORKSPACE_DIR : null)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      this.process = started;
      Thread t = new Thread(this::readLoop, "workspace-daemon-git-status-watch");
      t.setDaemon(true);
      this.reader = t;
      t.start();
    } catch (IOException e) {
      // No watcher ⇒ the boot report still stands and reconnect re-reports; we just miss live
      // updates. Never fatal (inotifywait absent, watch-limit exhausted): the daemon must live on.
      LOG.warnf(
          e, "git-status watcher failed to start for %s (boot report already sent)", workspaceId);
    }
  }

  private void readLoop() {
    Process p = process;
    if (p == null) {
      return;
    }
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
      String raw;
      while (!closed && (raw = in.readLine()) != null) {
        if (!raw.isBlank()) {
          onRawChange();
        }
      }
    } catch (IOException e) {
      if (!closed) {
        LOG.debugf(e, "git-status watcher read failed for %s", workspaceId);
      }
    }
  }

  /** A raw inotify line arrived: open a coalescing window if one isn't already pending. */
  private void onRawChange() {
    if (openWindow.add("w")) {
      scheduler.schedule(this::settleWindow, coalesceMs, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * The coalescing window closed: clear it <em>before</em> the git-touching recompute (so an event
   * arriving mid-computation opens a fresh window rather than being swallowed), then recompute the
   * marker and report if it moved.
   */
  private void settleWindow() {
    openWindow.remove("w");
    if (!closed) {
      settleFromGit();
    }
  }

  /** Fork the two git reads and feed them to {@link #settle}. */
  private void settleFromGit() {
    settle(capture("git", "status", "--porcelain=v2", "--branch", "-uall"), capture("git", "diff"));
  }

  /**
   * Given the raw {@code git status} and {@code git diff} output, emit a {@link GitStatus} iff the
   * working-tree marker moved. Package-private so a test can drive it with canned output and a
   * capturing {@code send}, without a real git tree.
   */
  void settle(String statusV2, String diff) {
    String marker = sha256(statusV2 + " " + diff);
    if (marker.equals(lastMarker)) {
      return; // nothing meaningful changed — no report
    }
    lastMarker = marker;
    WorkspaceInfo info = WorkspaceDescriber.parse(workspaceId, repoId, branch, parent, statusV2);
    GitStatus status = new GitStatus(workspaceId, !info.dirty(), info.head());
    last = status;
    send.accept(status);
  }

  /**
   * Re-send the last reported status (a no-op before the first report). Invoked on every socket
   * (re)connect so a qits restart that lost its in-memory cache gets the current value re-pushed,
   * mirroring {@code ServiceSupervisor.reportAll}.
   */
  void reportCurrent() {
    GitStatus current = last;
    if (current != null) {
      send.accept(current);
    }
  }

  /** Stop watching: kill the {@code inotifywait} process and shut the scheduler down. */
  void close() {
    closed = true;
    Process p = process;
    if (p != null) {
      p.destroy();
    }
    Thread t = reader;
    if (t != null) {
      t.interrupt();
    }
    scheduler.shutdownNow();
  }

  /**
   * The {@code inotifywait} command over {@code /workspace}: monitor continuously ({@code -m}),
   * recursively ({@code -r}), quietly ({@code -q}), on the mutating events. Exclude the heavy
   * build/VCS dirs whose churn never moves the marker <em>and</em> {@code .git/objects}/{@code
   * .git/logs} (a commit's blob writes + reflog), but keep {@code .git/index}/{@code HEAD}/{@code
   * refs} watched so a commit/checkout is observed. Package-private for the watcher test.
   */
  List<String> watchArgv() {
    return List.of(
        "inotifywait",
        "-m",
        "-r",
        "-q",
        "-e",
        "modify",
        "-e",
        "create",
        "-e",
        "delete",
        "-e",
        "move",
        "-e",
        "close_write",
        "--exclude",
        "(^|/)(node_modules|target|dist|build|\\.angular|\\.gradle)(/|$)|(^|/)\\.git/(objects|logs)(/|$)",
        "/workspace");
  }

  /** Run a git command in {@code /workspace} and return its stdout, or "" on any failure. */
  private static String capture(String... argv) {
    try {
      // Discard stderr so a chatty git can't fill its pipe and deadlock the stdout read (same
      // reasoning as WorkspaceDescriber.capture).
      ProcessBuilder builder =
          new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD);
      if (WORKSPACE_DIR.isDirectory()) {
        builder.directory(WORKSPACE_DIR);
      }
      Process p = builder.start();
      byte[] out = p.getInputStream().readAllBytes();
      if (!p.waitFor(10, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return "";
      }
      return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    } catch (Exception e) {
      return "";
    }
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed present on every JVM; fall back to identity so watching still works.
      return input;
    }
  }
}
