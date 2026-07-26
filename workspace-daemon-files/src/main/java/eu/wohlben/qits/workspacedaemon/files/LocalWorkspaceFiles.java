package eu.wohlben.qits.workspacedaemon.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link WorkspaceFiles} over the local filesystem — the in-container replacement for the host's
 * {@code docker exec find/cat/realpath} implementation. Everything the host paid a process spawn
 * and an output parse for is a direct {@code java.nio} call here, which is the entire reason the
 * capability moved into the daemon.
 *
 * <p>The root is injected rather than hardcoded to {@code /workspace}: the daemon wires the real
 * checkout, and the tests point it at a temp directory. Relative paths are resolved against it
 * <em>without</em> lexical normalization, exactly like the host's {@code find ./<path>} with
 * workdir {@code /workspace} — silently collapsing a {@code ..} here would hide the escape the
 * caller is contractually required to reject, and quietly change which path was actually read.
 *
 * <p>Symlinks are never followed for typing: {@link #stat} and {@link #list} use {@link
 * LinkOption#NOFOLLOW_LINKS} so a link is reported as a link and a linked directory is never
 * descended into. Following is confined to {@link #resolvesInsideRoot} (where it is the point) and
 * {@link #read} (where the caller has already run that guard).
 */
public final class LocalWorkspaceFiles implements WorkspaceFiles {

  private final Path root;

  public LocalWorkspaceFiles(Path root) {
    this.root = root;
  }

  /** The workspace root every relative path in this module is resolved against. */
  public Path root() {
    return root;
  }

  /**
   * Forks {@code git <args>} in the root with stderr merged into stdout. Merged on purpose: git
   * writes its diagnostics to stderr, and a caller that only sees "exit 128" with an empty message
   * cannot tell a missing repository from a broken index. A non-zero exit throws rather than
   * returning partial output — every caller here treats git's answer as authoritative, so a silent
   * empty listing would read as "the workspace has no files".
   */
  @Override
  public String git(String... args) {
    List<String> argv = new ArrayList<>(args.length + 1);
    argv.add("git");
    Collections.addAll(argv, args);
    try {
      Process process =
          new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true).start();
      process.getOutputStream().close(); // none of these subcommands read stdin
      byte[] out;
      // Drain before waiting: a full pipe blocks git forever if we wait first.
      try (InputStream in = process.getInputStream()) {
        out = in.readAllBytes();
      }
      int exitCode = process.waitFor();
      String output = new String(out, StandardCharsets.UTF_8);
      if (exitCode != 0) {
        throw WorkspaceFilesException.internal(
            "git failed [" + exitCode + "]: " + String.join(" ", argv) + "\n" + output);
      }
      return output;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw WorkspaceFilesException.internal("Interrupted running: " + String.join(" ", argv), e);
    } catch (IOException e) {
      throw WorkspaceFilesException.internal(
          "Failed to run: " + String.join(" ", argv) + ": " + e.getMessage(), e);
    }
  }

  @Override
  public Entry stat(String path) {
    BasicFileAttributes attrs;
    try {
      attrs =
          Files.readAttributes(
              root.resolve(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      // The host's `find` exited non-zero for anything it could not stat and the caller turned that
      // into a 404; an unreadable parent directory is indistinguishable from a missing entry here
      // too, and deliberately so — probing the difference is a directory-existence oracle.
      return new Entry(path, EntryType.MISSING, 0, 0);
    }
    EntryType type = typeOf(attrs);
    return new Entry(path, type, type == EntryType.FILE ? attrs.size() : 0, 0);
  }

  @Override
  public List<Entry> list(String dir) {
    Path target = root.resolve(dir);
    List<Entry> entries = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(target)) {
      for (Path child : children) {
        BasicFileAttributes attrs;
        try {
          attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException vanished) {
          // The tree is live (an agent may be writing in it); an entry that disappeared between the
          // directory read and the stat is simply not part of this listing.
          continue;
        }
        EntryType type = typeOf(attrs);
        String relative = relative(child);
        // Only real directories get a child count — a symlinked one is reported as a link and never
        // descended into, so counting through it would both cost a walk and leak what's outside.
        int childCount = type == EntryType.DIRECTORY ? countChildren(child) : 0;
        entries.add(
            new Entry(relative, type, type == EntryType.FILE ? attrs.size() : 0, childCount));
      }
    } catch (IOException e) {
      throw WorkspaceFilesException.internal("Failed to list directory: " + dir, e);
    }
    return entries;
  }

  @Override
  public int childCount(String dir) {
    return countChildren(root.resolve(dir));
  }

  /**
   * Canonicalizes the root and the target — every intermediate and final symlink followed, both
   * required to exist — and confirms the target stays under the root. The root is resolved too
   * rather than compared literally, because it is itself reachable through symlinked ancestors
   * (a temp dir under a linked {@code /tmp}, a bind mount), and comparing a resolved target against
   * an unresolved root would then reject every legitimate path.
   *
   * <p>{@link Path#startsWith} is component-wise, so a sibling named {@code /workspace-evil} cannot
   * pass as a prefix match the way a raw string comparison would let it.
   */
  @Override
  public boolean resolvesInsideRoot(String path) {
    try {
      Path realRoot = root.toRealPath();
      Path realTarget = root.resolve(path).toRealPath();
      return realTarget.startsWith(realRoot);
    } catch (IOException e) {
      return false; // missing path, or a broken/looping symlink
    }
  }

  /**
   * Reads the file exactly, as bytes, so binary content and trailing newlines survive — the caller
   * decodes and sniffs. This is the one read that <em>does</em> follow symlinks (as {@code cat}
   * did), which is safe only because {@link #resolvesInsideRoot} has already run; the size cap is
   * likewise the caller's, from a prior {@link #stat}.
   */
  @Override
  public byte[] read(String path) {
    try {
      return Files.readAllBytes(root.resolve(path));
    } catch (IOException e) {
      throw WorkspaceFilesException.internal("Failed to read file: " + path, e);
    }
  }

  /** The workspace-root-relative form of an absolute path, with {@code /} separators. */
  private String relative(Path absolute) {
    return root.relativize(absolute).toString().replace('\\', '/');
  }

  /**
   * One level of {@code readdir}, never a recursive walk — the count exists precisely so a lazy
   * stub can be labelled without opening the directory it stands for. An unreadable directory
   * counts as zero, matching the host, where {@code find}'s non-zero exit produced the same.
   */
  private static int countChildren(Path dir) {
    int count = 0;
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
      for (Path ignored : children) {
        count++;
      }
    } catch (IOException e) {
      return 0;
    }
    return count;
  }

  /**
   * Maps {@code lstat} attributes onto the seam's types. The symlink check comes first because a
   * link is <em>also</em> reported as "other" — order here is what keeps a linked directory from
   * being typed as a directory.
   */
  private static EntryType typeOf(BasicFileAttributes attrs) {
    if (attrs.isSymbolicLink()) {
      return EntryType.SYMLINK;
    }
    if (attrs.isDirectory()) {
      return EntryType.DIRECTORY;
    }
    if (attrs.isRegularFile()) {
      return EntryType.FILE;
    }
    return EntryType.OTHER; // fifo, socket, device — surfaced, never read
  }
}
