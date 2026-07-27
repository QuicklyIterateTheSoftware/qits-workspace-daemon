package eu.wohlben.qits.workspacedaemon.commands;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * A pseudo-terminal: the master side this process reads and writes, plus the slave device path a
 * child is given as its controlling terminal.
 *
 * <p>The seam exists because of how the PTY got here. On the host, an interactive command was a
 * pty4j {@code PtyProcess} whose outer PTY drove a {@code docker exec -it} client — the PTY was
 * needed to make a <em>remote</em> TTY behave. In the container the process is a direct child, so
 * the PTY is only needed for what a PTY is actually for: line discipline, terminal encoding,
 * full-screen applications and {@code SIGWINCH} on resize. pty4j itself could not come along —
 * it is JNA plus per-platform native libraries extracted at runtime, and this daemon compiles to a
 * GraalVM native image whose design point is having nothing to register — so {@link ForeignPty}
 * calls the four libc entry points directly through {@code java.lang.foreign}.
 *
 * <p>Kept as an interface so the session can be tested against a plain pipe pair without a real
 * terminal, and so a platform that needs a different implementation has somewhere to put it.
 */
public interface Pty extends AutoCloseable {

  /** Output produced by whatever holds the slave end — a process's merged stdout and stderr. */
  InputStream in();

  /** Input delivered to the slave end — what the user typed. */
  OutputStream out();

  /**
   * The slave device path ({@code /dev/pts/N}), for handing to a child as its stdio. Valid until
   * {@link #close()}.
   */
  String slavePath();

  /**
   * Set the terminal window size, which makes the kernel deliver {@code SIGWINCH} to the foreground
   * process group. Best-effort: a closed or invalid PTY is ignored rather than throwing, because a
   * resize racing a process exit is ordinary and must not surface as an error to the browser.
   */
  void resize(int cols, int rows);

  @Override
  void close();
}
