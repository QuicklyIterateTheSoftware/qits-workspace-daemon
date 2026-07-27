package eu.wohlben.qits.workspacedaemon.commands;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One command's captured log, bounded.
 *
 * <p>This is where the host's async write path went. There, {@code CommandLogService} enqueued each
 * line onto a {@link java.util.concurrent.BlockingQueue} that a single {@code command-log-writer}
 * thread drained in batches of 256 into one transaction each, because the alternative was a
 * database round-trip per line and a chatty {@code mvn test} emits thousands. Appending to an
 * in-memory deque costs nothing worth moving off the capture thread, so the queue, the writer
 * thread, the batch size and {@code CommandLogBatchPersister} all go; {@link #append} is called
 * directly from the session's reader thread.
 *
 * <p><b>The bound is the behavioural change.</b> A {@code command_log_line} row was a CLOB in a
 * database on a host with a disk; these lines are heap in a container sized for the workspace's own
 * build. So the buffer keeps the most recent {@value #DEFAULT_CAPACITY} lines per command and drops
 * the oldest beyond that, which the host never did. The number is a compromise: large enough that
 * an ordinary action's whole output is retained, small enough that a runaway process cannot walk
 * the daemon into an OOM and take the container's agent down with it. {@link #dropped()} reports
 * how many were lost so a reader can say so rather than silently showing a truncated log.
 *
 * <p>Not synchronized on the whole class: appends come from one session's reader thread per
 * command, reads from the API's worker pool. All mutation is behind {@code this}, which is
 * uncontended in practice and simpler to reason about than a lock-free ring.
 */
public final class CommandLogBuffer {

  /**
   * Lines retained per command. Chosen against the two real consumers: a full {@code mvn verify}
   * on a mid-size repo lands in the low tens of thousands of lines, and an agent chat's OUTPUT
   * channel carries only error results (its conversation lives on TRANSCRIPT).
   */
  public static final int DEFAULT_CAPACITY = 50_000;

  private final int capacity;
  private final Deque<CommandLogLine> lines = new ArrayDeque<>();

  /** Monotonic per-command ordinal. Never reset, so a dropped prefix leaves a visible gap. */
  private long nextSequence = 1;

  private long dropped;

  public CommandLogBuffer() {
    this(DEFAULT_CAPACITY);
  }

  public CommandLogBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.capacity = capacity;
  }

  /**
   * Take the next sequence number. The session stamps sequence and timestamp at capture so ordering
   * is fixed at the source rather than at the buffer, exactly as it was when the write was async.
   */
  public synchronized long nextSequence() {
    return nextSequence++;
  }

  /** Append one already-sequenced line, evicting the oldest if the buffer is full. */
  public synchronized void append(CommandLogLine line) {
    if (lines.size() >= capacity) {
      lines.removeFirst();
      dropped++;
    }
    lines.addLast(line);
    if (line.sequence() >= nextSequence) {
      nextSequence = line.sequence() + 1;
    }
  }

  /** Every retained line, oldest first. */
  public synchronized List<CommandLogLine> all() {
    return List.copyOf(lines);
  }

  /** Retained lines on one channel, oldest first. */
  public synchronized List<CommandLogLine> channel(LogChannel channel) {
    List<CommandLogLine> selected = new ArrayList<>();
    for (CommandLogLine line : lines) {
      if (line.channel() == channel) {
        selected.add(line);
      }
    }
    return selected;
  }

  /** Retained lines on one channel with sequence strictly below the bound, oldest first. */
  public synchronized List<CommandLogLine> channelBefore(LogChannel channel, long sequenceExclusive) {
    List<CommandLogLine> selected = new ArrayList<>();
    for (CommandLogLine line : lines) {
      if (line.channel() == channel && line.sequence() < sequenceExclusive) {
        selected.add(line);
      }
    }
    return selected;
  }

  /**
   * Drop every line on one channel, returning how many went. The transcript sweep is
   * delete-and-reimport, so it needs this to stay idempotent across repeated sweeps of the same
   * command.
   */
  public synchronized long clearChannel(LogChannel channel) {
    long removed = lines.stream().filter(line -> line.channel() == channel).count();
    lines.removeIf(line -> line.channel() == channel);
    return removed;
  }

  /** How many lines have been evicted for capacity. Zero for any command that stayed in bounds. */
  public synchronized long dropped() {
    return dropped;
  }

  public synchronized int size() {
    return lines.size();
  }
}
