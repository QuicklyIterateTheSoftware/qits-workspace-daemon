package eu.wohlben.qits.workspacedaemon.commands;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live registry of running command processes, keyed by durable command id. Owns each {@link
 * CommandSession} (its terminal, reader thread, scrollback buffer and attached sinks) independent of
 * any client connection, so processes survive disconnects and can be re-attached. It deliberately
 * performs <em>no</em> automatic cleanup — a process ends only by exiting itself or via {@link
 * #terminate}.
 *
 * <p>It is storage-agnostic: when a process ends, the session invokes the {@link
 * CommandExitListener} supplied at spawn time, which {@code CommandService} wires to the status
 * update. The registry never touches the store.
 *
 * <h2>What the move deleted</h2>
 *
 * <p>Most of this class used to be about not being where the process is. Every spawn went through a
 * {@code dockerExec} argv builder that composed the runtime's exec prefix, the container-side
 * {@code -e} environment and {@code -w /workspace}; the interactive path needed pty4j to drive
 * {@code docker exec -it}, and the pipe path needed {@code setsid -w} specifically so {@code docker
 * exec -i} would not tear the pipes down under a detached process. None of that survives: the
 * daemon is in the container, {@code /workspace} is its own working directory, and the environment
 * is just the child's environment.
 *
 * <p>{@code setsid} stays, for the one reason that was never about docker — it makes the launched
 * shell a process-group leader, so {@code kill -- -pgid} reaches a compound script's children and
 * not merely the shell. {@code --ctty} is now correct and necessary where it previously would have
 * failed with EPERM: on the host, {@code docker exec -it} had already made the shell a session
 * leader owning the inner TTY, so re-stealing the controlling terminal was refused; here nothing
 * has claimed the terminal yet and the child must claim it, or {@code test -t 1} fails and every
 * full-screen TUI falls back to line mode.
 */
public final class CommandRegistry {

  /** Where a launched script records its process-group id, by command id. */
  private static final String PID_FILE_PREFIX = "/tmp/qits-cmd-";

  private static final int INITIAL_COLUMNS = 80;
  private static final int INITIAL_ROWS = 24;

  private final Map<String, CommandSession> sessions = new ConcurrentHashMap<>();

  /**
   * Line-oriented chat sessions on plain pipes, keyed the same way as {@link #sessions}.
   */
  private final Map<String, ChatSession> chats = new ConcurrentHashMap<>();

  /** The checkout every command runs in — the daemon's {@code /workspace}. */
  private final Path workspaceRoot;

  /** Grace period before a graceful stop escalates to SIGKILL. */
  private final long graceMillis;

  public CommandRegistry(Path workspaceRoot, long graceMillis) {
    this.workspaceRoot = workspaceRoot;
    this.graceMillis = graceMillis;
  }

  /** The pid file a command's launch wrapper writes its process-group id to. */
  static Path pidFile(String commandId) {
    return Path.of(PID_FILE_PREFIX + commandId + ".pid");
  }

  /** Spawn a process for {@code commandId} with optional sinks attached before output starts. */
  public void spawn(
      String commandId,
      String script,
      Map<String, String> environment,
      CommandExitListener exitListener,
      CommandLogWriter logWriter,
      CommandOutputSink... initialSinks) {
    startSession(commandId, script, environment, exitListener, logWriter, initialSinks);
  }

  /**
   * Spawn a coding-agent chat process (kind {@code CHAT}) on plain pipes — not a terminal, which
   * would echo input and corrupt the line-delimited JSON. The {@code protocolFactory} chooses the
   * transport (null ⇒ Claude stream-json pass-through; Kimi supplies its ACP client), which
   * normalizes to the one envelope the session rings and broadcasts. Registry-tracked and
   * re-attachable exactly like {@link #spawn}.
   */
  public void spawnChat(
      String commandId,
      String script,
      Map<String, String> environment,
      ChatProtocolFactory protocolFactory,
      CommandExitListener exitListener,
      CommandLogWriter logWriter,
      CommandLogReader logReader,
      CommandOutputSink... initialSinks) {
    Process process;
    try {
      ProcessBuilder builder = shell(script, commandId, environment, false);
      // Keep stderr off the JSON stdout stream (it would corrupt parsing); let it go to the
      // daemon's own stderr rather than merging it into the conversation.
      builder.redirectError(ProcessBuilder.Redirect.INHERIT);
      process = builder.start();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to start chat " + commandId, e);
    }
    // A null factory is the default stream-json pass-through; Kimi supplies its ACP client.
    ChatProtocol protocol =
        protocolFactory != null
            ? protocolFactory.create(process)
            : new StreamJsonChatProtocol(process, commandId);
    ChatSession session =
        new ChatSession(
            commandId,
            process,
            graceMillis,
            protocol,
            exitListener,
            () -> chats.remove(commandId),
            logWriter,
            logReader);
    for (CommandOutputSink sink : initialSinks) {
      session.addInitialSink(sink);
    }
    chats.put(commandId, session);
    session.startReader();
  }

  /** Send a user turn to a running chat command; false if it is not a running chat. */
  public boolean chatSend(String commandId, String text) {
    ChatSession session = chats.get(commandId);
    if (session == null) {
      return false;
    }
    session.sendUser(text);
    return true;
  }

  private CommandSession startSession(
      String commandId,
      String script,
      Map<String, String> environment,
      CommandExitListener exitListener,
      CommandLogWriter logWriter,
      CommandOutputSink... initialSinks) {
    Pty pty;
    try {
      pty = ForeignPty.open(INITIAL_COLUMNS, INITIAL_ROWS);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to allocate a terminal for command " + commandId, e);
    }
    Process process;
    try {
      ProcessBuilder builder = shell(script, commandId, environment, true);
      // The child's stdio is the terminal's slave device. Opening it by path is what makes the
      // process see a real TTY; `setsid --ctty` in the argv is what makes it that process's
      // *controlling* terminal, which is what SIGWINCH and job control need.
      java.io.File slave = new java.io.File(pty.slavePath());
      builder.redirectInput(slave);
      builder.redirectOutput(slave);
      builder.redirectError(slave);
      process = builder.start();
    } catch (IOException e) {
      pty.close();
      throw new UncheckedIOException("Failed to start command " + commandId, e);
    }

    CommandSession session =
        new CommandSession(
            commandId,
            process,
            pty,
            graceMillis,
            exitListener,
            () -> sessions.remove(commandId),
            logWriter);
    for (CommandOutputSink sink : initialSinks) {
      session.addInitialSink(sink);
    }
    sessions.put(commandId, session);
    session.startReader();
    return session;
  }

  /**
   * The shell invocation that runs {@code script} in the checkout: {@code setsid} so the shell is a
   * process-group leader {@code kill -- -pgid} can address, then a wrapper that records that pgid
   * to a pid file before running the script.
   *
   * <p>The script runs as the shell body, not {@code exec}'d: {@code $$} (the login shell) is the
   * group leader that {@code kill -- -pgid} reaches along with its children. It is deliberately not
   * {@code exec}'d — a compound script ({@code while …; do …; done}) is not a simple command {@code
   * exec} can take — but a script that wants a single leader process can still {@code exec} its own
   * target, since the shell keeps the same pid and the recorded pgid stays valid.
   *
   * @param controllingTerminal whether the shell should claim its stdio as a controlling terminal.
   *     True for interactive commands, whose stdio is a PTY slave; false for chats, whose stdio is
   *     a pipe and for which {@code --ctty} would be meaningless.
   */
  private ProcessBuilder shell(
      String script, String commandId, Map<String, String> environment, boolean controllingTerminal) {
    List<String> argv = new ArrayList<>();
    argv.add("setsid");
    if (controllingTerminal) {
      argv.add("--ctty");
    } else {
      // -w (wait) rather than double-forking and exiting: the parent must stay alive holding the
      // pipes, or a chat reads EOF before its first turn arrives and exits without ever answering.
      argv.add("-w");
    }
    argv.add("bash");
    argv.add("-lc");
    argv.add("echo $$ > " + pidFile(commandId) + "; " + script);

    ProcessBuilder builder = new ProcessBuilder(argv);
    builder.directory(workspaceRoot.toFile());
    // The container-side environment used to travel as `-e` flags inside a docker exec argv; here
    // it is simply the child's environment, layered over the daemon's own.
    builder.environment().putAll(environment);
    if (controllingTerminal) {
      builder.environment().putIfAbsent("TERM", "xterm-256color");
    }
    return builder;
  }

  /** Attach a live client to a running command (terminal or chat); false if none is running. */
  public boolean attach(String commandId, CommandOutputSink sink) {
    CommandSession session = sessions.get(commandId);
    if (session != null) {
      session.attach(sink);
      return true;
    }
    ChatSession chat = chats.get(commandId);
    if (chat != null) {
      chat.attach(sink);
      return true;
    }
    return false;
  }

  public void detach(String commandId, CommandOutputSink sink) {
    CommandSession session = sessions.get(commandId);
    if (session != null) {
      session.detach(sink);
    }
    ChatSession chat = chats.get(commandId);
    if (chat != null) {
      chat.detach(sink);
    }
  }

  public boolean input(String commandId, byte[] data) {
    CommandSession session = sessions.get(commandId);
    if (session == null) {
      return false;
    }
    session.input(data);
    return true;
  }

  public boolean resize(String commandId, int cols, int rows) {
    CommandSession session = sessions.get(commandId);
    if (session == null) {
      return false;
    }
    session.resize(cols, rows);
    return true;
  }

  /**
   * Send a named signal (e.g. TERM) to a running terminal command's process group — the graceful
   * half of a stop. False if the command is not a running terminal session or delivery failed.
   */
  public boolean signal(String commandId, String signal) {
    CommandSession session = sessions.get(commandId);
    return session != null && session.signal(signal);
  }

  /** Force-kill a running command (terminal or chat); false if it is not in the registry. */
  public boolean terminate(String commandId) {
    CommandSession session = sessions.get(commandId);
    if (session != null) {
      session.terminate();
      return true;
    }
    ChatSession chat = chats.get(commandId);
    if (chat != null) {
      chat.terminate();
      return true;
    }
    return false;
  }

  public boolean isRunning(String commandId) {
    CommandSession session = sessions.get(commandId);
    if (session != null) {
      return session.isAlive();
    }
    ChatSession chat = chats.get(commandId);
    return chat != null && chat.isAlive();
  }
}
