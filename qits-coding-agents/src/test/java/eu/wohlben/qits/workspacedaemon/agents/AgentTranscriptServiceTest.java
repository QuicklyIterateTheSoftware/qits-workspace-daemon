package eu.wohlben.qits.workspacedaemon.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transcript sweep, driven over a fixture claude volume.
 *
 * <p>These were {@code @QuarkusTest}s reading {@code command_log_line} out of a database. There is no
 * database any more and no CDI in this reactor, so they run against a real {@link CommandStore} and
 * {@link CommandLogService} with the fixture tree under a {@code @TempDir} — the shape {@code
 * CommandServiceTest} already uses.
 */
class AgentTranscriptServiceTest {

  private static final String SESSION = "11111111-1111-1111-1111-111111111111";
  private static final String COMMAND = "cmd-1";

  @TempDir Path claudeMount;

  private CommandStore store;
  private CommandLogService logs;
  private CommandLifecycleService lifecycle;
  private AgentSessionStore sessionStore;
  private AtomicInteger changeNotifications;
  private AgentTranscriptService service;

  @BeforeEach
  void setUp() {
    store = new CommandStore();
    logs = new CommandLogService(store, null);
    lifecycle = new CommandLifecycleService(store, null);
    sessionStore = new AgentSessionStore();
    changeNotifications = new AtomicInteger();
    service =
        new AgentTranscriptService(
            store, logs, sessionStore, claudeMount.toString(), changeNotifications::incrementAndGet);
  }

  // --- fixtures ---------------------------------------------------------------------------------

  /** Registers an agent command whose first session is {@code sessionId}. */
  private void agentCommand(String commandId, String sessionId, AgentType harness, String reported) {
    lifecycle.createRunning(
        "main",
        "abc1234",
        "agent",
        "Agent",
        "exec claude",
        false,
        CommandKind.CHAT,
        commandId,
        new AgentSessionRef(sessionId, AgentSessionSource.PINNED, null, reported, Instant.now()),
        harness.name());
  }

  /** The Claude transcript path this harness would write for {@code /workspace}. */
  private Path claudeTranscript(String sessionId) {
    return claudeMount
        .resolve(".claude")
        .resolve(new ClaudeCodeAgent().transcriptPath("/workspace", sessionId));
  }

