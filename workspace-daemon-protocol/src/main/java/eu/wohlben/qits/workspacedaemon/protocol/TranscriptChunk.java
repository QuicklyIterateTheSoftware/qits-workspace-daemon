package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: bytes appended to one of an agent session's transcript files
 * (capability 13, qits-1752).
 *
 * <p>A workspace on a remote execution target writes its transcripts to that target's {@code
 * /claude-home}, a volume qits-workspaces cannot mount — and one every workspace and desk on the
 * target shares. So the daemon ships the sessions its own agents' hooks reported, and the host
 * archives them. Nothing else on the volume is ever read.
 *
 * <p>The stream is addressed by {@code (sessionId, file, offset)}: {@code offset} is the byte
 * offset in the source file where {@code data} starts, so the host appends a chunk only when it
 * equals the size it holds and answers anything else with a {@link TranscriptResync} carrying that
 * size. That one rule is the whole delivery guarantee — a daemon restart starts its cursors at 0, a
 * frame lost to a reconnect leaves a gap, and both are corrected by the resync rather than by any
 * state the daemon keeps. A duplicate is harmless for the same reason.
 *
 * <p>{@code data} is the UTF-8 text of whole lines: it always ends with a newline, and a trailing
 * line the harness is still writing is never sent. The one exception is a single line longer than
 * the daemon's chunk cap, which goes out as consecutive chunks split at a character boundary, the
 * last ending with its newline; contiguous offsets make that indistinguishable to an appender.
 *
 * <p>Unsolicited and not correlated. A backend older than 13 does not know the tag and drops the
 * frame ({@code DaemonControlSocket} catches an undecodable frame and logs it); nothing else about
 * the socket changes, and it never sends a {@link TranscriptResync}, so the daemon simply keeps
 * shipping from its own cursors.
 *
 * @param agentId the agent whose harness wrote the session, or null for a command no agent of this
 *     daemon owns (the sign-in terminal); {@code commandId} still names it
 * @param commandId the harness command whose hook reported the session
 * @param sessionId the harness session id; a single safe path segment ({@link
 *     TranscriptFiles#validSessionId})
 * @param file which of the session's files: {@link TranscriptFiles#MAIN} for {@code
 *     <sessionId>.jsonl}, or {@code subagents/agent-<id>.jsonl} for a sidechain ({@link
 *     TranscriptFiles#validFile})
 * @param offset the byte offset in the source file where {@code data} starts; never negative
 * @param data the appended text, UTF-8 on disk; never null
 */
public record TranscriptChunk(
    String agentId, String commandId, String sessionId, String file, long offset, String data)
    implements DaemonMessage {

  /**
   * Refuses a frame whose address could name anything but one of the session's own files: the host
   * turns {@code sessionId} and {@code file} into a path, so they are checked on both sides of the
   * wire, and a decoder that meets a bad one drops the frame as undecodable.
   */
  public TranscriptChunk {
    TranscriptFiles.requireAddress(sessionId, file);
    if (offset < 0) {
      throw new IllegalArgumentException("transcript chunk offset is negative: " + offset);
    }
    if (data == null) {
      throw new IllegalArgumentException("transcript chunk has no data");
    }
  }
}
