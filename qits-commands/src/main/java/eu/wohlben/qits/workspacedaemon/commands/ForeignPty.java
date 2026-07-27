package eu.wohlben.qits.workspacedaemon.commands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link Pty} over the libc pseudo-terminal calls, reached through {@code java.lang.foreign}.
 *
 * <p>Five entry points, no dependency: {@code open("/dev/ptmx")} for the master, {@code grantpt} +
 * {@code unlockpt} to make the slave usable, {@code ptsname_r} to learn its path, and {@code ioctl}
 * with {@code TIOCSWINSZ} to resize. Reads and writes go through {@code read}/{@code write} on the
 * master fd, because the JDK offers no public way to wrap an arbitrary file descriptor in a stream
 * and reaching {@code FileDescriptor.fd} reflectively is exactly the kind of thing a native image
 * makes you register.
 *
 * <p><b>Native image.</b> Every {@link FunctionDescriptor} here is a {@code static final} constant,
 * which is what GraalVM requires to register a downcall stub automatically at build time — no
 * {@code Feature}, no {@code RuntimeForeignAccess} call, nothing in a config file. That is the
 * whole reason the descriptors are hoisted into constants rather than built at the call site.
 *
 * <p><b>Linux only</b>, deliberately. {@link #TIOCSWINSZ} and the {@code struct winsize} layout are
 * this kernel's, and the workspace container is a Debian image — the one place this code ever runs.
 * A different platform should get its own {@link Pty}, not a portability layer here.
 *
 * <p>Not thread-safe for concurrent {@link #close()}: the session owns the lifecycle, and reads and
 * writes come from its own two threads. {@link #resize} may be called from any thread and is
 * guarded by the same closed flag.
 */
public final class ForeignPty implements Pty {

  // --- libc constants (linux) -----------------------------------------------------------------

  private static final int O_RDWR = 0x0002;
  private static final int O_NOCTTY = 0x0100;

  /** {@code ioctl} request that sets the window size and raises SIGWINCH. */
  private static final long TIOCSWINSZ = 0x5414L;

  /** {@code struct winsize} is four unsigned shorts: rows, cols, xpixel, ypixel. */
  private static final int WINSIZE_BYTES = 8;

  private static final int PTSNAME_MAX = 256;

  // --- downcall handles -----------------------------------------------------------------------

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC = LINKER.defaultLookup();

  private static final FunctionDescriptor OPEN_DESC =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor GRANTPT_DESC =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor UNLOCKPT_DESC =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor PTSNAME_R_DESC =
      FunctionDescriptor.of(
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
  private static final FunctionDescriptor IOCTL_DESC =
      FunctionDescriptor.of(
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
  private static final FunctionDescriptor READ_DESC =
      FunctionDescriptor.of(
          ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
  private static final FunctionDescriptor WRITE_DESC =
      FunctionDescriptor.of(
          ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
  private static final FunctionDescriptor CLOSE_DESC =
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

  private static final MethodHandle OPEN = downcall("open", OPEN_DESC);
  private static final MethodHandle GRANTPT = downcall("grantpt", GRANTPT_DESC);
  private static final MethodHandle UNLOCKPT = downcall("unlockpt", UNLOCKPT_DESC);
  private static final MethodHandle PTSNAME_R = downcall("ptsname_r", PTSNAME_R_DESC);

  /**
   * {@code ioctl} is variadic ({@code int ioctl(int, unsigned long, ...)}), so the third argument
   * has to be declared as the first variadic one or the ABI's register assignment is wrong.
   */
  private static final MethodHandle IOCTL =
      LINKER.downcallHandle(
          LIBC.find("ioctl").orElseThrow(() -> new UnsatisfiedLinkError("ioctl")),
          IOCTL_DESC,
          Linker.Option.firstVariadicArg(2));

  private static final MethodHandle READ = downcall("read", READ_DESC);
  private static final MethodHandle WRITE = downcall("write", WRITE_DESC);
  private static final MethodHandle CLOSE = downcall("close", CLOSE_DESC);

  private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(
        LIBC.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError(symbol)), descriptor);
  }

  // --- instance -------------------------------------------------------------------------------

  private final int masterFd;
  private final String slavePath;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final InputStream in = new MasterInput();
  private final OutputStream out = new MasterOutput();

  private ForeignPty(int masterFd, String slavePath) {
    this.masterFd = masterFd;
    this.slavePath = slavePath;
  }

  /**
   * Allocate a PTY and set its initial size.
   *
   * @throws IOException if any of the four setup calls fails — the caller turns that into a failed
   *     launch, which is the same outcome the host had when {@code PtyProcessBuilder.start()} threw
   */
  public static ForeignPty open(int cols, int rows) throws IOException {
    int fd;
    String slave;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment path = arena.allocateFrom("/dev/ptmx");
      // O_NOCTTY: opening the master must never make it this process's controlling terminal.
      fd = (int) OPEN.invokeExact(path, O_RDWR | O_NOCTTY);
      if (fd < 0) {
        throw new IOException("open(/dev/ptmx) failed");
      }
      int granted = (int) GRANTPT.invokeExact(fd);
      if (granted != 0) {
        closeFd(fd);
        throw new IOException("grantpt failed");
      }
      int unlocked = (int) UNLOCKPT.invokeExact(fd);
      if (unlocked != 0) {
        closeFd(fd);
        throw new IOException("unlockpt failed");
      }
      MemorySegment buffer = arena.allocate(PTSNAME_MAX);
      // ptsname_r over ptsname: the latter returns a pointer into a static buffer, which two
      // concurrent launches would race.
      int named = (int) PTSNAME_R.invokeExact(fd, buffer, (long) PTSNAME_MAX);
      if (named != 0) {
        closeFd(fd);
        throw new IOException("ptsname_r failed");
      }
      slave = buffer.getString(0);
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("Could not allocate a pseudo-terminal: " + t, t);
    }
    ForeignPty pty = new ForeignPty(fd, slave);
    pty.resize(cols, rows);
    return pty;
  }

  @Override
  public InputStream in() {
    return in;
  }

  @Override
  public OutputStream out() {
    return out;
  }

  @Override
  public String slavePath() {
    return slavePath;
  }

  @Override
  public void resize(int cols, int rows) {
    if (closed.get() || cols <= 0 || rows <= 0) {
      return;
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment winsize = arena.allocate(WINSIZE_BYTES);
      // struct winsize field order is rows first, then cols; the pixel fields stay zero, which is
      // what every terminal emulator that does not do sixel reports.
      winsize.set(ValueLayout.JAVA_SHORT, 0, (short) rows);
      winsize.set(ValueLayout.JAVA_SHORT, 2, (short) cols);
      winsize.set(ValueLayout.JAVA_SHORT, 4, (short) 0);
      winsize.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
      int unused = (int) IOCTL.invokeExact(masterFd, TIOCSWINSZ, winsize);
    } catch (Throwable ignored) {
      // Best-effort by contract: a resize arriving after the process exited is ordinary.
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      closeFd(masterFd);
    }
  }

  private static void closeFd(int fd) {
    try {
      int unused = (int) CLOSE.invokeExact(fd);
    } catch (Throwable ignored) {
      // Nothing useful to do with a failed close of a descriptor we are discarding.
    }
  }

  /** Reads from the master. EOF (0) and EIO — what a PTY reports once its slave is gone — end it. */
  private final class MasterInput extends InputStream {

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n <= 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      if (closed.get()) {
        return -1;
      }
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment buffer = arena.allocate(length);
        long n = (long) READ.invokeExact(masterFd, buffer, (long) length);
        if (n <= 0) {
          // A PTY master reports EIO rather than EOF when the last slave closes; both mean the
          // conversation is over, and the reader thread treats -1 as the process having gone.
          return -1;
        }
        MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, destination, offset, (int) n);
        return (int) n;
      } catch (Throwable t) {
        throw new IOException("PTY read failed", t);
      }
    }

    @Override
    public void close() {
      ForeignPty.this.close();
    }
  }

  /** Writes to the master — the user's keystrokes reaching the child's terminal. */
  private final class MasterOutput extends OutputStream {

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
      if (closed.get()) {
        throw new IOException("PTY is closed");
      }
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment buffer = arena.allocate(length);
        MemorySegment.copy(source, offset, buffer, ValueLayout.JAVA_BYTE, 0, length);
        int written = 0;
        while (written < length) {
          // A short write is normal once the slave's input buffer fills; loop rather than lose
          // keystrokes.
          long n =
              (long) WRITE.invokeExact(masterFd, buffer.asSlice(written), (long) (length - written));
          if (n <= 0) {
            throw new IOException("PTY write failed");
          }
          written += (int) n;
        }
      } catch (IOException e) {
        throw e;
      } catch (Throwable t) {
        throw new IOException("PTY write failed", t);
      }
    }

    @Override
    public void close() {
      ForeignPty.this.close();
    }
  }
}
