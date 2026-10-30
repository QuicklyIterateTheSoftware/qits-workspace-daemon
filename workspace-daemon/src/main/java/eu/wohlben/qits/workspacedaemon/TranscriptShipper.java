package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.AgentState;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptChunk;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptFiles;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptResync;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * Ships the transcripts of this daemon's own agents home, as {@link TranscriptChunk}s over the
 * control socket (qits-1752).
 *
 * <p>A workspace on a remote execution target writes its transcripts to that target's {@code
 * /claude-home}, which qits-workspaces cannot mount, and which every workspace and desk on the
 * target shares. So the only files read here are the ones this daemon's hooks named: each {@link
 * AgentActivity} carrying a session id and a {@code transcriptPath} registers that session, and a
 * session is the main file plus every {@code subagents/agent-<id>.jsonl} beside it. The projects
 * directory is never listed.
 *
 * <p><b>Delivery is the host's offset check, not state kept here.</b> A cursor per (session, file)
 * starts at 0 when the session is first heard of, and moves only when the {@link Sender} accepted a
 * chunk. The host appends a chunk only when its offset equals the size it holds, and otherwise
 * answers with a {@link TranscriptResync}, which moves the cursor to that size. So a restarted
 * daemon re-converges on its first chunk per file, a frame lost to a dropped socket is re-sent from
 * where the host stopped, and a duplicate costs nothing. To keep that first exchange cheap, a cursor
 * that is new, or whose socket just reconnected, ships one chunk per poll until a poll has passed —
 * time for the host's resync to arrive before the rest of a long file is sent from 0.
 *
 * <p><b>Whole lines only.</b> A chunk ends at a newline, so a line the harness is still writing is
 * held until it is complete. A single line longer than the chunk cap — a tool result carrying an
 * image, say — is split across consecutive chunks at a UTF-8 character boundary, but only once its
 * newline is on disk, so nothing that is still growing is ever sent.
 *
 * <p>A resync can be stale: chunks sent back to back from a wrong cursor each draw one, all naming
 * the same size. Within one connection the host's size never goes down, so a resync no larger than
 * the last one applied to that file is ignored; {@link #connected} forgets them, because a new
 * connection may be a host that lost its archive.
 *
 * <p>An ended session ({@code SessionEnd}, a killed agent, or its harness's exit) is forgotten once
 * everything in it has been handed to the socket and {@link #ENDED_LINGER} has passed — long enough
 * for a resync about its last chunk to arrive — or after {@link #ENDED_RETENTION} regardless.
 *
 * <p>Framework-free: time and the socket arrive as seams, and every method is synchronized, so the
 * poll timer, a hook, a resync and an exit may call it from any thread. The {@link Sender} is
 * called under that lock and must not block.
 */
final class TranscriptShipper {

  private static final Logger LOG = Logger.getLogger(TranscriptShipper.class);

  /**
   * The largest {@code data} a chunk carries, in bytes. Well under every frame limit between here
   * and qits-workspaces (Vert.x's 64 KiB frames are reassembled into messages of up to 6.5 MB), even
   * if JSON escaping were to multiply it several times.
   */
  static final int DEFAULT_MAX_CHUNK_BYTES = 128 * 1024;

  /** The most bytes one poll ships across every session, so a backlog cannot hog the socket. */
  static final long DEFAULT_POLL_BUDGET_BYTES = 2L * 1024 * 1024;

  /** How long an ended session that has shipped everything is kept, for a late resync. */
  static final Duration ENDED_LINGER = Duration.ofMinutes(5);

  /** How long an ended session is kept at all, shipped or not. */
  static final Duration ENDED_RETENTION = Duration.ofHours(24);

  /** The socket half: whether the chunk was handed to an open socket. */
  interface Sender {
    /**
     * @return true when the chunk was written to the socket, false when there is none or it is
     *     backed up — the cursor then stays put and the chunk is sent again later
     */
    boolean send(TranscriptChunk chunk);
  }

  private final Sender sender;
  private final LongSupplier clock;
  private final List<Path> roots;
  private final int maxChunkBytes;
  private final long pollBudgetBytes;

  /** Every known session by id, in the order first heard of. */
  private final Map<String, Session> sessions = new LinkedHashMap<>();

  /**
   * @param roots the harness config directories a reported transcript must sit under — the
   *     credential volume, and the agents' config checkouts; a path anywhere else is ignored
   */
  TranscriptShipper(Sender sender, LongSupplier clock, List<Path> roots) {
    this(sender, clock, roots, DEFAULT_MAX_CHUNK_BYTES, DEFAULT_POLL_BUDGET_BYTES);
  }

  TranscriptShipper(
      Sender sender,
      LongSupplier clock,
      List<Path> roots,
      int maxChunkBytes,
      long pollBudgetBytes) {
    if (maxChunkBytes < 4) {
      throw new IllegalArgumentException("a chunk must hold at least one character");
    }
    this.sender = sender;
    this.clock = clock;
    this.roots = roots.stream().map(root -> root.toAbsolutePath().normalize()).toList();
    this.maxChunkBytes = maxChunkBytes;
    this.pollBudgetBytes = pollBudgetBytes;
  }

  /**
   * Learn from one hook's frame, already tagged with its agent: a session id with a {@code .jsonl}
   * transcript path registers the session (or re-opens it, for a resumed one), an {@code ENDED}
   * marks it ended. A frame with no usable session or path is ignored.
   *
   * @return true when the caller should poll promptly — a turn finished or the session ended, so
   *     there is a fresh tail to send
   */
  synchronized boolean observe(AgentActivity activity) {
    String sessionId = activity.sessionId();
    if (!TranscriptFiles.validSessionId(sessionId)) {
      return false;
    }
    boolean ended = AgentState.ENDED.equals(activity.state());
    Session session = sessions.get(sessionId);
    Path main = transcriptPath(activity.transcriptPath());
    if (main != null && (session == null || !session.main.equals(main))) {
      if (session != null) {
        LOG.infof("Transcript of session %s moved to %s; shipping it from 0", sessionId, main);
      }
      session = new Session(sessionId, main);
      sessions.put(sessionId, session);
    }
    if (session == null) {
      return false;
    }
    if (activity.commandId() != null) {
      session.commandId = activity.commandId();
    }
    if (activity.agentId() != null) {
      session.agentId = activity.agentId();
    }
    if (ended) {
      session.end(clock.getAsLong());
    } else {
      session.endedAt = null;
    }
    return ended || "Stop".equals(activity.hookEvent());
  }

  /** Mark every session of a harness command ended: the harness exited without a {@code SessionEnd}. */
  synchronized void commandEnded(String commandId) {
    if (commandId == null) {
      return;
    }
    long now = clock.getAsLong();
    for (Session session : sessions.values()) {
      if (commandId.equals(session.commandId)) {
        session.end(now);
      }
    }
  }

  /**
   * Move a file's cursor to the size the host holds, clamped to the file as it is here. An unknown
   * session or file, and a stale resync (see the class javadoc), change nothing.
   */
  synchronized void resync(TranscriptResync resync) {
    Session session = sessions.get(resync.sessionId());
    if (session == null) {
      LOG.debugf("Resync for unknown transcript session %s ignored", resync.sessionId());
      return;
    }
    Cursor cursor = session.cursor(resync.file());
    if (cursor == null) {
      LOG.debugf(
          "Resync for transcript %s of session %s, which is not on disk, ignored",
          resync.file(),
          resync.sessionId());
      return;
    }
    if (cursor.lastResync != null && resync.size() <= cursor.lastResync) {
      return; // drawn by a chunk sent before the resync this connection already applied
    }
    long local = sizeOf(cursor.path);
    if (resync.size() > local) {
      LOG.warnf(
          "The host holds %d bytes of transcript %s of session %s, but only %d exist here;"
              + " continuing from the end of the file",
          resync.size(),
          resync.file(),
          resync.sessionId(),
          local);
      cursor.position = local;
      // Not recorded: until the file grows past what the host holds, every chunk draws this resync
      // again, and each must be free to clamp the cursor once more.
      return;
    }
    cursor.position = resync.size();
    cursor.lastResync = resync.size();
  }

  /**
   * The control socket (re)connected: every cursor ships carefully again, and every remembered
   * resync is forgotten, since the host on the other end may be a new one.
   */
  synchronized void connected() {
    for (Session session : sessions.values()) {
      for (Cursor cursor : session.files.values()) {
        cursor.fresh = true;
        cursor.lastResync = null;
      }
    }
  }

  /**
   * Ship what each session's files gained since the last accepted chunk, up to the poll budget,
   * discovering new sidechains on the way; then forget ended sessions that are done. Stops at the
   * first chunk the {@link Sender} refuses.
   */
  synchronized void poll() {
    long now = clock.getAsLong();
    long[] budget = {pollBudgetBytes};
    boolean open = true;
    for (Session session : sessions.values()) {
      if (!open || budget[0] <= 0) {
        break;
      }
      session.discoverSidechains();
      for (Map.Entry<String, Cursor> file : session.files.entrySet()) {
        if (!ship(session, file.getKey(), file.getValue(), budget)) {
          open = false;
          break;
        }
      }
    }
    evict(now);
  }

  /** How many sessions are known, for tests. */
  synchronized int sessionCount() {
    return sessions.size();
  }

  /**
   * Ship one file from its cursor. A fresh cursor ships one chunk and loses its freshness.
   *
   * @return false when the sender refused a chunk
   */
  private boolean ship(Session session, String file, Cursor cursor, long[] budget) {
    boolean once = cursor.fresh;
    cursor.fresh = false;
    long size = sizeOf(cursor.path);
    if (cursor.position > size) {
      if (!cursor.shrunkLogged) {
        LOG.warnf(
            "Transcript %s of session %s shrank below %d bytes already shipped; waiting for it to"
                + " grow back",
            file,
            session.sessionId,
            cursor.position);
        cursor.shrunkLogged = true;
      }
      return true;
    }
    while (cursor.position < size && budget[0] > 0) {
      Slice slice;
      try {
        slice = nextSlice(cursor.path, cursor.position, size);
      } catch (IOException e) {
        LOG.debugf("Could not read transcript %s: %s", cursor.path, e.getMessage());
        return true;
      }
      if (slice == null) {
        return true; // only a line still being written is left
      }
      TranscriptChunk chunk =
          new TranscriptChunk(
              session.agentId,
              session.commandId,
              session.sessionId,
              file,
              cursor.position,
              decode(slice.bytes, cursor));
      if (!sender.send(chunk)) {
        return false;
      }
      cursor.position += slice.bytes.length;
      budget[0] -= slice.bytes.length;
      if (once) {
        return true;
      }
    }
    return true;
  }

  /**
   * The next slice of whole lines from {@code position}: up to {@link #maxChunkBytes}, ending at
   * the last newline in that window. With no newline in a full window the line is longer than the
   * cap, and its first piece is returned — cut at a character boundary — only if its newline is
   * already on disk. Null when nothing complete is there yet.
   */
  private Slice nextSlice(Path path, long position, long size) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      int length = (int) Math.min(maxChunkBytes + 1L, size - position);
      ByteBuffer buffer = ByteBuffer.allocate(length);
      while (buffer.hasRemaining()) {
        if (channel.read(buffer, position + buffer.position()) < 0) {
          break;
        }
      }
      byte[] bytes = buffer.array();
      int read = buffer.position();
      int window = Math.min(read, maxChunkBytes);
      for (int i = window - 1; i >= 0; i--) {
        if (bytes[i] == '\n') {
          return new Slice(Arrays.copyOf(bytes, i + 1));
        }
      }
      if (read <= maxChunkBytes || !newlineFrom(channel, position + maxChunkBytes, size)) {
        return null;
      }
      int cut = maxChunkBytes;
      while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
        cut--; // never start the next piece on a UTF-8 continuation byte
      }
      return new Slice(Arrays.copyOf(bytes, cut == 0 ? maxChunkBytes : cut));
    }
  }

  /** Whether a newline lies anywhere in {@code [from, size)}. */
  private static boolean newlineFrom(FileChannel channel, long from, long size) throws IOException {
    ByteBuffer block = ByteBuffer.allocate(64 * 1024);
    for (long at = from; at < size; ) {
      block.clear();
      int n = channel.read(block, at);
      if (n <= 0) {
        return false;
      }
      byte[] bytes = block.array();
      for (int i = 0; i < n; i++) {
        if (bytes[i] == '\n') {
          return true;
        }
      }
      at += n;
    }
    return false;
  }

  /**
   * The slice as text. The harness writes UTF-8; should a slice not be, it is sent with replacement
   * characters, logged once per file, and the host's size then disagrees with this cursor — which
   * the next resync settles.
   */
  private static String decode(byte[] bytes, Cursor cursor) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      if (!cursor.malformedLogged) {
        LOG.warnf("Transcript %s is not valid UTF-8; shipping it with replacements", cursor.path);
        cursor.malformedLogged = true;
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private void evict(long now) {
    Iterator<Session> it = sessions.values().iterator();
    while (it.hasNext()) {
      Session session = it.next();
      if (session.endedAt == null) {
        continue;
      }
      long ended = now - session.endedAt;
      if (ended >= ENDED_RETENTION.toMillis()) {
        if (!session.drained()) {
          LOG.warnf(
              "Forgetting ended transcript session %s with bytes still unshipped", session.sessionId);
        }
        it.remove();
      } else if (ended >= ENDED_LINGER.toMillis() && session.drained()) {
        it.remove();
      }
    }
  }

  /**
   * A hook's {@code transcriptPath} as a main transcript: absolute, {@code .jsonl}, and under one
   * of {@link #roots} once normalized — or null. The hook webhook is unauthenticated, so a path it
   * reports is checked before anything here reads it and sends it off the machine.
   */
  private Path transcriptPath(String reported) {
    if (reported == null || reported.isBlank()) {
      return null;
    }
    try {
      Path path = Path.of(reported).normalize();
      if (!path.isAbsolute() || !path.getFileName().toString().endsWith(".jsonl")) {
        return null;
      }
      for (Path root : roots) {
        if (path.startsWith(root) && !path.equals(root)) {
          return path;
        }
      }
      LOG.debugf("Transcript path %s is outside the harness config directories; not shipped", path);
      return null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static long sizeOf(Path path) {
    try {
      return Files.size(path);
    } catch (NoSuchFileException e) {
      return 0;
    } catch (IOException e) {
      LOG.debugf("Could not stat transcript %s: %s", path, e.getMessage());
      return 0;
    }
  }

  private record Slice(byte[] bytes) {}

  /** One file's send cursor. */
  private static final class Cursor {
    final Path path;

    /** The byte offset the next chunk starts at. */
    long position;

    /** Ships one chunk at the next poll and no more: new, or its socket just reconnected. */
    boolean fresh = true;

    /** The size the last resync applied this connection carried, or null. */
    Long lastResync;

    boolean shrunkLogged;
    boolean malformedLogged;

    Cursor(Path path) {
      this.path = path;
    }
  }

  /** One harness session: its main transcript and the sidechains beside it. */
  private static final class Session {
    final String sessionId;
    final Path main;

    /** {@code <main without .jsonl>/subagents}. */
    final Path subagents;

    /** Cursors by wire {@code file}, the main one first. */
    final Map<String, Cursor> files = new LinkedHashMap<>();

    String agentId;
    String commandId;

    /** When it ended, by the shipper's clock, or null while it runs. */
    Long endedAt;

    Session(String sessionId, Path main) {
      this.sessionId = sessionId;
      this.main = main;
      String name = main.getFileName().toString();
      this.subagents =
          main.resolveSibling(name.substring(0, name.length() - ".jsonl".length()))
              .resolve(TranscriptFiles.SUBAGENTS);
      files.put(TranscriptFiles.MAIN, new Cursor(main));
    }

    void end(long now) {
      if (endedAt == null) {
        endedAt = now;
      }
    }

    /**
     * The cursor for a wire {@code file}, adding a sidechain's when its file exists here and is not
     * known yet; null when there is no such file.
     */
    Cursor cursor(String file) {
      Cursor known = files.get(file);
      if (known != null) {
        return known;
      }
      if (!file.startsWith(TranscriptFiles.SUBAGENTS + "/")) {
        return null;
      }
      Path path = subagents.resolve(file.substring(TranscriptFiles.SUBAGENTS.length() + 1));
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      Cursor added = new Cursor(path);
      files.put(file, added);
      return added;
    }

    /**
     * Add a cursor for every {@code agent-<id>.jsonl} regular file in the subagents directory not
     * known yet, in name order. Anything else in it — a symlink, a metadata file — is left alone.
     */
    void discoverSidechains() {
      if (!Files.isDirectory(subagents, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      TreeSet<String> found = new TreeSet<>();
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(subagents, "agent-*.jsonl")) {
        for (Path entry : entries) {
          String file = TranscriptFiles.sidechain(entry.getFileName().toString());
          if (file != null
              && !files.containsKey(file)
              && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            found.add(file);
          }
        }
      } catch (IOException e) {
        LOG.debugf("Could not list %s: %s", subagents, e.getMessage());
        return;
      }
      for (String file : found) {
        cursor(file);
      }
    }

    /** Whether every known file's cursor is at its end of file. */
    boolean drained() {
      for (Cursor cursor : files.values()) {
        if (cursor.position < sizeOf(cursor.path)) {
          return false;
        }
      }
      return true;
    }
  }
}
