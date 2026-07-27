package eu.wohlben.qits.workspacedaemon.commands;

import java.time.Instant;
import java.util.List;

/**
 * Read side of the command log — the flip side of {@link CommandLogWriter}, kept framework-free for
 * the same reason. {@link ChatSession#attach} uses it to restore the head of a conversation (the
 * imported agent transcript, plus the few error-result lines recorded on {@code OUTPUT}) so a
 * reconnecting client sees the whole transcript, not just recent scrollback.
 */
public interface CommandLogReader {

  /** One recorded line: capture/import sequence, raw content, capture timestamp. */
  record TimedLine(long seq, String content, Instant timestamp) {}

  /**
   * The command's imported {@code TRANSCRIPT} lines in sequence order (main session only while a
   * chat runs; sidechains join after the exit sweep).
   */
  List<TimedLine> transcriptLines(String commandId);

  /**
   * {@code OUTPUT} lines with sequence strictly below the bound, in order. For a chat these are
   * only the recorded error-result lines; the bound is a live (ring) sequence, so the result can
   * never overlap a ring replay from that bound.
   */
  List<TimedLine> outputLinesBefore(String commandId, long sequenceExclusive);
}
