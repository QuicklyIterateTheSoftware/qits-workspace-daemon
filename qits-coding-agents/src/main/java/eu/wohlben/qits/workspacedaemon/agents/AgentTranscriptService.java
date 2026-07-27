package eu.wohlben.qits.workspacedaemon.agents;

import eu.wohlben.qits.workspacedaemon.agents.acp.KimiEventNormalizer;
import eu.wohlben.qits.workspacedaemon.agents.json.Json;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionRef;
import eu.wohlben.qits.workspacedaemon.commands.Command;
import eu.wohlben.qits.workspacedaemon.commands.CommandChangeListener;
import eu.wohlben.qits.workspacedaemon.commands.CommandLogService;
import eu.wohlben.qits.workspacedaemon.commands.CommandStore;
import eu.wohlben.qits.workspacedaemon.commands.LogChannel;
import io.vertx.core.json.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Imports agent-session transcripts — the JSONL files the harness itself persists, including
 * subagent sidechains — into the command's log buffer on the {@link LogChannel#TRANSCRIPT} channel,
 * which is what {@code GET /commands/{id}/log?channel=TRANSCRIPT} serves to the Agents view.
 *
 * <p>Runs once per agent command, on process exit (composed onto the registry exit listener), as a
 * delete-and-reimport of the command's {@code TRANSCRIPT} channel — idempotent by construction. A
 * command that traversed several sessions (in-TUI {@code /resume}) imports each session's transcript
 * in list order; the lines self-describe via {@code sessionId}, so segment boundaries stay
 * recoverable in the rendered view.
 *
 * <p><strong>What relocation changed.</strong> On the host this class existed either side of a
 * boundary: the harness wrote transcripts inside a workspace container, qits read them from its own
 * filesystem, and the two only met because the devcontainer happened to mount the same claude
 * volume. Hence a configurable config dir, a legacy alias for it, and a remap of every hook-reported
 * path from container-side to host-side. Inside the container none of that is true any more —
 * {@code /claude-home} is simply a local directory, and a reported path <em>is</em> the path. The
 * config-dir overrides are gone and {@link #resolveReportedPath} is identity after a validity check.
 *
 * <p>The volume is still shared across workspaces and still outlives any single container, so the
 * transcripts survive a recreate. What does not survive is the index of them — see {@link
 * AgentSessionStore}.
 */
public final class AgentTranscriptService {

  private static final Logger LOG = System.getLogger(AgentTranscriptService.class.getName());

  /** The agent always runs with the checkout as its cwd (the daemon's own working directory). */
  static final String CONTAINER_CWD = "/workspace";

  /**
   * Base of the TRANSCRIPT sequence space, disjoint from live stdio sequences (which start at 0 per
   * run) so imported lines sort after — and never collide with — intercepted output.
   */
  static final long TRANSCRIPT_SEQ_BASE = 1L << 40;

  /** The synthetic line type carrying a sidechain's meta (agent type, description, anchor). */
  static final String AGENT_META_TYPE = "qits_agent_meta";

  /** How often and how long {@link #onChatExit} waits for the harness's JSONL flush. */
  private static final int SETTLE_ATTEMPTS = 4;

  private static final long SETTLE_DELAY_MS = 250;

  private static final AtomicBoolean MISSING_CONFIG_DIR_LOGGED = new AtomicBoolean();

  private final CommandStore store;
  private final CommandLogService commandLogService;
  private final AgentSessionStore sessionStore;
  private final String claudeMount;
  private final CommandChangeListener onChanged;

  /**
   * @param claudeMount where the shared credential volume is mounted in this container (the {@code
   *     qits.workspace.claude-mount} value). A constructor argument rather than a config read
   *     because this module is framework-free, and because the launch path must overlay the very
   *     same value as {@code HOME} — one reader, no chance of drift.
   * @param onChanged notified when a sweep changed a command's transcript, so the workspace can be
   *     nudged to refetch. May be null.
   */
  public AgentTranscriptService(
      CommandStore store,
      CommandLogService commandLogService,
      AgentSessionStore sessionStore,
      String claudeMount,
      CommandChangeListener onChanged) {
    this.store = store;
    this.commandLogService = commandLogService;
    this.sessionStore = sessionStore;
    this.claudeMount = claudeMount;
    this.onChanged = onChanged;
  }

  /**
   * The resolved harness config dir: {@code <claude-mount>/<harness dot-dir>}. Per-command, because
   * the dot-dir differs by harness ({@code .claude} vs {@code .kimi-code}). Package-visible: the live
   * tail resolves the same dir.
   */
  Path configDir(AgentType agentType) {
    return Path.of(claudeMount, dotDir(agentType));
  }

  private static String dotDir(AgentType agentType) {
    return switch (agentType) {
      case CLAUDE -> ".claude";
      case KIMI -> ".kimi-code";
    };
  }

  /**
   * The harness a command was launched with. An unset or unparseable value ⇒ legacy {@link
   * AgentType#CLAUDE}. The commands module stores this as a plain String so it need not depend on
   * this one.
   */
  AgentType harnessOf(String commandId) {
    return store.find(commandId).map(AgentTranscriptService::harnessOf).orElse(AgentType.CLAUDE);
  }

  private static AgentType harnessOf(Command command) {
    return AgentType.parse(command.agentType()).orElse(AgentType.CLAUDE);
  }

  /**
   * The exit-listener entry point: sweep the command's transcripts, never letting a failure propagate
   * into the registry's exit handling.
   */
  public void onCommandExit(String commandId) {
    try {
      sweep(commandId, configDir(harnessOf(commandId)));
    } catch (RuntimeException e) {
      LOG.log(Level.ERROR, () -> "Transcript sweep failed for command " + commandId, e);
    }
  }

  /**
   * The chat variant of {@link #onCommandExit}: the live tail has already imported {@code
   * expectedMainLines} main-session lines, so wait for the harness's asynchronous JSONL flush to
   * catch up before the delete-and-reimport — sweeping early would replace the tail's good rows with
   * fewer. Bounded settle (the exit chain runs on the chat reader thread and {@code terminate()}
   * waits only 2 s on the finish latch); on exhaustion, sweep anyway with a warning.
   */
  public void onChatExit(String commandId, long expectedMainLines) {
    try {
      chatSweep(commandId, expectedMainLines, configDir(harnessOf(commandId)));
    } catch (RuntimeException e) {
      LOG.log(Level.ERROR, () -> "Transcript sweep failed for command " + commandId, e);
    }
  }

  /** Settle-then-sweep; package-visible so tests can point it at a fixture config dir. */
  void chatSweep(String commandId, long expectedMainLines, Path configDir) {
    awaitSettled(commandId, expectedMainLines, configDir);
    sweep(commandId, configDir);
  }

  private void awaitSettled(String commandId, long expectedMainLines, Path configDir) {
    if (expectedMainLines <= 0 || !Files.isDirectory(configDir)) {
      return;
    }
    SessionInfo main = mainSession(commandId);
    if (main == null) {
      return;
    }
    CodingAgent agent = CodingAgentFactory.ofType(main.agentType());
    for (int attempt = 0; ; attempt++) {
      Path transcript = resolveTranscript(configDir, agent, main);
      if (transcript != null && countNonBlankLines(transcript) >= expectedMainLines) {
        return;
      }
      if (attempt >= SETTLE_ATTEMPTS - 1) {
        LOG.log(
            Level.WARNING,
            () ->
                "Transcript of command "
                    + commandId
                    + " is still shorter than the "
                    + expectedMainLines
                    + " line(s) already imported live — sweeping anyway");
        return;
      }
      try {
        Thread.sleep(SETTLE_DELAY_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private long countNonBlankLines(Path file) {
    try (Stream<String> lines = Files.lines(file)) {
      return lines.filter(line -> !line.isBlank()).count();
    } catch (IOException e) {
      return -1;
    }
  }

  /** The command's first (for a chat: only) session, or null. */
  SessionInfo mainSession(String commandId) {
    return store
        .find(commandId)
        .filter(command -> !command.agentSessions().isEmpty())
        .map(
            command -> {
              AgentSessionRef ref = command.agentSessions().get(0);
              return new SessionInfo(ref.sessionId(), ref.transcriptPath(), harnessOf(command));
            })
        .orElse(null);
  }

  /** The line's own {@code timestamp} field when present and parseable, else null. */
  Instant lineTimestamp(String line) {
    return ownTimestamp(Json.parse(line));
  }

  /** The sweep itself; package-visible so tests can point it at a fixture config dir. */
  void sweep(String commandId, Path configDir) {
    CommandInfo info = loadInfo(commandId);
    if (info == null || info.sessions().isEmpty()) {
      return; // not an agent command (or gone) — nothing to import.
    }
    if (!Files.isDirectory(configDir)) {
      // The container is running without the shared claude volume; say so once, not per command.
      if (MISSING_CONFIG_DIR_LOGGED.compareAndSet(false, true)) {
        LOG.log(
            Level.WARNING,
            () ->
                "Agent config dir "
                    + configDir
                    + " does not exist — transcript import is disabled"
                    + " (is the claude volume mounted?)");
      }
      return;
    }

    // Delete-and-reimport: a crashed prior sweep or a manual re-trigger converges to one copy.
    commandLogService.deleteChannel(commandId, LogChannel.TRANSCRIPT);

    AgentType harness = info.agentType();
    ImportBuffer buffer = new ImportBuffer(commandId);
    CodingAgent agent = CodingAgentFactory.ofType(harness);
    // Stats aggregate once per session even when the list revisits one (in-TUI switch back).
    Set<String> visited = new LinkedHashSet<>();
    List<AgentSessionStore.Stat> stats = new ArrayList<>();
    for (SessionInfo session : info.sessions()) {
      boolean firstVisit = visited.add(session.sessionId());
      Path transcript = resolveTranscript(configDir, agent, session);
      StatCollector sessionStat = new StatCollector(harness);
      if (transcript == null) {
        LOG.log(
            Level.WARNING,
            () ->
                "No transcript found for session "
                    + session.sessionId()
                    + " of command "
                    + commandId);
      } else {
        importJsonl(
            buffer, transcript, sessionStat, kimiNormalizer(harness, session.sessionId(), null));
        if (firstVisit) {
          stats.add(sessionStat.toStat(commandId, session.sessionId(), null, null));
        }
      }
      List<AgentSessionStore.Stat> sidechainStats =
          importSidechains(
              buffer,
              configDir.resolve(agent.subagentsDir(CONTAINER_CWD, session.sessionId())),
              commandId,
              session.sessionId(),
              harness);
      if (firstVisit) {
        stats.addAll(sidechainStats);
      }
    }
    buffer.flush();
    replaceStats(stats);
    if (buffer.imported > 0) {
      long imported = buffer.imported;
      LOG.log(
          Level.INFO,
          () -> "Imported " + imported + " transcript line(s) for command " + commandId);
    }
    notifyChanged();
  }

  private void notifyChanged() {
    if (onChanged == null) {
      return;
    }
    try {
      onChanged.commandsChanged();
    } catch (RuntimeException e) {
      LOG.log(Level.DEBUG, "Transcript change notification failed", e);
    }
  }

  /** What the sweep needs from the command. */
  private record CommandInfo(AgentType agentType, List<SessionInfo> sessions) {}

  /**
   * One session's identity + hook-reported path + the command's harness (denormalized per session so
   * {@code resolveTranscript} and the live tail read it straight off the session); shared with the
   * live tail.
   */
  record SessionInfo(String sessionId, String reportedTranscriptPath, AgentType agentType) {}

  private CommandInfo loadInfo(String commandId) {
    return store
        .find(commandId)
        .map(
            command -> {
              AgentType harness = harnessOf(command);
              List<SessionInfo> sessions =
                  command.agentSessions().stream()
                      .map(
                          ref ->
                              new SessionInfo(ref.sessionId(), ref.transcriptPath(), harness))
                      .toList();
              return new CommandInfo(harness, sessions);
            })
        .orElse(null);
  }

  /**
   * The session's transcript file: the hook-reported path (authoritative) when it resolves, else the
   * harness convention, else a lookup under the harness's transcript root (the recovery path if the
   * escaping convention ever drifts across CLI upgrades — Claude's {@code
   * projects/<escaped-cwd>/<id>.jsonl} filename, Kimi's {@code sessions/<workDirKey>/<id>}
   * directory), else null. Package-visible: the live tail resolves the same way.
   */
  Path resolveTranscript(Path configDir, CodingAgent agent, SessionInfo session) {
    Path reported =
        resolveReportedPath(configDir, session.reportedTranscriptPath(), session.agentType());
    if (reported != null && Files.isRegularFile(reported)) {
      return reported;
    }
    Path conventional = configDir.resolve(agent.transcriptPath(CONTAINER_CWD, session.sessionId()));
    if (Files.isRegularFile(conventional)) {
      return conventional;
    }
    return switch (session.agentType()) {
      case CLAUDE -> findByFilename(configDir.resolve("projects"), session.sessionId() + ".jsonl");
      case KIMI -> findKimiTranscript(configDir.resolve("sessions"), session.sessionId());
    };
  }

  /**
   * A hook-reported transcript path, checked and taken as-is.
   *
   * <p>The harness runs in this same container and reports the path it actually wrote, so this is
   * the identity function — the host's container-side-to-host-side remap has nothing left to do.
   * What survives is the check: the reported path must sit under this harness's config dir, because
   * the hook endpoint is unauthenticated (loopback-only, but so is anything the untrusted checkout
   * can run) and the value ends up being opened. Anything else falls back to the convention.
   */
  private Path resolveReportedPath(Path configDir, String reportedPath, AgentType agentType) {
    if (reportedPath == null || reportedPath.isBlank()) {
      return null;
    }
    Path reported = Path.of(reportedPath).normalize();
    return reported.startsWith(configDir.normalize()) ? reported : null;
  }

  private Path findByFilename(Path projectsDir, String filename) {
    if (!Files.isDirectory(projectsDir)) {
      return null;
    }
    // projects/<escaped-cwd>/<sessionId>.jsonl — depth 2; one spare level for safety.
    try (Stream<Path> walk = Files.walk(projectsDir, 3)) {
      return walk.filter(p -> p.getFileName().toString().equals(filename))
          .filter(Files::isRegularFile)
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The Kimi recovery lookup: find the session's directory by name under {@code sessions/} (any
   * workDirKey) and resolve its main {@code wire.jsonl}.
   */
  private Path findKimiTranscript(Path sessionsDir, String sessionId) {
    if (!Files.isDirectory(sessionsDir)) {
      return null;
    }
    // sessions/<workDirKey>/<sessionId>/agents/main/wire.jsonl — the session dir sits at depth 2.
    try (Stream<Path> walk = Files.walk(sessionsDir, 2)) {
      return walk.filter(Files::isDirectory)
          .filter(p -> p.getFileName().toString().equals(sessionId))
          .map(p -> p.resolve(Path.of("agents", "main", "wire.jsonl")))
          .filter(Files::isRegularFile)
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Streams one JSONL file into the buffer (no slurp — transcripts can be large). Claude lines are
   * already in the event envelope the frontend renders and are buffered verbatim; Kimi {@code
   * wire.jsonl} lines are run through {@code normalizer} (non-null only for Kimi) into that same
   * envelope with the shared minted uuids, so kimi transcripts render and a chat re-attach stitches
   * losslessly. Stats are always collected on the <em>source</em> line — one observation per raw
   * message — so a message that normalizes into several envelopes still counts as one turn (matching
   * Claude's per-line counting) and the earliest raw line (incl. a timestamped metadata header) still
   * seeds the session's first-timestamp.
   */
  private void importJsonl(
      ImportBuffer buffer, Path file, StatCollector stats, KimiEventNormalizer normalizer) {
    try (BufferedReader reader = Files.newBufferedReader(file)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        Json node = Json.parse(line);
        Instant timestamp = ownTimestamp(node);
        Instant when = timestamp != null ? timestamp : Instant.now();
        if (normalizer == null) {
          buffer.add(line, when);
        } else {
          for (String envelope : normalizer.onWireLine(node)) {
            buffer.add(envelope, when);
          }
        }
        stats.observe(node, timestamp);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A Kimi wire→envelope normalizer for a session (or a sidechain of it), or null under Claude. */
  private KimiEventNormalizer kimiNormalizer(
      AgentType agentType, String sessionId, String sidechainAgentId) {
    if (agentType != AgentType.KIMI) {
      return null;
    }
    KimiEventNormalizer normalizer = new KimiEventNormalizer(sessionId);
    if (sidechainAgentId != null) {
      normalizer.asSidechain(sidechainAgentId);
    }
    return normalizer;
  }

  /**
   * Imports every sidechain of a session. For Claude, each {@code agent-<id>.jsonl} (sorted by name
   * for determinism) is preceded by a synthetic {@value #AGENT_META_TYPE} line built from the sibling
   * {@code .meta.json}. For Kimi, sidechains live in {@code agents/<id>/wire.jsonl} subdirectories.
   * Returns one stat row per sidechain.
   */
  private List<AgentSessionStore.Stat> importSidechains(
      ImportBuffer buffer,
      Path subagentsDir,
      String commandId,
      String sessionId,
      AgentType agentType) {
    if (!Files.isDirectory(subagentsDir)) {
      return List.of();
    }
    return switch (agentType) {
      case CLAUDE -> importClaudeSidechains(buffer, subagentsDir, commandId, sessionId, agentType);
      case KIMI -> importKimiSidechains(buffer, subagentsDir, commandId, sessionId, agentType);
    };
  }

  private List<AgentSessionStore.Stat> importClaudeSidechains(
      ImportBuffer buffer,
      Path subagentsDir,
      String commandId,
      String sessionId,
      AgentType agentType) {
    List<Path> sidechains;
    try (Stream<Path> list = Files.list(subagentsDir)) {
      sidechains =
          list.filter(p -> p.getFileName().toString().matches("agent-.*\\.jsonl"))
              .sorted()
              .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<AgentSessionStore.Stat> stats = new ArrayList<>();
    for (Path sidechain : sidechains) {
      String filename = sidechain.getFileName().toString();
      String agentId = filename.substring("agent-".length(), filename.length() - ".jsonl".length());
      SidechainMeta meta = readSidechainMeta(sidechain, agentId);
      buffer.add(agentMetaLine(agentId, meta), Instant.now());
      StatCollector collector = new StatCollector(agentType);
      importJsonl(buffer, sidechain, collector, null);
      stats.add(collector.toStat(commandId, sessionId, agentId, meta));
    }
    return stats;
  }

  private List<AgentSessionStore.Stat> importKimiSidechains(
      ImportBuffer buffer,
      Path subagentsDir,
      String commandId,
      String sessionId,
      AgentType agentType) {
    List<Path> sidechains;
    try (Stream<Path> list = Files.list(subagentsDir)) {
      sidechains =
          list.filter(Files::isDirectory)
              // The main agent lives under agents/main/; everything else is a sidechain.
              .filter(p -> !"main".equals(p.getFileName().toString()))
              .filter(p -> Files.isRegularFile(p.resolve("wire.jsonl")))
              .sorted()
              .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<AgentSessionStore.Stat> stats = new ArrayList<>();
    for (Path sidechain : sidechains) {
      String agentId = sidechain.getFileName().toString();
      Path wire = sidechain.resolve("wire.jsonl");
      // Kimi has no separate meta file for sidechains.
      SidechainMeta meta = new SidechainMeta(null, null, null);
      buffer.add(agentMetaLine(agentId, meta), Instant.now());
      StatCollector collector = new StatCollector(agentType);
      importJsonl(buffer, wire, collector, kimiNormalizer(agentType, sessionId, agentId));
      stats.add(collector.toStat(commandId, sessionId, agentId, meta));
    }
    return stats;
  }

  /** The sidechain's {@code agentType}/{@code description}/{@code toolUseId} labels. */
  private record SidechainMeta(String agentType, String description, String toolUseId) {}

  private SidechainMeta readSidechainMeta(Path sidechain, String agentId) {
    Path metaFile = sidechain.resolveSibling("agent-" + agentId + ".meta.json");
    if (Files.isRegularFile(metaFile)) {
      try {
        Json parsed = Json.parse(Files.readString(metaFile));
        // The labels are agent-produced free text; clamp them to what the UI will render.
        return new SidechainMeta(
            truncate(parsed.path("agentType").asText(null), 255),
            truncate(parsed.path("description").asText(null), 1024),
            parsed.path("toolUseId").asText(null));
      } catch (IOException e) {
        LOG.log(Level.DEBUG, () -> "Unreadable sidechain meta " + metaFile, e);
      }
    }
    return new SidechainMeta(null, null, null);
  }

  private static String truncate(String value, int maxLength) {
    return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
  }

  private String agentMetaLine(String agentId, SidechainMeta meta) {
    JsonObject node = new JsonObject().put("type", AGENT_META_TYPE).put("agentId", agentId);
    if (meta.agentType() != null) {
      node.put("agentType", meta.agentType());
    }
    if (meta.description() != null) {
      node.put("description", meta.description());
    }
    if (meta.toolUseId() != null) {
      node.put("toolUseId", meta.toolUseId());
    }
    return node.encode();
  }

  /** The line's own {@code timestamp} field when present and parseable, else null. */
  private Instant ownTimestamp(Json node) {
    if (node == null) {
      return null;
    }
    try {
      String timestamp = node.path("timestamp").asText(null);
      return timestamp != null ? Instant.parse(timestamp) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Whether a <em>source</em> transcript line is a conversation turn the operator would count — a
   * {@code user} or {@code assistant} message actually carrying text, not a tool-result/tool-call
   * carrier, a thinking-only line, or a meta line. Counted on the source shape (once per message):
   * Claude's envelope line or Kimi's raw {@code wire.jsonl} message.
   */
  private boolean isConversationTurn(Json node, AgentType agentType) {
    if (node == null || node.isMissing()) {
      return false;
    }
    return switch (agentType) {
      case CLAUDE -> isEnvelopeConversationTurn(node);
      case KIMI -> isKimiRawConversationTurn(node);
    };
  }

  private static boolean isEnvelopeConversationTurn(Json node) {
    String type = node.path("type").asText("");
    if (!"user".equals(type) && !"assistant".equals(type)) {
      return false;
    }
    if (node.path("isMeta").asBoolean(false)) {
      return false;
    }
    Json content = node.path("message").path("content");
    if (content.isTextual()) {
      return !content.asText().isBlank();
    }
    for (Json block : content) {
      if ("text".equals(block.path("type").asText(""))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Kimi's raw {@code wire.jsonl} message: {@code metadata}/{@code config}/{@code session} noise is
   * not a turn; a {@code user}/{@code assistant} message carrying text is.
   */
  private static boolean isKimiRawConversationTurn(Json node) {
    if (node.has("metadata") || node.has("config") || node.has("session")) {
      return false;
    }
    String role = node.path("role").asText("");
    if (!"user".equals(role) && !"assistant".equals(role)) {
      return false;
    }
    Json content = node.path("content");
    if (content.isTextual()) {
      return !content.asText().isBlank();
    }
    if (content.isArray()) {
      for (Json block : content) {
        if ("text".equals(block.path("type").asText(""))
            && !block.path("text").asText("").isBlank()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Replaces the swept sessions' stat rows with this import's aggregation. Keyed by session, not
   * command: a later resume of the same session supersedes the earlier import's counts instead of
   * duplicating them, and a sweep that found no transcript leaves an earlier import's stats in place.
   */
  private void replaceStats(List<AgentSessionStore.Stat> stats) {
    if (stats.isEmpty()) {
      return;
    }
    Set<String> sessionIds = new LinkedHashSet<>();
    for (AgentSessionStore.Stat stat : stats) {
      sessionIds.add(stat.sessionId());
    }
    sessionStore.replace(sessionIds, stats);
  }

  /** Aggregates one transcript's stat row while its lines stream through the import. */
  private class StatCollector {
    private final AgentType agentType;
    private int messageCount;
    private Instant firstTimestamp;

    private StatCollector(AgentType agentType) {
      this.agentType = agentType;
    }

    private void observe(Json node, Instant timestamp) {
      if (firstTimestamp == null && timestamp != null) {
        firstTimestamp = timestamp;
      }
      if (isConversationTurn(node, agentType)) {
        messageCount++;
      }
    }

    private AgentSessionStore.Stat toStat(
        String commandId, String sessionId, String agentId, SidechainMeta meta) {
      return new AgentSessionStore.Stat(
          commandId,
          sessionId,
          agentId,
          meta == null ? null : meta.agentType(),
          meta == null ? null : meta.description(),
          messageCount,
          firstTimestamp);
    }
  }

  /** Accumulates lines and flushes them in batches through the synchronous import path. */
  private class ImportBuffer {
    private static final int FLUSH_AT = 256;

    private final String commandId;
    private final List<CommandLogService.PendingLine> pending = new ArrayList<>(FLUSH_AT);
    private long seq = TRANSCRIPT_SEQ_BASE;
    private long imported;

    private ImportBuffer(String commandId) {
      this.commandId = commandId;
    }

    private void add(String content, Instant timestamp) {
      pending.add(
          new CommandLogService.PendingLine(
              commandId, seq++, LogChannel.TRANSCRIPT, content, timestamp));
      imported++;
      if (pending.size() >= FLUSH_AT) {
        flush();
      }
    }

    private void flush() {
      if (!pending.isEmpty()) {
        commandLogService.importLines(List.copyOf(pending));
        pending.clear();
      }
    }
  }
}
