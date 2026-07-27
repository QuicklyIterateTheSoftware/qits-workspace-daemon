package eu.wohlben.qits.workspacedaemon.agents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-session transcript aggregates — how many conversation turns a session holds, when it started,
 * and the same for each of its subagent sidechains. Written by {@link AgentTranscriptService} at the
 * end of a sweep, read by {@link AgentSessionQueryService} to build the session tree.
 *
 * <p>On the host this was the {@code agent_session_stat} table (Flyway V30, widened by V39). Here it
 * is a map, and it dies with the container — the deliberate scope of moving agents inside. What that
 * costs is worth stating plainly: after a container recreate the Agents view shows only sessions
 * this container drove, even though the transcripts themselves are still on the shared claude volume
 * and could be re-read. Recovering them would mean walking the volume rather than the command list,
 * which is a feature, not part of this move.
 *
 * <p>Keyed by session rather than by command, exactly as the table's index was: a later resume of the
 * same session supersedes the earlier import's counts instead of duplicating them.
 */
public final class AgentSessionStore {

  /**
   * The most sessions retained. Chosen to exceed what is reachable: {@code CommandStore} holds at
   * most {@link eu.wohlben.qits.workspacedaemon.commands.CommandStore#MAX_COMMANDS} commands and the
   * session tree only ever shows sessions reachable from one of them, so eviction here removes rows
   * nothing can still ask for. It is a heap backstop, not a retention policy.
   */
  public static final int MAX_SESSIONS = 500;

  /**
   * One aggregate row. {@code agentId} is null for the session's own row and set for a subagent
   * sidechain; {@code agentType} and {@code description} are the sidechain's labels, absent for the
   * session row.
   */
  public record Stat(
      String commandId,
      String sessionId,
      String agentId,
      String agentType,
      String description,
      int messageCount,
      Instant firstTimestamp) {}

  private final int maxSessions;

  /** sessionId → that session's rows (its own, plus one per sidechain). Insertion-ordered. */
  private final LinkedHashMap<String, List<Stat>> bySession = new LinkedHashMap<>();

  public AgentSessionStore() {
    this(MAX_SESSIONS);
  }

  public AgentSessionStore(int maxSessions) {
    this.maxSessions = maxSessions;
  }

  /**
   * Replaces every row of {@code sessionIds} with {@code stats}. The unit of replacement is the
   * session, so a sweep that re-imported one session does not disturb another's rows, and a sweep
   * that found no transcript for a session leaves an earlier import's counts in place.
   */
  public synchronized void replace(Collection<String> sessionIds, List<Stat> stats) {
    for (String sessionId : sessionIds) {
      bySession.remove(sessionId);
    }
    for (Stat stat : stats) {
      bySession.computeIfAbsent(stat.sessionId(), key -> new ArrayList<>()).add(stat);
    }
    evictOldest();
  }

  /** Every row belonging to any of {@code sessionIds}, in no particular order. */
  public synchronized List<Stat> findBySessionIds(Collection<String> sessionIds) {
    List<Stat> out = new ArrayList<>();
    for (String sessionId : sessionIds) {
      List<Stat> rows = bySession.get(sessionId);
      if (rows != null) {
        out.addAll(rows);
      }
    }
    return out;
  }

  /** How many sessions currently hold rows — the test seam on the bound. */
  public synchronized int sessionCount() {
    return bySession.size();
  }

  private void evictOldest() {
    while (bySession.size() > maxSessions) {
      Map.Entry<String, List<Stat>> eldest = bySession.entrySet().iterator().next();
      bySession.remove(eldest.getKey());
    }
  }
}
