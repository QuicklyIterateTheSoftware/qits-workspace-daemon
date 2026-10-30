package eu.wohlben.qits.workspacedaemon.protocol;

import java.util.regex.Pattern;

/**
 * The address grammar {@link TranscriptChunk} and {@link TranscriptResync} share: which session,
 * and which of its files. Both halves become a path on the host, so the grammar is closed — no
 * separator, no {@code ..}, no second form — and checked in the records themselves, so neither end
 * of the wire can build a frame that escapes the session's directory.
 */
public final class TranscriptFiles {

  /** The session's own transcript, {@code <sessionId>.jsonl}. */
  public static final String MAIN = "main";

  /** The directory a session's sidechains live in, beside {@code <sessionId>.jsonl}. */
  public static final String SUBAGENTS = "subagents";

  /** A sidechain: {@code subagents/agent-<id>.jsonl}, the id word characters and dashes only. */
  private static final Pattern SIDECHAIN = Pattern.compile("subagents/agent-[A-Za-z0-9_-]+\\.jsonl");

  /** A session id: one path segment that is not a dot-name — a UUID in practice. */
  private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  private TranscriptFiles() {}

  /** Whether {@code file} is {@link #MAIN} or exactly {@code subagents/agent-<id>.jsonl}. */
  public static boolean validFile(String file) {
    return file != null && (MAIN.equals(file) || SIDECHAIN.matcher(file).matches());
  }

  /** Whether {@code sessionId} is a single safe path segment with no {@code ..} in it. */
  public static boolean validSessionId(String sessionId) {
    return sessionId != null
        && SESSION_ID.matcher(sessionId).matches()
        && !sessionId.contains("..");
  }

  /** The {@code file} value of the sidechain whose file name is {@code fileName}, or null. */
  public static String sidechain(String fileName) {
    String file = SUBAGENTS + "/" + fileName;
    return SIDECHAIN.matcher(file).matches() ? file : null;
  }

  static void requireAddress(String sessionId, String file) {
    if (!validSessionId(sessionId)) {
      throw new IllegalArgumentException("not a transcript session id: " + sessionId);
    }
    if (!validFile(file)) {
      throw new IllegalArgumentException("not a transcript file: " + file);
    }
  }
}
