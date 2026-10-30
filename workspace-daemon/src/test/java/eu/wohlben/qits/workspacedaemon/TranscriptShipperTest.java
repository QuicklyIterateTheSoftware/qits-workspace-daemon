package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptChunk;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptResync;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TranscriptShipper} against real files in a temporary harness config directory, with a
 * recording socket and a hand-turned clock. Every chunk the socket accepts is also appended to a
 * per-file "host archive" exactly as qits-workspaces would — only when its offset equals what the
 * archive holds — so the tests can assert the property that matters: what the host ends up with is
 * the file, byte for byte.
 */
class TranscriptShipperTest {

  private static final String SESSION = "5f0c1a7e-0000-4000-8000-000000000001";

  @TempDir Path home;

  private Path main;
  private final List<TranscriptChunk> sent = new ArrayList<>();
  private final AtomicLong now = new AtomicLong(1_000_000L);
  private boolean connected = true;
  private TranscriptShipper shipper;

  @BeforeEach
  void setUp() throws IOException {
    main = home.resolve(".claude/projects/-workspace-agents-a1-wrapper/" + SESSION + ".jsonl");
    Files.createDirectories(main.getParent());
    shipper = shipper(64);
  }

  private TranscriptShipper shipper(int maxChunkBytes) {
    return new TranscriptShipper(
        chunk -> {
          if (!connected) {
            return false;
          }
          sent.add(chunk);
          return true;
        },
        now::get,
        List.of(home),
        maxChunkBytes,
        1024 * 1024);
  }

  private AgentActivity hook(String event, String state) {
    return new AgentActivity("cmd-1", SESSION, state, event, null, main.toString(), now.get())
        .withAgentId("agent-1");
  }

