package eu.wohlben.qits.workspacedaemon.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.agents.json.Json;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionRef;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionSource;
import eu.wohlben.qits.workspacedaemon.commands.CommandKind;
import eu.wohlben.qits.workspacedaemon.commands.CommandLifecycleService;
import eu.wohlben.qits.workspacedaemon.commands.CommandLogLine;
import eu.wohlben.qits.workspacedaemon.commands.CommandLogService;
import eu.wohlben.qits.workspacedaemon.commands.CommandStore;
import eu.wohlben.qits.workspacedaemon.commands.LogChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The live tail, driven deterministically through {@code pollNow} rather than its scheduler — the
 * seam the host tests already used, and the reason the scheduler never has to run here.
 */
class AgentTranscriptTailServiceTest {

  private static final String SESSION = "22222222-2222-2222-2222-222222222222";
  private static final String COMMAND = "cmd-tail";

  @TempDir Path claudeMount;

  private CommandStore store;
  private CommandLogService logs;
  private AgentTranscriptService transcripts;
  private AgentTranscriptTailService tail;
  private Path transcriptFile;

  @BeforeEach
  void setUp() throws IOException {
    store = new CommandStore();
    logs = new CommandLogService(store, null);
    CommandLifecycleService lifecycle = new CommandLifecycleService(store, null);
    transcripts =
        new AgentTranscriptService(
            store, logs, new AgentSessionStore(), claudeMount.toString(), null);
    tail = new AgentTranscriptTailService(transcripts, logs);
    lifecycle.createRunning(
        "main",
        "abc",
        "agent",
        "Agent",
        "exec claude",
        false,
        CommandKind.CHAT,
        COMMAND,
        new AgentSessionRef(SESSION, AgentSessionSource.PINNED, null, null, Instant.now()),
        AgentType.CLAUDE.name());
    transcriptFile =
        claudeMount
            .resolve(".claude")
            .resolve(new ClaudeCodeAgent().transcriptPath("/workspace", SESSION));
    Files.createDirectories(transcriptFile.getParent());
    Files.writeString(transcriptFile, "");
  }

  private void append(String text) throws IOException {
    Files.writeString(
        transcriptFile,
        text,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private static String line(String text) {
    return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\""
        + text
        + "\"}]}}";
  }

  private List<CommandLogLine> transcript() {
    return logs.log(COMMAND, null, LogChannel.TRANSCRIPT);
  }

  @Test
  void completeLinesAreImportedAsTheyArrive() throws IOException {
    tail.startTail(COMMAND, claudeMount.resolve(".claude"));
    append(line("one") + "\n");
    tail.pollNow(COMMAND);

    assertEquals(1, transcript().size());
    assertEquals(line("one"), transcript().get(0).content());

    append(line("two") + "\n");
    tail.pollNow(COMMAND);

    assertEquals(2, transcript().size(), "the second poll resumes from the byte position");
    assertEquals(line("two"), transcript().get(1).content());
  }

  @Test
  void aPartialLineIsHeldUntilItsNewlineArrives() throws IOException {
    tail.startTail(COMMAND, claudeMount.resolve(".claude"));
    String whole = line("split");
    append(whole.substring(0, 20));
    tail.pollNow(COMMAND);

    assertTrue(transcript().isEmpty(), "half a JSON line must never be imported");

    append(whole.substring(20) + "\n");
    tail.pollNow(COMMAND);

    assertEquals(1, transcript().size());
    assertEquals(whole, transcript().get(0).content(), "the halves rejoin exactly");
  }

  @Test
  void truncationReseedsTheChannelInsteadOfAppendingTwice() throws IOException {
    tail.startTail(COMMAND, claudeMount.resolve(".claude"));
    append(line("one") + "\n" + line("two") + "\n");
    tail.pollNow(COMMAND);
    assertEquals(2, transcript().size());

    // The harness only ever appends, so a shrink means the file was replaced.
    Files.writeString(transcriptFile, line("fresh") + "\n");
    tail.pollNow(COMMAND);

    assertEquals(1, transcript().size(), "the channel is re-seeded, not appended to");
    assertEquals(line("fresh"), transcript().get(0).content());
  }

  @Test
  void stopAndDrainReportsTheHighWaterAndTakesTheLastLines() throws IOException {
    tail.startTail(COMMAND, claudeMount.resolve(".claude"));
    append(line("one") + "\n" + line("two") + "\n");

    long imported = tail.stopAndDrain(COMMAND);

    assertEquals(2, imported, "the count the exit sweep's settle compares against");
    assertEquals(2, transcript().size(), "the final drain picked up what the schedule had not");
    assertEquals(0, tail.stopAndDrain(COMMAND), "a second stop is a no-op");
  }

  @Test
  void kimiWireLinesAreNormalizedLiveExactlyAsTheSweepWould() throws IOException {
    String kimiSession = "session_" + SESSION;
    CommandStore kimiStore = new CommandStore();
    CommandLogService kimiLogs = new CommandLogService(kimiStore, null);
    new CommandLifecycleService(kimiStore, null)
        .createRunning(
            "main",
            "abc",
            "agent",
            "Agent",
            "exec kimi acp",
            false,
            CommandKind.CHAT,
            COMMAND,
            new AgentSessionRef(kimiSession, AgentSessionSource.PINNED, null, null, Instant.now()),
            AgentType.KIMI.name());
    AgentTranscriptService kimiTranscripts =
        new AgentTranscriptService(
            kimiStore, kimiLogs, new AgentSessionStore(), claudeMount.toString(), null);
    AgentTranscriptTailService kimiTail =
        new AgentTranscriptTailService(kimiTranscripts, kimiLogs);
    Path wire =
        claudeMount
            .resolve(".kimi-code")
            .resolve(new KimiCodeAgent().transcriptPath("/workspace", kimiSession));
    Files.createDirectories(wire.getParent());
    Files.writeString(wire, "{\"role\":\"assistant\",\"content\":\"live\"}\n");

    kimiTail.startTail(COMMAND, claudeMount.resolve(".kimi-code"));
    kimiTail.pollNow(COMMAND);

    List<CommandLogLine> lines = kimiLogs.log(COMMAND, null, LogChannel.TRANSCRIPT);
    assertEquals(1, lines.size());
    Json envelope = Json.parse(lines.get(0).content());
    assertEquals("assistant", envelope.path("type").asText());
    assertEquals("live", envelope.path("message").path("content").path(0).path("text").asText());
  }

  @Test
  void startAndCloseAreIdempotent() {
    tail.start();
    tail.start();
    tail.close();
    tail.close();
  }
}
