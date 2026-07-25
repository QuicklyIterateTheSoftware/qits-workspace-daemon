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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
 * already runs in the container, like {@link Provisioner}/{@link WorkspaceDescriber} fork git). Raw
 * events feed a <b>trailing debounce</b>: every event (re)arms a timer, so the marker is recomputed
 * only once the tree has been <em>quiet</em> for {@code coalesceMs} (default 1.5s), and a {@link
 * GitStatus} is emitted only if the marker moved. The recompute runs {@code git status}/{@code git
 * diff} with {@code --no-optional-locks}, so it never takes {@code .git/index.lock} and thus can
 * never race a concurrent {@code git commit}/{@code push} for it (the {@code index.lock} contention
 * that once forced a 20s window — see the resolved contention issue). With the lock race gone the
 * debounce is now just a short coalescing gate: it collapses a commit's write burst to a single
 * recompute and keeps the badge from flickering, landing the dirty→clean report ~1.5s after the
 * commit instead of 20s. A {@code maxWaitMs} cap (default 120s) bounds the wait so a workspace
 * under sustained churn (an editor autosaving, a watch-mode task) still refreshes its badge at a
 * bounded cadence instead of going silent forever.
 *
 * <p>Dedup is on the full working-tree <b>marker</b> (sha256 of {@code git status --porcelain=v2
 * --branch -uall} + {@code git diff}), not the {@code clean} boolean — the same algorithm the host
 * {@code WorkingTreeMarker} used. This preserves the "files changed" signal on a dirty→dirty edit
 * (a second file touched while the tree is already dirty) that a bare boolean would swallow, while
 * still ignoring churn under an excluded/gitignored path. The heavy build dirs, the noisy {@code
 * .git/objects}/{@code .git/logs}, and the remote-tracking bookkeeping ({@code
 * .git/refs/remotes}/{@code .git/FETCH_HEAD}/{@code .git/ORIG_HEAD}, which a push/fetch writes but
 * which never move the marker) are excluded, but {@code .git/index}/{@code HEAD}/{@code refs/heads}
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
  private final long maxWaitMs;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-daemon-git-status");
            thread.setDaemon(true);
            return thread;
          });

  /** Debounce bookkeeping — all reads/writes guarded by {@link #debounceLock}. */
  private final Object debounceLock = new Object();

  /** The armed trailing-debounce timer, or {@code null} between bursts. */
  private ScheduledFuture<?> pending;

  /** {@code System.nanoTime()} deadline at which the current burst must settle regardless. */
  private long burstDeadlineNanos;

  /**
   * Monotonic id of the currently-armed timer. A timer that fires but no longer matches (a newer
   * event re-armed after it had already started running, so {@link ScheduledFuture#cancel} came too
   * late) is a stale wake-up and bows out — preventing two concurrent recomputes.
   */
  private long generation;

  private volatile Process process;
  private volatile Thread reader;
  private volatile boolean closed;

  /** The last marker we reported on; {@code null} until the first (boot) report. */
  private volatile String lastMarker;

  /** The last {@link GitStatus} emitted, replayed by {@link #reportCurrent()} on reconnect. */
  private volatile GitStatus last;

  /**
   * What runs when a debounce window elapses — {@link #settleFromGit()} in production; a test
   * injects a git-free counter so the debounce/reset/cap timing can be driven without a real tree.
   */
  private final Runnable settleAction;

  GitStatusMonitor(
      String workspaceId,
      String repoId,
      String branch,
      String parent,
      Consumer<DaemonMessage> send,
      long coalesceMs,
      long maxWaitMs) {
    this(workspaceId, repoId, branch, parent, send, coalesceMs, maxWaitMs, null);
  }

  /**
   * Full constructor with a settle-action override ({@code null} ⇒ the real {@code git} recompute).
   */
  GitStatusMonitor(
      String workspaceId,
      String repoId,
      String branch,
      String parent,
      Consumer<DaemonMessage> send,
      long coalesceMs,
      long maxWaitMs,
      Runnable settleActionOverride) {
    this.workspaceId = workspaceId;
    this.repoId = repoId;
    this.branch = branch;
    this.parent = parent;
    this.send = send;
    this.coalesceMs = coalesceMs;
    // The cap must never undercut the quiet period, or the burst would settle before the debounce
    // could coalesce it. A non-positive cap disables the ceiling (pure trailing debounce).
    this.maxWaitMs = maxWaitMs <= 0 ? Long.MAX_VALUE : Math.max(maxWaitMs, coalesceMs);
    this.settleAction = settleActionOverride != null ? settleActionOverride : this::settleFromGit;
  }

  /**
   * Emit the initial status (the boot report) and start watching. Called after the checkout is
   * provisioned so {@code git status} has a tree to read.
   */
  void start() {
    // lastMarker is null ⇒ this emits the boot report — unless the read comes back blank (git
    // failed), in which case settle() skips it and the first live event re-reads.
    settleFromGit();
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

  /**
   * A raw inotify line arrived: (re)arm the trailing-debounce timer so the recompute lands only
   * once the tree falls quiet for {@code coalesceMs}. Each event cancels the previous timer and
   * schedules a fresh one — so a sustained burst (e.g. a {@code git commit} scribbling under {@code
   * .git/index}/{@code refs/heads}) collapses to a single recompute after the burst settles rather
   * than firing {@code git status} on every event. (The recompute is lock-free via {@code
   * --no-optional-locks}, so this coalescing is for a stable, non-flickering badge — not to dodge
   * {@code index.lock}, which the flag already handles.) The {@code maxWaitMs} cap keeps the settle
   * from being starved forever under continuous churn: the delay is clamped so the recompute never
   * lands later than {@code maxWaitMs} after the first event of the burst. Package-private so the
   * debounce timing can be driven in a test without a real {@code inotifywait} fork.
   */
  void onRawChange() {
    synchronized (debounceLock) {
      if (closed) {
        return;
      }
      long now = System.nanoTime();
      if (pending == null) {
        // First event of a new burst — anchor the ceiling (guard against maxWaitMs == MAX_VALUE).
        long capNanos = maxWaitMs == Long.MAX_VALUE ? Long.MAX_VALUE : maxWaitMs * 1_000_000L;
        burstDeadlineNanos = capNanos == Long.MAX_VALUE ? Long.MAX_VALUE : now + capNanos;
      } else {
        pending.cancel(false);
      }
      long remainingToDeadlineMs =
          burstDeadlineNanos == Long.MAX_VALUE
              ? Long.MAX_VALUE
              : Math.max(0L, (burstDeadlineNanos - now) / 1_000_000L);
      long delayMs = Math.min(coalesceMs, remainingToDeadlineMs);
      long mine = ++generation;
      pending = scheduler.schedule(() -> onWindowElapsed(mine), delayMs, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * The debounce timer elapsed: close the burst <em>before</em> the git-touching recompute (so an
   * event arriving mid-computation starts a fresh burst rather than being swallowed), then
   * recompute the marker and report if it moved. The git forks run <em>outside</em> {@link
   * #debounceLock} so they never block the reader thread's {@link #onRawChange}.
   */
  private void onWindowElapsed(long mine) {
    synchronized (debounceLock) {
      if (mine != generation) {
        return; // a newer event re-armed after this timer began firing — this wake-up is stale
      }
      pending = null; // burst closed; the next event begins a new one
      if (closed) {
        return;
      }
    }
    settleAction.run();
  }

  /** Fork the two git reads and feed them to {@link #settle}. */
  private void settleFromGit() {
    // --no-optional-locks: neither read takes .git/index.lock (git skips the opportunistic index
    // refresh), so the recompute can never race a concurrent commit/push for that lock. This is
    // what
    // lets the debounce be a short quiescence gate rather than the 20s lock-avoidance window it
    // once
    // had to be (see the class javadoc and the resolved index.lock-contention issue).
    settle(
        capture("git", "--no-optional-locks", "status", "--porcelain=v2", "--branch", "-uall"),
        capture("git", "--no-optional-locks", "diff"));
  }

  /**
   * Given the raw {@code git status} and {@code git diff} output, emit a {@link GitStatus} iff the
   * working-tree marker moved. Package-private so a test can drive it with canned output and a
   * capturing {@code send}, without a real git tree.
   */
  void settle(String statusV2, String diff) {
    if (statusV2.isBlank()) {
      // A clean tree's `status --porcelain=v2 --branch` always carries the `# branch.*` headers, so
      // blank output means the git read itself failed (non-zero exit ⇒ capture() returned ""), not
      // a
      // clean tree. WorkspaceDescriber.parse would read blank as not-dirty and we'd flip the badge
      // falsely clean — so skip. The shorter debounce can land a read mid-operation, making this
      // reachable; the next inotify event (or a reconnect) re-reads. Never overwrites lastMarker,
      // so
      // a real change after a transient failure still reports.
      return;
    }
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
   * build/VCS dirs whose churn never moves the marker <em>and</em> the {@code .git} bookkeeping
   * that never changes working-tree cleanliness or {@code branch.oid}: {@code .git/objects}/{@code
   * .git/logs} (a commit's blob writes + reflog) plus {@code .git/refs/remotes}/{@code
   * .git/FETCH_HEAD}/{@code .git/ORIG_HEAD} (a push/fetch/auto-push's remote-tracking bookkeeping,
   * top-level or a submodule's gitdir) — so a push's ordinary loose-ref writes don't re-arm the
   * badge debounce. (A periodic {@code packed-refs} rewrite still can, but a re-arm is harmless now
   * that the recompute is lock-free and short.) But keep {@code .git/index}/{@code HEAD}/{@code
   * refs/heads} watched so a commit/checkout — and a pull/merge that advances the local branch and
   * rewrites work-tree files — is observed. Package-private for the watcher test.
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
        // The `(modules/[^/]+/)*` hop matches a submodule's own gitdir (`.git/modules/<name>/…`, at
        // any nesting depth) as well as the top-level `.git/`, so a submodule fetch/push's remote
        // bookkeeping is excluded too — otherwise it would still re-arm the debounce in a workspace
        // with submodules.
        "(^|/)(node_modules|target|dist|build|\\.angular|\\.gradle)(/|$)"
            + "|(^|/)\\.git/(modules/[^/]+/)*(objects|logs|refs/remotes)(/|$)"
            + "|(^|/)\\.git/(modules/[^/]+/)*(FETCH_HEAD|ORIG_HEAD)$",
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
