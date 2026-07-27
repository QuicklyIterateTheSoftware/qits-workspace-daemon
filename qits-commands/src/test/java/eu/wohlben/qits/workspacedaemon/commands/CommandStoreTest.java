package eu.wohlben.qits.workspacedaemon.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The in-memory replacement for {@code CommandRepository} and {@code CommandLogLineRepository}.
 *
 * <p>The retention tests are the ones worth having: they pin the two bounds that the host's tables
 * did not have, and getting either wrong is either a leak that takes the container's agent down
 * with it or a live process that can no longer be terminated.
 */
class CommandStoreTest {

  private static Command finished(String id, Instant endedAt) {
    return Command.running(
            id, CommandKind.TERMINAL, "main", "abc", null, id, "true", false, null, endedAt)
        .finished(CommandStatus.EXITED, 0, endedAt);
  }

  @Test
  void listsMostRecentlyLaunchedFirst() {
    CommandStore store = new CommandStore();
    store.put(finished("older", Instant.parse("2026-07-27T10:00:00Z")));
    store.put(finished("newer", Instant.parse("2026-07-27T11:00:00Z")));

    assertEquals(List.of("newer", "older"), store.listByLaunchedAtDesc().stream().map(Command::id).toList());
  }

  @Test
  void evictsTheOldestFinishedCommandsBeyondTheBound() {
    CommandStore store = new CommandStore(2, 100);
    store.put(finished("a", Instant.parse("2026-07-27T10:00:00Z")));
    store.put(finished("b", Instant.parse("2026-07-27T11:00:00Z")));
    store.put(finished("c", Instant.parse("2026-07-27T12:00:00Z")));

    assertEquals(1, store.evicted());
    assertTrue(store.find("a").isEmpty(), "the oldest finished command should have been evicted");
    assertTrue(store.find("b").isPresent());
    assertTrue(store.find("c").isPresent());
  }

  @Test
  void neverEvictsARunningCommandHoweverOld() {
    // A running command's process is alive and every attach, input and terminate resolves through
    // the store — evicting one would strand a live process with no way to reach it.
    CommandStore store = new CommandStore(1, 100);
    Command live =
        Command.running(
            "live", CommandKind.TERMINAL, "main", "abc", null, "live", "sleep 1", true, null,
            Instant.parse("2026-07-27T09:00:00Z"));
    store.put(live);
    store.put(finished("newer-but-done", Instant.parse("2026-07-27T12:00:00Z")));
    store.put(finished("newest", Instant.parse("2026-07-27T13:00:00Z")));

    assertTrue(store.find("live").isPresent(), "a RUNNING command must survive eviction pressure");
  }

  @Test
  void evictingACommandDropsItsLogToo() {
    CommandStore store = new CommandStore(1, 100);
    store.put(finished("gone", Instant.parse("2026-07-27T10:00:00Z")));
    store.log("gone").append(new CommandLogLine(1, LogChannel.OUTPUT, "x", null, Instant.now()));
    store.put(finished("kept", Instant.parse("2026-07-27T11:00:00Z")));

    assertEquals(0, store.log("gone").size(), "an evicted command's buffer should not linger");
  }

  @Test
  void findsTheHarnessThatDroveASession() {
    CommandStore store = new CommandStore();
    Command command =
        finished("c", Instant.now())
            .withSession(
                new AgentSessionRef("sess-1", AgentSessionSource.PINNED, null, null, Instant.now()));
    store.put(command);

    assertTrue(store.ownsSession("sess-1"));
    assertFalse(store.ownsSession("sess-unknown"));
  }

  @Test
  void aSessionFromAPreviousContainerIsNotOwned() {
    // The fail-closed direction of losing durability: a resume of a session this workspace drove
    // before a recreate is refused rather than allowed.
    CommandStore store = new CommandStore();
    assertFalse(store.ownsSession("sess-from-a-past-container"));
  }
}
