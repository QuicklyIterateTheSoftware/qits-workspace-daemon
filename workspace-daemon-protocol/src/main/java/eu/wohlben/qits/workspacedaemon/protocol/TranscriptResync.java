package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * qits → {@code workspace-daemon}: the host holds {@code size} bytes of a transcript file, which
 * is not where the last {@link TranscriptChunk} for it started (capability 13, qits-1752).
 *
 * <p>The daemon moves its send cursor for that file to {@code size} — back, re-sending what the
 * host lost, or forward, skipping what it already has — and carries on from there. A size beyond
 * the file as the daemon sees it means the host holds more than exists here; the daemon logs it
 * and continues from its own end of file.
 *
 * <p>This is how a restarted daemon (cursors at 0) and a restarted or reconnected host (holding
 * whatever it archived) meet without duplicating or losing a line. An older daemon does not know
 * the tag and drops the frame; the host's archive then only grows when a chunk happens to start
 * where it ends.
 *
 * @param sessionId the session the file belongs to
 * @param file {@link TranscriptFiles#MAIN} or {@code subagents/agent-<id>.jsonl}
 * @param size the bytes the host holds for that file; never negative
 */
public record TranscriptResync(String sessionId, String file, long size) implements DaemonMessage {

  /** Refuses an address that is not one of a session's files, as {@link TranscriptChunk} does. */
  public TranscriptResync {
    TranscriptFiles.requireAddress(sessionId, file);
    if (size < 0) {
      throw new IllegalArgumentException("transcript resync size is negative: " + size);
    }
  }
}
