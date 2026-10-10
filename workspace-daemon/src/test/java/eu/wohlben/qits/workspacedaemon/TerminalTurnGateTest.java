package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Turns for an interactive harness wait for its SessionStart, and then go in order. */
class TerminalTurnGateTest {

  private final List<String> typed = new CopyOnWriteArrayList<>();
  private volatile boolean running = true;
  private TerminalTurnGate gate;

  private TerminalTurnGate gate(Duration timeout) {
    gate =
        new TerminalTurnGate(
            (commandId, text) -> {
              if (!running) {
                return false;
              }
              typed.add(commandId + " " + text);
              return true;
            },
            timeout);
    return gate;
  }

  @AfterEach
  void close() {
    if (gate != null) {
      gate.close();
    }
  }

  @Test
  void turnsAreHeldUntilTheHarnessStartsAndThenTypedInOrder() throws Exception {
    TerminalTurnGate gate = gate(Duration.ofMinutes(1));

    assertTrue(gate.type("c1", "first"));
    assertTrue(gate.type("c1", "second"));
    assertTrue(typed.isEmpty(), "nothing is typed into a harness that has not started");

    gate.started("c1");

    awaitTyped(2);
    assertEquals(List.of("c1 first", "c1 second"), typed);
  }

  @Test
  void aStartedHarnessGetsItsTurnAtOnce() throws Exception {
    TerminalTurnGate gate = gate(Duration.ofMinutes(1));
    gate.started("c1");
    awaitStarted(gate, "c1");

    assertTrue(gate.type("c1", "go on"));

    assertEquals(List.of("c1 go on"), typed);
  }

  @Test
  void aHarnessThatNeverSaysItStartedGetsItsTurnsAfterTheTimeout() throws Exception {
    TerminalTurnGate gate = gate(Duration.ofMillis(50));

    gate.type("c1", "anyway");

    awaitTyped(1);
    assertEquals(List.of("c1 anyway"), typed);
    assertTrue(gate.isStarted("c1"), "later turns do not wait again");
  }

  @Test
  void oneHarnessStartingReleasesOnlyItsOwnTurns() throws Exception {
    TerminalTurnGate gate = gate(Duration.ofMinutes(1));
    gate.type("c1", "mine");
    gate.type("c2", "yours");

    gate.started("c1");

    awaitTyped(1);
    Thread.sleep(50);
    assertEquals(List.of("c1 mine"), typed);
  }

  @Test
  void aHarnessThatEndsBeforeItStartsDropsItsHeldTurns() throws Exception {
    TerminalTurnGate gate = gate(Duration.ofMillis(50));
    gate.type("c1", "lost");

    gate.forget("c1");

    Thread.sleep(150);
    assertTrue(typed.isEmpty());
    assertFalse(gate.isStarted("c1"));
  }

  @Test
  void aStartedHarnessThatStoppedRefusesTheTurn() throws Exception {
    TerminalTurnGate gate = gate(Duration.ofMinutes(1));
    gate.started("c1");
    awaitStarted(gate, "c1");
    running = false;

    assertFalse(gate.type("c1", "too late"));
  }

  private void awaitTyped(int count) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (typed.size() < count) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("typed only " + typed);
      }
      Thread.sleep(5);
    }
  }

  private static void awaitStarted(TerminalTurnGate gate, String commandId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!gate.isStarted(commandId)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(commandId + " never started");
      }
      Thread.sleep(5);
    }
  }
}