  private void write(Path file, String... lines) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, String.join("\n", lines) + "\n");
  }

  private static String userLine(String text) {
    return "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\""
        + text
        + "\"}]},\"timestamp\":\"2026-07-27T08:00:00Z\"}";
  }

  private static String assistantLine(String text) {
    return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\""
        + text
        + "\"}]},\"timestamp\":\"2026-07-27T08:00:01Z\"}";
  }

  private List<CommandLogLine> transcript() {
    return logs.log(COMMAND, null, LogChannel.TRANSCRIPT);
  }

  // --- the sweep --------------------------------------------------------------------------------

  @Test
  void claudeEnvelopeLinesAreImportedVerbatim() throws IOException {
    agentCommand(COMMAND, SESSION, AgentType.CLAUDE, null);
    write(claudeTranscript(SESSION), userLine("hi"), assistantLine("hello"));

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    List<CommandLogLine> lines = transcript();
    assertEquals(2, lines.size(), "both lines land on the TRANSCRIPT channel");
    assertEquals(userLine("hi"), lines.get(0).content(), "Claude lines are already the envelope");
    assertEquals(
        Instant.parse("2026-07-27T08:00:00Z"),
        lines.get(0).timestamp(),
        "the line's own timestamp is preserved, not the import time");
    assertEquals(1, changeNotifications.get(), "a completed sweep nudges the workspace once");
  }

  @Test
  void aSecondSweepReplacesRatherThanDuplicates() throws IOException {
    agentCommand(COMMAND, SESSION, AgentType.CLAUDE, null);
    write(claudeTranscript(SESSION), userLine("hi"));

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));
    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    assertEquals(1, transcript().size(), "delete-and-reimport converges on one copy");
  }

  @Test
  void turnsAreCountedAndAggregatedPerSession() throws IOException {
    agentCommand(COMMAND, SESSION, AgentType.CLAUDE, null);
    write(
        claudeTranscript(SESSION),
        userLine("hi"),
        assistantLine("hello"),
        // Neither of these is a turn an operator would count.
        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t\"}]}}",
        "{\"type\":\"user\",\"isMeta\":true,\"message\":{\"content\":[{\"type\":\"text\","
            + "\"text\":\"meta\"}]}}");

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    List<AgentSessionStore.Stat> stats = sessionStore.findBySessionIds(List.of(SESSION));
    assertEquals(1, stats.size());
    assertEquals(2, stats.get(0).messageCount(), "tool carriers and meta lines are not turns");
    assertEquals(Instant.parse("2026-07-27T08:00:00Z"), stats.get(0).firstTimestamp());
    assertNull(stats.get(0).agentId(), "the session's own row carries no agent id");
  }

  @Test
  void sidechainsGetAMetaLineAndTheirOwnStatRow() throws IOException {
    agentCommand(COMMAND, SESSION, AgentType.CLAUDE, null);
    write(claudeTranscript(SESSION), userLine("go"));
    Path subagents =
        claudeMount.resolve(".claude").resolve(new ClaudeCodeAgent().subagentsDir("/workspace", SESSION));
    write(subagents.resolve("agent-a1.jsonl"), assistantLine("sub work"));
    write(
        subagents.resolve("agent-a1.meta.json"),
        "{\"agentType\":\"Explore\",\"description\":\"look around\",\"toolUseId\":\"tu-1\"}");

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    List<CommandLogLine> lines = transcript();
    Json meta = Json.parse(lines.get(1).content());
    assertEquals("qits_agent_meta", meta.path("type").asText(), "the sidechain is announced first");
    assertEquals("a1", meta.path("agentId").asText());
    assertEquals("Explore", meta.path("agentType").asText());
    assertEquals("look around", meta.path("description").asText());

    List<AgentSessionStore.Stat> stats = sessionStore.findBySessionIds(List.of(SESSION));
    AgentSessionStore.Stat sub =
        stats.stream().filter(s -> s.agentId() != null).findFirst().orElseThrow();
    assertEquals("a1", sub.agentId());
    assertEquals("Explore", sub.agentType());
    assertEquals(1, sub.messageCount());
  }

  @Test
  void kimiWireLinesAreNormalizedIntoTheSharedEnvelope() throws IOException {
    String kimiSession = "session_" + SESSION;
    agentCommand(COMMAND, kimiSession, AgentType.KIMI, null);
    Path wire =
        claudeMount
            .resolve(".kimi-code")
            .resolve(new KimiCodeAgent().transcriptPath("/workspace", kimiSession));
    write(wire, "{\"role\":\"user\",\"content\":\"hi\"}", "{\"metadata\":{\"v\":\"1\"}}");

    service.sweep(COMMAND, service.configDir(AgentType.KIMI));

    List<CommandLogLine> lines = transcript();
    assertEquals(1, lines.size(), "the metadata noise line normalizes to nothing");
    Json envelope = Json.parse(lines.get(0).content());
    assertEquals("user", envelope.path("type").asText(), "raw wire became the Claude envelope");
    assertEquals("hi", envelope.path("message").path("content").path(0).path("text").asText());
    assertEquals(kimiSession, envelope.path("sessionId").asText());
  }

  // --- what relocation changed ------------------------------------------------------------------

  @Test
  void aHookReportedPathResolvesLocallyWithNoRemapping() throws IOException {
    // On the host the reported path was container-side and had to be re-rooted onto qits' own mount.
    // In the container it IS the path -- this pins that the identity resolution actually finds it.
    Path actual = claudeMount.resolve(".claude/projects/-workspace/" + SESSION + ".jsonl");
    write(actual, userLine("reported"));
    agentCommand(COMMAND, SESSION, AgentType.CLAUDE, actual.toString());

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    assertEquals(1, transcript().size());
    assertEquals(userLine("reported"), transcript().get(0).content());
  }

  @Test
  void aReportedPathOutsideTheConfigDirIsRefusedAndTheConventionWins() throws IOException {
    // The hook endpoint is unauthenticated and the checkout is untrusted, so a reported path that
    // escapes the harness config dir must not be opened.
    Path outside = claudeMount.resolve("elsewhere/secrets.jsonl");
    write(outside, userLine("must not be imported"));
    write(claudeTranscript(SESSION), userLine("the conventional file"));
    agentCommand(COMMAND, SESSION, AgentType.CLAUDE, outside.toString());

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    assertEquals(1, transcript().size());
    assertEquals(userLine("the conventional file"), transcript().get(0).content());
  }

  @Test
  void aTraversalInTheReportedPathIsRefused() throws IOException {
    // The file really exists; only the reported path dresses it up as living under .claude.
    write(claudeMount.resolve("elsewhere/secrets.jsonl"), userLine("must not be imported"));
    Files.createDirectories(claudeMount.resolve(".claude"));
    String escape = claudeMount.resolve(".claude/../elsewhere/secrets.jsonl").toString();
    agentCommand(COMMAND, SESSION, AgentType.CLAUDE, escape);

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    assertTrue(transcript().isEmpty(), "normalization defeats the traversal, and nothing else matches");
  }

  @Test
  void aMissingTranscriptImportsNothingAndLeavesNoStats() {
    agentCommand(COMMAND, SESSION, AgentType.CLAUDE, null);
    // The config dir exists (the @TempDir) but the session's file was never written.
    try {
      Files.createDirectories(claudeMount.resolve(".claude"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    assertTrue(transcript().isEmpty());
    assertTrue(sessionStore.findBySessionIds(List.of(SESSION)).isEmpty());
  }

  @Test
  void aNonAgentCommandIsNotSwept() {
    lifecycle.createRunning(
        "main", "abc", "build", "Build", "make", false, CommandKind.TERMINAL, COMMAND, null, null);

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    assertTrue(transcript().isEmpty());
    assertEquals(0, changeNotifications.get(), "nothing changed, so nothing is nudged");
  }

  @Test
  void theHarnessIsReadOffTheCommandAndDefaultsToClaude() {
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
        null);

    assertEquals(
        AgentType.CLAUDE,
        service.harnessOf(COMMAND),
        "a command with no recorded harness predates the setting");
    assertEquals(
        claudeMount.resolve(".claude"),
        service.configDir(AgentType.CLAUDE),
        "the config dir is the mount plus the harness dot-dir, with no override knob left");
    assertEquals(claudeMount.resolve(".kimi-code"), service.configDir(AgentType.KIMI));
  }

  @Test
  void aLineWithoutItsOwnTimestampFallsBackToImportTime() throws IOException {
    agentCommand(COMMAND, SESSION, AgentType.CLAUDE, null);
    write(claudeTranscript(SESSION), "{\"type\":\"user\",\"message\":{\"content\":\"bare\"}}");
    Instant before = Instant.now();

    service.sweep(COMMAND, service.configDir(AgentType.CLAUDE));

    assertFalse(transcript().get(0).timestamp().isBefore(before));
  }
}