  private static void append(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(
        file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  /** Poll until a poll sends nothing more, as the timer would over a few ticks. */
  private void drain() {
    int before;
    do {
      before = sent.size();
      shipper.poll();
    } while (sent.size() > before);
  }

  /** The text the host would hold for {@code file}, appending only chunks that start at its end. */
  private String archive(String file) {
    StringBuilder held = new StringBuilder();
    for (TranscriptChunk chunk : sent) {
      if (chunk.file().equals(file)
          && chunk.offset() == held.toString().getBytes(StandardCharsets.UTF_8).length) {
        held.append(chunk.data());
      }
    }
    return held.toString();
  }

  @Test
  void appendedLinesAreShippedOnceEach() throws IOException {
    append(main, "{\"n\":1}\n{\"n\":2}\n");
    shipper.observe(hook("SessionStart", "IDLE"));
    drain();
    assertEquals(1, sent.size());
    TranscriptChunk first = sent.get(0);
    assertEquals("agent-1", first.agentId());
    assertEquals("cmd-1", first.commandId());
    assertEquals(SESSION, first.sessionId());
    assertEquals("main", first.file());
    assertEquals(0L, first.offset());
    assertEquals("{\"n\":1}\n{\"n\":2}\n", first.data());

    drain();
    assertEquals(1, sent.size(), "nothing new, nothing sent");

    append(main, "{\"n\":3}\n");
    drain();
    assertEquals(2, sent.size());
    assertEquals(16L, sent.get(1).offset());
    assertEquals("{\"n\":3}\n", sent.get(1).data());
    assertEquals(Files.readString(main), archive("main"));
  }

  @Test
  void aPartialLineIsHeldUntilItsNewlineArrives() throws IOException {
    shipper.observe(hook("SessionStart", "IDLE"));
    append(main, "{\"n\":1}\n{\"n\":");
    drain();
    assertEquals(List.of("{\"n\":1}\n"), sent.stream().map(TranscriptChunk::data).toList());

    drain();
    assertEquals(1, sent.size(), "the half line is not sent on its own");

    append(main, "2}\n");
    drain();
    assertEquals("{\"n\":2}\n", sent.get(1).data());
    assertEquals(8L, sent.get(1).offset());
  }

  @Test
  void sidechainsBesideTheSessionAreDiscoveredAndShippedUnderSubagents() throws IOException {
    shipper.observe(hook("SessionStart", "IDLE"));
    append(main, "m\n");
    Path subagents = main.resolveSibling(SESSION).resolve("subagents");
    append(subagents.resolve("agent-a1b2.jsonl"), "s1\n");
    append(subagents.resolve("agent-a1b2.meta.json"), "{}\n");
    append(subagents.resolve("notes.jsonl"), "x\n");
    drain();
    assertEquals(
        List.of("main", "subagents/agent-a1b2.jsonl"),
        sent.stream().map(TranscriptChunk::file).toList());

    append(subagents.resolve("agent-c3.jsonl"), "s2\n");
    drain();
    assertEquals("subagents/agent-c3.jsonl", sent.get(2).file());
    assertEquals("s2\n", sent.get(2).data());
  }

  @Test
  void aSymlinkedSidechainIsNotFollowed() throws IOException {
    Path secret = home.resolve("other-workspace.jsonl");
    append(secret, "theirs\n");
    Path subagents = main.resolveSibling(SESSION).resolve("subagents");
    Files.createDirectories(subagents);
    Files.createSymbolicLink(subagents.resolve("agent-x.jsonl"), secret);
    shipper.observe(hook("SessionStart", "IDLE"));
    drain();
    assertTrue(sent.isEmpty());
  }

  @Test
  void aResyncRewindsAndTheHostEndsUpWithTheWholeFile() throws IOException {
    shipper.observe(hook("SessionStart", "IDLE"));
    append(main, "aaaa\nbbbb\n");
    drain();
    shipper.resync(new TranscriptResync(SESSION, "main", 5));
    drain();
    assertEquals(2, sent.size());
    assertEquals(5L, sent.get(1).offset());
    assertEquals("bbbb\n", sent.get(1).data());
  }

  @Test
  void aResyncSkipsAheadOverWhatTheHostAlreadyHolds() throws IOException {
    append(main, "aaaa\nbbbb\ncccc\n");
    // A restarted daemon: the cursor is back at 0, and its first chunk draws the host's size.
    shipper = shipper(5);
    shipper.observe(hook("SessionStart", "IDLE"));
    shipper.poll();
    assertEquals(1, sent.size(), "a fresh cursor sends one chunk and waits for the host's answer");
    shipper.resync(new TranscriptResync(SESSION, "main", 10));
    drain();
    assertEquals(2, sent.size());
    assertEquals(10L, sent.get(1).offset());
    assertEquals("cccc\n", sent.get(1).data());
  }

  @Test
  void aResyncBeyondTheLocalFileContinuesFromItsEnd() throws IOException {
    shipper.observe(hook("SessionStart", "IDLE"));
    append(main, "aaaa\n");
    drain();
    shipper.resync(new TranscriptResync(SESSION, "main", 500));
    append(main, "bbbb\n");
    drain();
    assertEquals(5L, sent.get(1).offset());
    assertEquals("bbbb\n", sent.get(1).data());
  }

  @Test
  void aStaleResyncInTheSameConnectionIsIgnoredButANewConnectionHonoursIt() throws IOException {
    shipper = shipper(5);
    shipper.observe(hook("SessionStart", "IDLE"));
    append(main, "aaaa\nbbbb\ncccc\n");
    drain();
    assertEquals(3, sent.size());
    // Three chunks went out from a wrong cursor; each draws resync(5). Only the first applies.
    shipper.resync(new TranscriptResync(SESSION, "main", 5));
    drain();
    assertEquals(5, sent.size());
    shipper.resync(new TranscriptResync(SESSION, "main", 5));
    shipper.resync(new TranscriptResync(SESSION, "main", 5));
    drain();
    assertEquals(5, sent.size(), "the stale ones re-send nothing");

    // The socket reconnects to a host that holds 5 again — that one is real.
    shipper.connected();
    shipper.resync(new TranscriptResync(SESSION, "main", 5));
    drain();
    assertEquals(7, sent.size());
    assertEquals(5L, sent.get(5).offset());
  }

  @Test
  void largeAppendsAreSplitIntoChunksThatEachEndAtANewline() throws IOException {
    shipper = shipper(16);
    shipper.observe(hook("SessionStart", "IDLE"));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      text.append("line-").append(i).append('\n');
    }
    append(main, text.toString());
    drain();
    assertTrue(sent.size() > 5);
    long offset = 0;
    for (TranscriptChunk chunk : sent) {
      byte[] bytes = chunk.data().getBytes(StandardCharsets.UTF_8);
      assertTrue(bytes.length <= 16, chunk.data());
      assertTrue(chunk.data().endsWith("\n"), chunk.data());
      assertEquals(offset, chunk.offset());
      offset += bytes.length;
    }
    assertEquals(text.toString(), archive("main"));
  }

  @Test
  void aLineLongerThanTheCapIsSplitAtCharacterBoundariesOnlyOnceItIsComplete()
      throws IOException {
    shipper = shipper(16);
    shipper.observe(hook("SessionStart", "IDLE"));
    String line = "ääääääääääääääääääääüü€€€x";
    append(main, line);
    drain();
    assertTrue(sent.isEmpty(), "still being written");

    append(main, "\n");
    drain();
    assertTrue(sent.size() > 1);
    long offset = 0;
    for (TranscriptChunk chunk : sent) {
      byte[] bytes = chunk.data().getBytes(StandardCharsets.UTF_8);
      assertTrue(bytes.length <= 16);
      assertFalse(chunk.data().contains("�"), "no character was cut in half");
      assertEquals(offset, chunk.offset());
      offset += bytes.length;
    }
    assertEquals(line + "\n", archive("main"));
  }

  @Test
  void nothingAdvancesWhileDisconnectedAndShippingResumesOnReconnect() throws IOException {
    shipper.observe(hook("SessionStart", "IDLE"));
    append(main, "aaaa\n");
    connected = false;
    drain();
    assertTrue(sent.isEmpty());

    connected = true;
    shipper.connected();
    append(main, "bbbb\n");
    drain();
    assertEquals(0L, sent.get(0).offset());
    assertEquals("aaaa\nbbbb\n", archive("main"));
  }

  @Test
  void onlyPathsUnderTheHarnessConfigDirectoriesAreShipped() throws IOException {
    Path outside = Files.createTempFile("elsewhere", ".jsonl");
    try {
      Files.writeString(outside, "nope\n");
      shipper.observe(
          new AgentActivity("cmd-1", SESSION, "IDLE", "SessionStart", null, outside.toString(), 1));
      shipper.observe(
          new AgentActivity(
              "cmd-1",
              SESSION,
              "IDLE",
              "SessionStart",
              null,
              home.resolve("x/../../escape.jsonl").toString(),
              1));
      shipper.observe(
          new AgentActivity("cmd-1", "../bad", "IDLE", "SessionStart", null, main.toString(), 1));
      shipper.observe(new AgentActivity("cmd-1", SESSION, "IDLE", "SessionStart", null, null, 1));
      drain();
      assertTrue(sent.isEmpty());
      assertEquals(0, shipper.sessionCount());
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void aTurnEndOrSessionEndAsksForAPromptShipAndOtherHooksDoNot() {
    assertFalse(shipper.observe(hook("SessionStart", "IDLE")));
    assertFalse(shipper.observe(hook("UserPromptSubmit", "BUSY")));
    assertTrue(shipper.observe(hook("Stop", "IDLE")));
    assertTrue(shipper.observe(hook("SessionEnd", "ENDED")));
  }

  @Test
  void anUnknownSessionsResyncChangesNothing() throws IOException {
    append(main, "aaaa\n");
    shipper.resync(new TranscriptResync("other-session", "main", 3));
    shipper.observe(hook("SessionStart", "IDLE"));
    drain();
    assertEquals(0L, sent.get(0).offset());
  }

  @Test
  void anEndedSessionIsForgottenOnceShippedAndItsLingerHasPassed() throws IOException {
    shipper.observe(hook("SessionStart", "IDLE"));
    append(main, "aaaa\n");
    shipper.observe(hook("SessionEnd", "ENDED"));
    drain();
    assertEquals(1, shipper.sessionCount(), "kept a while for a late resync");

    now.addAndGet(TranscriptShipper.ENDED_LINGER.toMillis());
    shipper.poll();
    assertEquals(0, shipper.sessionCount());
  }

  @Test
  void aHarnessExitEndsItsSessionsAndAnUnshippedOneIsKeptUntilRetentionRunsOut()
      throws IOException {
    shipper.observe(hook("SessionStart", "IDLE"));
    append(main, "aaaa\n");
    connected = false;
    shipper.commandEnded("cmd-1");
    now.addAndGet(TranscriptShipper.ENDED_LINGER.toMillis());
    shipper.poll();
    assertEquals(1, shipper.sessionCount(), "not shipped yet");

    now.addAndGet(TranscriptShipper.ENDED_RETENTION.toMillis());
    shipper.poll();
    assertEquals(0, shipper.sessionCount());
  }

  @Test
  void aResumedSessionIsNoLongerEnded() throws IOException {
    shipper.observe(hook("SessionStart", "IDLE"));
    shipper.observe(hook("SessionEnd", "ENDED"));
    shipper.observe(hook("SessionStart", "IDLE"));
    append(main, "aaaa\n");
    now.addAndGet(TranscriptShipper.ENDED_LINGER.toMillis());
    drain();
    shipper.poll();
    assertEquals(1, shipper.sessionCount());
  }
}
