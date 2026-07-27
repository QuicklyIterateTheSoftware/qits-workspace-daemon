package eu.wohlben.qits.workspacedaemon.commands;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures command log lines and answers reads over them. Both halves of the host's {@code
 * CommandLogService} in one class, minus everything that existed only because the far side was a
 * database: no queue, no {@code command-log-writer} thread, no batch size, no transactions, and no
 * {@code CommandLogBatchPersister}. {@link #append} writes straight into the command's {@link
 * CommandLogBuffer} on the caller's thread, which is the session's reader thread — the cost that
 * justified going async was the round-trip, and there is no round-trip left.
 *
 * <p>{@link #importLines} correspondingly stops being special. On the host it bypassed the async
 * queue so the transcript sweep's delete-and-reimport could not interleave with a drain; with a
 * synchronous append there is no drain to interleave with, and it is a plain loop.
 */
public final class CommandLogService implements CommandLogWriter, CommandLogReader {

  private final CommandStore store;
  private final LogLineClassifier classifier;

  /**
   * @param classifier stamps severity per line; may be null, in which case every line is
   *     unclassified — the same result the host got when no classifier implementation was wired
   */
  public CommandLogService(CommandStore store, LogLineClassifier classifier) {
    this.store = store;
    this.classifier = classifier;
  }

  /** One line to import, already sequenced by its producer. */
  public record PendingLine(
      String commandId, long sequence, LogChannel channel, String content, Instant timestamp) {}

  @Override
  public void append(
      String commandId, long sequence, LogChannel channel, String content, Instant timestamp) {
    LogSeverity severity = classify(channel, content);
    store.log(commandId).append(new CommandLogLine(sequence, channel, content, severity, timestamp));
  }

  /**
   * Severity is stamped on captured output only. STDIN is what the human typed and TRANSCRIPT is
   * the harness's own record; classifying either would put a severity on text that never came from
   * a process's log stream.
   */
  private LogSeverity classify(LogChannel channel, String content) {
    if (classifier == null || channel != LogChannel.OUTPUT) {
      return null;
    }
    return classifier.classify(content).orElse(null);
  }

  @Override
  public List<TimedLine> transcriptLines(String commandId) {
    return store.log(commandId).channel(LogChannel.TRANSCRIPT).stream()
        .map(line -> new TimedLine(line.sequence(), line.content(), line.timestamp()))
        .toList();
  }

  @Override
  public List<TimedLine> outputLinesBefore(String commandId, long sequenceExclusive) {
    return store.log(commandId).channelBefore(LogChannel.OUTPUT, sequenceExclusive).stream()
        .map(line -> new TimedLine(line.sequence(), line.content(), line.timestamp()))
        .toList();
  }

  /** A command's captured log in order; a non-null {@code severity} narrows to those lines. */
  public List<CommandLogLine> log(String commandId, LogSeverity severity) {
    return log(commandId, severity, null);
  }

  /**
   * A command's captured log in order; non-null {@code severity}/{@code channel} narrow to those
   * lines (channel separates intercepted stdio from the imported agent transcript).
   */
  public List<CommandLogLine> log(String commandId, LogSeverity severity, LogChannel channel) {
    CommandLogBuffer buffer = store.log(commandId);
    List<CommandLogLine> lines = channel != null ? buffer.channel(channel) : buffer.all();
    if (severity != null) {
      lines = lines.stream().filter(line -> line.severity() == severity).toList();
    }
    return lines;
  }

  /**
   * The conversation of a chat: its imported {@code TRANSCRIPT} lines with the recorded
   * error-result {@code OUTPUT} lines interleaved by timestamp (one clock, and errors sit at turn
   * boundaries seconds apart). The OUTPUT side is <em>filtered</em> to error results so a chat that
   * also streamed its events there cannot double-render the conversation. No {@code TRANSCRIPT}
   * lines at all means the transcript has not been imported yet — which is every chat while it is
   * still running, since the sweep runs at exit — so fall back to the full {@code OUTPUT} stream.
   */
  public List<CommandLogLine> chatLog(String commandId, LogSeverity severity) {
    List<CommandLogLine> transcript = store.log(commandId).channel(LogChannel.TRANSCRIPT);
    if (transcript.isEmpty()) {
      return log(commandId, severity, LogChannel.OUTPUT);
    }
    List<CommandLogLine> errors =
        store.log(commandId).channel(LogChannel.OUTPUT).stream()
            .filter(line -> ErrorResultLines.isErrorResult(line.content()))
            .toList();
    List<CommandLogLine> merged = mergeByTimestamp(transcript, errors);
    if (severity != null) {
      merged = merged.stream().filter(line -> line.severity() == severity).toList();
    }
    return merged;
  }

  /** Transcript order is authoritative; each error slots before the first later transcript line. */
  private static List<CommandLogLine> mergeByTimestamp(
      List<CommandLogLine> transcript, List<CommandLogLine> errors) {
    if (errors.isEmpty()) {
      return transcript;
    }
    List<CommandLogLine> merged = new ArrayList<>(transcript.size() + errors.size());
    int e = 0;
    for (CommandLogLine line : transcript) {
      while (e < errors.size() && !errors.get(e).timestamp().isAfter(line.timestamp())) {
        merged.add(errors.get(e++));
      }
      merged.add(line);
    }
    while (e < errors.size()) {
      merged.add(errors.get(e++));
    }
    return merged;
  }

  /** Import already-sequenced lines (the transcript sweep). */
  public void importLines(List<PendingLine> lines) {
    for (PendingLine line : lines) {
      append(line.commandId(), line.sequence(), line.channel(), line.content(), line.timestamp());
    }
  }

  /** Drop a command's lines on one channel — the sweep's delete-and-reimport idempotency. */
  public long deleteChannel(String commandId, LogChannel channel) {
    return store.log(commandId).clearChannel(channel);
  }

  /** The next sequence number for a command, taken from its buffer. */
  public long nextSequence(String commandId) {
    return store.log(commandId).nextSequence();
  }
}
