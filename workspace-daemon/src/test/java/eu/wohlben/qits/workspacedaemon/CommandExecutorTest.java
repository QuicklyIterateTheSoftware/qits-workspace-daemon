package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Direct, container-free coverage of the one command path Part 1 proves end-to-end: a {@link
 * RunCommand} produces {@link CommandChunk}s then exactly one terminal {@link CommandExit}. The
 * backend/extended tests exercise the same {@link CommandExecutor} over a real socket.
 */
class CommandExecutorTest {

  private static String text(List<DaemonMessage> messages, Stream channel) {
    StringBuilder sb = new StringBuilder();
    for (DaemonMessage message : messages) {
      if (message instanceof CommandChunk chunk && chunk.stream() == channel) {
        sb.append(chunk.text());
      }
    }
    return sb.toString();
  }

  private static CommandExit exit(List<DaemonMessage> messages) {
    return messages.stream()
        .filter(m -> m instanceof CommandExit)
        .map(m -> (CommandExit) m)
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  @Test
  void streamsStdoutThenExitZero() {
    List<DaemonMessage> emitted = new ArrayList<>();
    CommandExecutor.run(
        new RunCommand("c1", List.of("echo", "hello"), null, Map.of()), emitted::add);

    assertEquals("hello\n", text(emitted, Stream.STDOUT));
    assertEquals(new CommandExit("c1", 0), exit(emitted));
    // The exit is the last message, and there is exactly one.
    assertTrue(emitted.get(emitted.size() - 1) instanceof CommandExit);
    assertEquals(1, emitted.stream().filter(m -> m instanceof CommandExit).count());
  }

  @Test
  void capturesStderrAndNonZeroExit() {
    List<DaemonMessage> emitted = new CopyOnWriteArrayList<>();
    CommandExecutor.run(
        new RunCommand("c2", List.of("sh", "-c", "echo boom >&2; exit 3"), null, Map.of()),
        emitted::add);

    assertTrue(text(emitted, Stream.STDERR).contains("boom"));
    assertEquals(3, exit(emitted).exitCode());
  }

  @Test
  void honoursCwdAndEnv() {
    List<DaemonMessage> emitted = new ArrayList<>();
    CommandExecutor.run(
        new RunCommand(
            "c3", List.of("sh", "-c", "pwd; echo $GREETING"), "/tmp", Map.of("GREETING", "hi")),
        emitted::add);

    String out = text(emitted, Stream.STDOUT);
    assertTrue(out.contains("/tmp"), out);
    assertTrue(out.contains("hi"), out);
    assertEquals(0, exit(emitted).exitCode());
  }

  @Test
  void missingBinaryStillProducesTerminalExit() {
    List<DaemonMessage> emitted = new ArrayList<>();
    CommandExecutor.run(
        new RunCommand("c4", List.of("no-such-binary-xyzzy"), null, Map.of()), emitted::add);

    assertEquals(127, exit(emitted).exitCode());
  }
}
