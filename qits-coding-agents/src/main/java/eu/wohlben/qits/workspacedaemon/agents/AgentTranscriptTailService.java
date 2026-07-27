package eu.wohlben.qits.workspacedaemon.agents;

import eu.wohlben.qits.workspacedaemon.agents.acp.KimiEventNormalizer;
import eu.wohlben.qits.workspacedaemon.commands.CommandLogService;
import eu.wohlben.qits.workspacedaemon.commands.LogChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live counterpart of {@link AgentTranscriptService}'s exit sweep: while a chat runs, polls its
 * main-session transcript JSONL off the shared claude volume and appends each new complete line to
 * the command's log buffer on {@link LogChannel#TRANSCRIPT} — so a mid-run re-attach can serve the
 * durable head of the conversation. Main session only; sidechains and stats stay exit-sweep
 * territory, and the exit sweep's delete-and-reimport reconciles whatever the tail did.
 *
 * <p>Poll-based (no {@code WatchService}), with a partial line buffered across polls until its
 * newline arrives. Claude lines are imported raw (already the event envelope); Kimi {@code
 * wire.jsonl} lines are run through a per-session {@link KimiEventNormalizer} — the same normalizer +
 * minted uuids the exit sweep uses — so a mid-run re-attach stitches and renders identically to the
 * post-exit reconciliation. The {@code importedLines} high-water counts raw wire lines consumed (what
 * the exit sweep's settle compares against), not emitted envelopes.
 *
 * <p>Lifecycle is explicit {@link #start()}/{@link #close()} rather than CDI's {@code
 * @PostConstruct}/{@code @PreDestroy}: this module is framework-free, so {@code ControlSocket}
 * constructs it and closes it alongside the daemon's other long-lived pieces.
 */
public final class AgentTranscriptTailService implements AutoCloseable {

  private static final Logger LOG = System.getLogger(AgentTranscriptTailService.class.getName());

  private static final AtomicBoolean MISSING_CONFIG_DIR_LOGGED = new AtomicBoolean();

  /** The default poll cadence, matching the host's {@code qits.agent.transcript-tail-poll-ms}. */
  public static final long DEFAULT_POLL_MILLIS = 500;

  private final AgentTranscriptService transcriptService;
  private final CommandLogService commandLogService;
  private final long pollMillis;

  private final Map<String, Tail> tails = new ConcurrentHashMap<>();
  private volatile ScheduledExecutorService scheduler;

  public AgentTranscriptTailService(
      AgentTranscriptService transcriptService, CommandLogService commandLogService) {
    this(transcriptService, commandLogService, DEFAULT_POLL_MILLIS);
  }

  public AgentTranscriptTailService(
      AgentTranscriptService transcriptService,
      CommandLogService commandLogService,
      long pollMillis) {
    this.transcriptService = transcriptService;
    this.commandLogService = commandLogService;
    this.pollMillis = pollMillis;
  }

  /** Starts the poll scheduler. Idempotent. */
  public void start() {
    if (scheduler != null) {
      return;
    }
    scheduler =
        Executors.newScheduledThreadPool(
            2,
            runnable -> {
              Thread thread = new Thread(runnable, "transcript-tail");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** Stops every tail and the scheduler. Idempotent. */
  @Override
  public void close() {
    ScheduledExecutorService running = scheduler;
    scheduler = null;
    tails.clear();
    if (running != null) {
      running.shutdownNow();
    }
  }

  /**
   * Begin polling for the command's main-session transcript (idempotent per command). The harness is
   * passed by the launch (already resolved) so the config dir picks the right dot-dir; the
   * per-session harness for the layout switch comes off {@link AgentTranscriptService.SessionInfo}.
   */
  public void startTail(String commandId, AgentType agentType) {
    startTail(commandId, transcriptService.configDir(agentType));
  }

  /**
   * {@link #startTail(String, AgentType)}; package-visible so tests can point it at a fixture config
   * dir. A tail started before {@link #start()} still registers and is drained by {@link
   * #stopAndDrain}; it just never polls on a schedule.
   */
  void startTail(String commandId, Path configDir) {
    tails.computeIfAbsent(
        commandId,
        id -> {
          Tail tail = new Tail(id, configDir);
          ScheduledExecutorService running = scheduler;
          if (running != null) {
            tail.task =
                running.scheduleWithFixedDelay(
                    tail::pollSafely, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
          }
          return tail;
        });
  }

  /**
   * Stop the command's tail: cancel the schedule, run one final synchronous drain (the tail's own
   * lock serializes it against an in-flight poll), and return the high-water count of imported
   * main-session lines — after this no tail write can race the exit sweep's delete-and-reimport.
   */
  public long stopAndDrain(String commandId) {
    Tail tail = tails.remove(commandId);
    if (tail == null) {
      return 0;
    }
    if (tail.task != null) {
      tail.task.cancel(false);
    }
    tail.pollSafely();
    return tail.importedLines;
  }

  /** Run one poll synchronously — deterministic drive for tests. */
  void pollNow(String commandId) {
    Tail tail = tails.get(commandId);
    if (tail != null) {
      tail.pollSafely();
    }
  }

  /** One tracked command's tail: awaiting the file, then byte-position framing across polls. */
  private class Tail {

    private final String commandId;
    private final Path configDir;
    private final ByteArrayOutputStream partialLine = new ByteArrayOutputStream();

    private Future<?> task;
    private Path file;
    private Object fileKey;
    private long bytePosition;
    private long nextSeq = AgentTranscriptService.TRANSCRIPT_SEQ_BASE;
    private volatile long importedLines;

    /** Non-null under Kimi: normalizes each wire.jsonl line into the shared event envelope. */
    private KimiEventNormalizer normalizer;

    private Tail(String commandId, Path configDir) {
      this.commandId = commandId;
      this.configDir = configDir;
    }

    private synchronized void pollSafely() {
      try {
        poll();
      } catch (IOException | RuntimeException e) {
        LOG.log(Level.DEBUG, () -> "Transcript tail poll failed for command " + commandId, e);
      }
    }

    private void poll() throws IOException {
      if (file == null && !locate()) {
        return;
      }
      BasicFileAttributes attributes;
      try {
        attributes = Files.readAttributes(file, BasicFileAttributes.class);
      } catch (NoSuchFileException e) {
        file = null; // vanished (unexpected) — re-locate; a recreated file re-seeds via fileKey.
        return;
      }
      if (fileKey == null) {
        fileKey = attributes.fileKey();
      } else if (!Objects.equals(fileKey, attributes.fileKey())
          || attributes.size() < bytePosition) {
        // The harness only appends, so truncation/replacement is unexpected: re-seed the channel.
        LOG.log(
            Level.WARNING,
            () -> "Transcript of command " + commandId + " was truncated or replaced — re-importing");
        commandLogService.deleteChannel(commandId, LogChannel.TRANSCRIPT);
        bytePosition = 0;
        nextSeq = AgentTranscriptService.TRANSCRIPT_SEQ_BASE;
        importedLines = 0;
        partialLine.reset();
        if (normalizer != null) {
          normalizer.reset(); // re-import from the top must restart the minted indices too.
        }
        fileKey = attributes.fileKey();
      }
      readNewBytes();
    }

    /**
     * Resolve the transcript file — hook-reported path, harness convention, filename lookup — with a
     * fresh session read per attempt (the hook report typically lands moments after launch). Imports
     * from byte 0 deliberately: a resumed session's file already holds prior history.
     */
    private boolean locate() {
      if (!Files.isDirectory(configDir)) {
        if (MISSING_CONFIG_DIR_LOGGED.compareAndSet(false, true)) {
          LOG.log(
              Level.WARNING,
              () ->
                  "Agent config dir "
                      + configDir
                      + " does not exist — live transcript import is disabled"
                      + " (is the claude volume mounted?)");
        }
        return false;
      }
      AgentTranscriptService.SessionInfo session = transcriptService.mainSession(commandId);
      if (session == null) {
        return false;
      }
      if (session.agentType() == AgentType.KIMI && normalizer == null) {
        normalizer = new KimiEventNormalizer(session.sessionId());
      }
      file =
          transcriptService.resolveTranscript(
              configDir, CodingAgentFactory.ofType(session.agentType()), session);
      return file != null;
    }

    private void readNewBytes() throws IOException {
      List<CommandLogService.PendingLine> batch = new ArrayList<>();
      try (SeekableByteChannel channel = Files.newByteChannel(file)) {
        channel.position(bytePosition);
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (channel.read(buffer) > 0) {
          buffer.flip();
          while (buffer.hasRemaining()) {
            byte b = buffer.get();
            bytePosition++;
            if (b == '\n') {
              completeLine(batch);
            } else {
              partialLine.write(b);
            }
          }
          buffer.clear();
        }
      } catch (NoSuchFileException e) {
        file = null;
      }
      if (!batch.isEmpty()) {
        // A failed import skips these lines until the exit sweep reconciles — best-effort live.
        commandLogService.importLines(List.copyOf(batch));
      }
    }

    private void completeLine(List<CommandLogService.PendingLine> batch) {
      String line = partialLine.toString(StandardCharsets.UTF_8);
      partialLine.reset();
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      if (line.isBlank()) {
        return; // position already consumed; blank lines carry nothing.
      }
      // High-water counts raw wire lines consumed (incl. Kimi noise the normalizer drops), because
      // that is what the exit sweep's settle compares against the raw file — over-counting on a
      // failed import only makes the sweep wait a little longer, never reimport short.
      importedLines++;
      Instant own = transcriptService.lineTimestamp(line);
      Instant when = own != null ? own : Instant.now();
      if (normalizer == null) {
        batch.add(
            new CommandLogService.PendingLine(
                commandId, nextSeq++, LogChannel.TRANSCRIPT, line, when));
        return;
      }
      for (String envelope : normalizer.onWireLine(line)) {
        batch.add(
            new CommandLogService.PendingLine(
                commandId, nextSeq++, LogChannel.TRANSCRIPT, envelope, when));
      }
    }
  }
}
