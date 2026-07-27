package eu.wohlben.qits.workspacedaemon.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The in-memory replacement for {@code agent_session_stat}. */
class AgentSessionStoreTest {

  private static final Instant T0 = Instant.parse("2026-07-27T08:00:00Z");

  private static AgentSessionStore.Stat stat(String sessionId, String agentId, int count) {
    return new AgentSessionStore.Stat("cmd", sessionId, agentId, null, null, count, T0);
  }

  @Test
  void replaceSupersedesASessionsEarlierRows() {
    AgentSessionStore store = new AgentSessionStore();
    store.replace(List.of("s1"), List.of(stat("s1", null, 3)));
    store.replace(List.of("s1"), List.of(stat("s1", null, 9)));

    List<AgentSessionStore.Stat> rows = store.findBySessionIds(List.of("s1"));
    assertEquals(1, rows.size(), "a re-sweep of one session must not accumulate");
    assertEquals(9, rows.get(0).messageCount());
  }

  @Test
  void replacingOneSessionLeavesAnotherAlone() {
    AgentSessionStore store = new AgentSessionStore();
    store.replace(List.of("s1"), List.of(stat("s1", null, 3)));
    store.replace(List.of("s2"), List.of(stat("s2", null, 5)));
    store.replace(List.of("s2"), List.of(stat("s2", null, 6)));

    assertEquals(3, store.findBySessionIds(List.of("s1")).get(0).messageCount());
    assertEquals(6, store.findBySessionIds(List.of("s2")).get(0).messageCount());
  }

  @Test
  void aSessionAndItsSidechainsAreOneReplacementUnit() {
    AgentSessionStore store = new AgentSessionStore();
    store.replace(
        List.of("s1"), List.of(stat("s1", null, 4), stat("s1", "a", 1), stat("s1", "b", 2)));

    assertEquals(3, store.findBySessionIds(List.of("s1")).size());

    store.replace(List.of("s1"), List.of(stat("s1", null, 4)));

    assertEquals(
        1,
        store.findBySessionIds(List.of("s1")).size(),
        "a sweep that found no sidechains this time drops the old ones");
  }

  @Test
  void unknownSessionsSimplyHaveNoRows() {
    AgentSessionStore store = new AgentSessionStore();

    assertTrue(store.findBySessionIds(List.of("never-seen")).isEmpty());
  }

  @Test
  void theBoundEvictsOldestSessionsFirst() {
    AgentSessionStore store = new AgentSessionStore(2);
    store.replace(List.of("s1"), List.of(stat("s1", null, 1)));
    store.replace(List.of("s2"), List.of(stat("s2", null, 2)));
    store.replace(List.of("s3"), List.of(stat("s3", null, 3)));

    assertEquals(2, store.sessionCount());
    assertTrue(store.findBySessionIds(List.of("s1")).isEmpty(), "the eldest went");
    assertEquals(2, store.findBySessionIds(List.of("s2")).get(0).messageCount());
    assertEquals(3, store.findBySessionIds(List.of("s3")).get(0).messageCount());
  }

  @Test
  void aReplacementThatEmptiesASessionDoesNotLeaveAnEmptyBucket() {
    AgentSessionStore store = new AgentSessionStore();
    store.replace(List.of("s1"), List.of(stat("s1", null, 1)));
    store.replace(List.of("s1"), List.of());

    assertEquals(0, store.sessionCount());
    assertTrue(store.findBySessionIds(List.of("s1")).isEmpty());
  }
}
