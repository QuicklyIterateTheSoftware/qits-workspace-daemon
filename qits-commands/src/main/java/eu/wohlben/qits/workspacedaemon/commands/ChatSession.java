package eu.wohlben.qits.workspacedaemon.commands;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One live coding-agent <strong>chat</strong> process on plain pipes (not a PTY, which would corrupt
 * line-delimited JSON), driven through a pluggable {@link ChatProtocol} — Claude's stream-json
 * pass-through ({@link StreamJsonChatProtocol}) or Kimi's ACP JSON-RPC client from {@code
 * qits-coding-agents}, both normalizing to the same envelope. The registry counterpart of {@link
 * CommandSession}: same decoupled-from-any-connection model (a scrollback ring for re-attach, a
 * {@link CommandOutputSink} fan-out, a {@link CommandLogWriter}, an exit latch), but the unit of
 * streaming is a <em>JSON line</em>, not raw bytes.
 *
 * <p>Everything the client renders flows through one unified line stream: each event the protocol
 * emits, plus a synthetic {@code {"type":"user","text":…}} echo for every message the user sends.
 * That single stream is ringed (for re-attach) and broadcast (live) — stream interception is the
 * <em>live transport</em>. The lasting record is the imported agent transcript on {@link
 * LogChannel#TRANSCRIPT} (live tail during the run, reconciling sweep on exit); the only stdout
 * lines also captured are failure {@code result} events, which the harness never writes to its
 * transcript, kept on {@link LogChannel#OUTPUT} so error bubbles survive replay. Re-attach restores
 * the <em>entire</em> conversation: the transcript head is stitched to the ring tail at the first
 * ring line whose {@code uuid} the transcript already contains (stream-json events and transcript
 * lines share uuids), so every event replays exactly once.
 *
 * <p>Two things changed in the move. The JSON library is {@link JsonObject} rather than Jackson,
 * for the reasons in this module's pom. And termination signals the process group directly instead
 * of shelling {@code docker exec cat} to read the pid file and {@code docker exec sh -c kill} to
 * act on it — the file and the process are both local now.
 */
final class ChatSession {

  private static final Logger LOG = System.getLogger(ChatSession.class.getName());

  /**
   * How much recent conversation (in bytes of JSONL) to retain in memory. Only the tail — {@link
   * #attach} restores anything older from the captured log.
   */
  private static final int RING_CAPACITY_BYTES = 256 * 1024;

  /** One retained line of the unified stream, tagged with its capture sequence number. */
  private record RingLine(long seq, String line) {}

  private final String commandId;
  private final Process process;
  private final long graceMillis;
  private final ChatProtocol protocol;
  private final CommandExitListener exitListener;
  private final Runnable onComplete;
  private final CommandLogWriter logWriter;
  private final CommandLogReader logReader;

  private final AtomicLong logSeq = new AtomicLong();
  private final Deque<RingLine> ring = new ArrayDeque<>();
  private int ringBytes;
  private final Deque<CommandOutputSink> sinks = new ArrayDeque<>();

  private volatile boolean terminatedManually;
  private final CountDownLatch finished = new CountDownLatch(1);
  private volatile int exitCode = -1;

  ChatSession(
      String commandId,
      Process process,
      long graceMillis,
      ChatProtocol protocol,
      CommandExitListener exitListener,
      Runnable onComplete,
      CommandLogWriter logWriter,
      CommandLogReader logReader) {
    this.commandId = commandId;
    this.process = process;
    this.graceMillis = graceMillis;
    this.protocol = protocol;
    this.exitListener = exitListener;
    this.onComplete = onComplete;
    this.logWriter = logWriter;
    this.logReader = logReader;
  }

  synchronized void addInitialSink(CommandOutputSink sink) {
    sinks.add(sink);
  }

  /**
   * Starts the transport: the protocol pumps the process output through {@link #emitLine} and
   * latches the exit via {@link #finish} when the stream closes.
   */
  void startReader() {
    protocol.start(this::emitLine, this::finish);
  }

  /** Sends a user turn through the transport (which also echoes it into the stream). */
  void sendUser(String text) {
    protocol.sendUser(text);
  }

  /**
   * Ring + broadcast a single JSON line of the unified conversation stream. Only failure {@code
   * result} events are captured to the log: they are absent from the harness transcript, and
   * everything else is covered by the transcript import.
   */
  private void emitLine(String line) {
    long seq;
    synchronized (this) {
      seq = logSeq.getAndIncrement();
      appendToRing(seq, line);
      broadcast(line + "\n");
    }
    if (logWriter != null && ErrorResultLines.isErrorResult(line)) {
      logWriter.append(commandId, seq, LogChannel.OUTPUT, line, Instant.now());
    }
  }

  /**
   * Replays the entire conversation to a (re)connecting client — the head (imported transcript
   * lines, merged with any captured error results) followed by the ring tail — then subscribes it
   * for live lines. The seam is exact: the first ring line whose {@code uuid} the transcript already
   * contains marks where the ring takes over; everything before it comes from the transcript (which
   * also covers the real user turns whose ring echoes predate the seam). Holding the session lock
   * throughout means no live line can interleave mid-replay. Freshness of the head is the live
   * tail's poll cadence; a ring line not yet imported is simply served from the ring — the seam scan
   * tolerates any tail lag.
   */
  synchronized void attach(CommandOutputSink sink) {
    StringBuilder replay = new StringBuilder();
    List<CommandLogReader.TimedLine> transcript =
        logReader != null ? logReader.transcriptLines(commandId) : List.of();
    if (transcript.isEmpty()) {
      // Nothing imported yet: ring-only replay, prefixed by any error results the ring already
      // evicted (their OUTPUT sequences share the ring's space, so the bound is exact).
      if (!ring.isEmpty()) {
        long firstRingSeq = ring.peekFirst().seq();
        if (logReader != null && firstRingSeq > 0) {
          for (CommandLogReader.TimedLine error :
              logReader.outputLinesBefore(commandId, firstRingSeq)) {
            replay.append(error.content()).append('\n');
          }
        }
        for (RingLine entry : ring) {
          replay.append(entry.line()).append('\n');
        }
      }
    } else {
      appendStitched(replay, transcript);
    }
    if (!replay.isEmpty()) {
      try {
        sink.write(replay.toString());
      } catch (RuntimeException e) {
        LOG.log(Level.DEBUG, () -> "Chat replay failed for command " + commandId, e);
        return;
      }
    }
    sinks.add(sink);
  }

  /** Transcript head + ring tail, stitched at the first ring line the transcript contains. */
  private void appendStitched(StringBuilder replay, List<CommandLogReader.TimedLine> transcript) {
    Map<String, Integer> transcriptIndexByUuid = new HashMap<>();
    for (int i = 0; i < transcript.size(); i++) {
      String uuid = uuidOf(transcript.get(i).content());
      if (uuid != null) {
        transcriptIndexByUuid.putIfAbsent(uuid, i);
      }
    }
    int seamIndex = -1;
    long seamSeq = -1;
    int seamRingPos = -1;
    int pos = 0;
    for (RingLine entry : ring) {
      Integer index = transcriptIndexByUuid.get(uuidOf(entry.line()));
      if (index != null) {
        seamIndex = index;
        seamSeq = entry.seq();
        seamRingPos = pos;
        break;
      }
      pos++;
    }
    if (seamIndex >= 0) {
      // Exact seam: transcript strictly before it, ring from it onward. Ring lines before the seam
      // are stdout-only shapes (init, thinking budget, rate limits, user echoes) whose lasting
      // counterparts — where any exist — sit in the served transcript head.
      appendMerged(replay, transcript.subList(0, seamIndex), errorResultsBefore(seamSeq));
      int i = 0;
      for (RingLine entry : ring) {
        if (i++ >= seamRingPos) {
          replay.append(entry.line()).append('\n');
        }
      }
    } else {
      // No shared uuid (tail lag, or the ring holds only echoes/noise): serve everything from both,
      // skipping only synthetic user echoes the transcript head already covers. Residual
      // double-render here is best-effort territory (256 KB of ring vs one poll interval).
      long bound = ring.isEmpty() ? Long.MAX_VALUE : ring.peekFirst().seq();
      appendMerged(replay, transcript, errorResultsBefore(bound));
      Set<String> servedUserTexts = userTexts(transcript);
      for (RingLine entry : ring) {
        String echoText = syntheticEchoText(entry.line());
        if (echoText != null && servedUserTexts.contains(echoText)) {
          continue;
        }
        replay.append(entry.line()).append('\n');
      }
    }
  }

  private List<CommandLogReader.TimedLine> errorResultsBefore(long sequenceExclusive) {
    return sequenceExclusive > 0 && logReader != null
        ? logReader.outputLinesBefore(commandId, sequenceExclusive)
        : List.of();
  }

  /**
   * Interleaves captured error results into the transcript head by timestamp — both carry wall
   * clocks of the same process, and errors sit at turn boundaries seconds apart, so ordering is
   * safe.
   */
  private void appendMerged(
      StringBuilder replay,
      List<CommandLogReader.TimedLine> transcriptHead,
      List<CommandLogReader.TimedLine> errors) {
    int e = 0;
    for (CommandLogReader.TimedLine line : transcriptHead) {
      while (e < errors.size() && !errors.get(e).timestamp().isAfter(line.timestamp())) {
        replay.append(errors.get(e++).content()).append('\n');
      }
      replay.append(line.content()).append('\n');
    }
    while (e < errors.size()) {
      replay.append(errors.get(e++).content()).append('\n');
    }
  }

  /** The top-level {@code uuid} of a stream/transcript line, or null (echoes, noise, non-JSON). */
  private static String uuidOf(String line) {
    JsonObject node = parseQuietly(line);
    return node == null ? null : node.getString("uuid", null);
  }

  /** The text of a synthetic qits user echo ({@code {"type":"user","text":…}}), else null. */
  private static String syntheticEchoText(String line) {
    JsonObject node = parseQuietly(line);
    if (node == null || !"user".equals(node.getString("type"))) {
      return null;
    }
    return node.getString("text", null);
  }

  /**
   * Every user-turn text in the transcript: {@code user} lines (string content, or text blocks
   * joined) and {@code queued_command} attachments (how the harness persists a turn sent while the
   * agent was mid-turn).
   */
  private static Set<String> userTexts(List<CommandLogReader.TimedLine> transcript) {
    Set<String> texts = new HashSet<>();
    for (CommandLogReader.TimedLine line : transcript) {
      JsonObject node = parseQuietly(line.content());
      if (node == null) {
        continue;
      }
      String type = node.getString("type");
      if ("user".equals(type)) {
        Object content = node.getJsonObject("message", new JsonObject()).getValue("content");
        if (content instanceof String text) {
          texts.add(text);
        } else if (content instanceof JsonArray blocks) {
          addJoinedTextBlocks(texts, blocks);
        }
      } else if ("attachment".equals(type)) {
        JsonObject attachment = node.getJsonObject("attachment", new JsonObject());
        if ("queued_command".equals(attachment.getString("type"))
            && attachment.getValue("prompt") instanceof JsonArray prompt) {
          addJoinedTextBlocks(texts, prompt);
        }
      }
    }
    return texts;
  }

  private static void addJoinedTextBlocks(Set<String> texts, JsonArray blocks) {
    StringBuilder joined = new StringBuilder();
    for (Object element : blocks) {
      if (element instanceof JsonObject block && "text".equals(block.getString("type"))) {
        joined.append(block.getString("text", ""));
      }
    }
    if (!joined.isEmpty()) {
      texts.add(joined.toString());
    }
  }

  /** Parse one line, or null if it is not a JSON object — echoes and noise both land here. */
  private static JsonObject parseQuietly(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
    try {
      return new JsonObject(line);
    } catch (DecodeException | ClassCastException e) {
      return null;
    }
  }

  synchronized void detach(CommandOutputSink sink) {
    sinks.remove(sink);
  }

  void terminate() {
    terminatedManually = true;
    // Close the transport first (stdin/EOF, plus any protocol-level cancel), so the agent sees the
    // session end cleanly before we escalate to signals.
    protocol.close();
    // A compound script's children are only reachable through the process group, which the launch
    // recorded to a pid file under setsid; escalate to SIGKILL after the grace period.
    killGroup("TERM");
    if (awaitExit(graceMillis) < 0) {
      killGroup("KILL");
    }
    process.destroy();
    awaitExit(TimeUnit.SECONDS.toMillis(2));
  }

  /**
   * Read the launched script's pgid from its pid file and signal that group. The file is written by
   * the script running in the (untrusted) checkout, so its contents are validated as a plain number
   * before being interpolated into a shell line.
   */
  private boolean killGroup(String signal) {
    String pgid;
    try {
      pgid = Files.readString(CommandRegistry.pidFile(commandId)).trim();
    } catch (IOException | RuntimeException noPidFile) {
      return false;
    }
    if (!pgid.matches("\\d+")) {
      return false;
    }
    try {
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

  private void appendToRing(long seq, String line) {
    ring.add(new RingLine(seq, line));
    ringBytes += line.length();
    while (ringBytes > RING_CAPACITY_BYTES && ring.size() > 1) {
      ringBytes -= ring.removeFirst().line().length();
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
    try {
      exitCode = process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      exitListener.onExit(commandId, exitCode, terminatedManually);
    } catch (RuntimeException e) {
      LOG.log(Level.ERROR, () -> "Exit listener failed for chat command " + commandId, e);
    } finally {
      onComplete.run();
      finished.countDown();
    }
  }
}
