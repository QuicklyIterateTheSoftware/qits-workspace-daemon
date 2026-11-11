package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.protocol.TranscriptFiles;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * A Claude session's files on the credential volume, and putting back the ones the platform holds
 * (qits-2088).
 *
 * <p>Claude keeps a session at {@code <mount>/.claude/projects/<escaped cwd>/<session>.jsonl},
 * the working directory escaped {@code [^A-Za-z0-9]} → {@code -}, and its sidechains at {@code
 * <session>/subagents/agent-<id>.jsonl} beside it. {@link #mainFile} is the one path a resume is
 * decided on ({@link AgentRuntime#sessionFilesExist}); {@link #write} restores into exactly that
 * layout, addressed by the transcript grammar the control socket already uses ({@link
 * TranscriptFiles}: {@code main}, or {@code subagents/agent-<id>.jsonl}), so nothing from the wire
 * becomes a path unvalidated.
 *
 * <p><b>A file appears whole or not at all.</b> The chunks of one file are appended to a staging
 * file beside it ({@code <name>.restoring}, which Claude never lists), at the offset the host
 * names — 0 starts it over — and moved into place by the chunk marked {@code last}. So an upload
 * cut short leaves no transcript for Claude to resume half of, and a session whose main file is
 * still missing is asked for again on the next start rather than resumed. A file already in place
 * is the harness's and is never overwritten.
 *
 * <p><b>Ownership.</b> The daemon runs as the harness's own user — it spawns every harness as its
 * child, with no user switch — so a file written here is owned exactly as one Claude writes, with
 * the process umask, like {@code agent.json}. Links are not followed: the staging file is opened
 * {@code NOFOLLOW_LINKS} and a link at the target counts as a file in place.
 *
 * <p>Framework-free; {@link AgentRuntime} serializes every call.
 */
final class SessionFiles {

  /** Beside the file it becomes, until its last chunk. Not {@code .jsonl}: Claude never lists it. */
  static final String STAGING_SUFFIX = ".restoring";

  private static final String JSONL = ".jsonl";

  private final String claudeMount;

  SessionFiles(String claudeMount) {
    this.claudeMount = claudeMount;
  }

  /**
   * {@code <mount>/.claude/projects/<escaped directory>/<session>.jsonl}; empty when no credential
   * volume is configured or {@code session} is not a plain name.
   */
  Optional<Path> mainFile(Path directory, String session) {
    if (claudeMount == null
        || claudeMount.isBlank()
        || session == null
        || !session.matches("[A-Za-z0-9._-]+")) {
      return Optional.empty();
    }
    String escaped = directory.toString().replaceAll("[^A-Za-z0-9]", "-");
    return Optional.of(
        Path.of(claudeMount, ".claude", "projects", escaped, session + JSONL));
  }

  /** What a {@link #write} did: the bytes held for the file, and whether it is now in place. */
  record Written(long size, boolean restored) {}

  /** The staging file does not end where the chunk starts: send from {@link #size()}. */
  static final class OffsetMismatchException extends RuntimeException {
    private final long size;

    OffsetMismatchException(long size) {
      super("The restore holds " + size + " bytes of that file; send from there");
      this.size = size;
    }

    long size() {
      return size;
    }
  }

  /**
   * Append one chunk of a session file the host holds, and move the file into place on its {@code
   * last} chunk.
   *
   * @param file {@code main} or {@code subagents/agent-<id>.jsonl}
   * @return the staged size, or the size of a file already in place with {@code restored} false
   * @throws IllegalArgumentException an address outside the grammar, a negative offset, no data,
   *     or no credential volume
   * @throws OffsetMismatchException {@code offset} is neither 0 nor the bytes staged so far
   */
  Written write(
      Path directory, String session, String file, long offset, String data, boolean last)
      throws IOException {
    if (!TranscriptFiles.validSessionId(session) || !TranscriptFiles.validFile(file)) {
      throw new IllegalArgumentException("Not a session file: " + session + " " + file);
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
    if (data == null) {
      throw new IllegalArgumentException("data is required");
    }
    Path main =
        mainFile(directory, session)
            .orElseThrow(() -> new IllegalArgumentException("No credential volume to restore to"));
    Path target =
        TranscriptFiles.MAIN.equals(file)
            ? main
            : main.resolveSibling(session)
                .resolve(TranscriptFiles.SUBAGENTS)
                .resolve(file.substring(TranscriptFiles.SUBAGENTS.length() + 1));
    Path staging = target.resolveSibling(target.getFileName() + STAGING_SUFFIX);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      Files.deleteIfExists(staging);
      return new Written(
          Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? Files.size(target) : 0L, false);
    }
    long held =
        Files.isRegularFile(staging, LinkOption.NOFOLLOW_LINKS) ? Files.size(staging) : 0L;
    if (offset != 0 && offset != held) {
      throw new OffsetMismatchException(held);
    }
    Files.createDirectories(target.getParent());
    OpenOption[] options;
    if (offset == 0) {
      Files.deleteIfExists(staging);
      options =
          new OpenOption[] {
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
          };
      held = 0L;
    } else {
      options =
          new OpenOption[] {
            StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS
          };
    }
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    try (OutputStream out = Files.newOutputStream(staging, options)) {
      out.write(bytes);
    }
    long size = held + bytes.length;
    if (!last) {
      return new Written(size, false);
    }
    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
    return new Written(size, true);
  }
}
