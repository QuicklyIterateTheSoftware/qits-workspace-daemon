package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ControlSocket#dialDelayMs}, the schedule the dial-home retries on (qits-2294). Asserted as
 * a pure function because waiting the schedule out is exactly the half-minute the host will not
 * give a new container; {@code ControlSocketDialHomeTest} proves the daemon actually dials on it.
 */
class ControlSocketDialScheduleTest {

  private static final long MAX_BACKOFF_MS = 30_000;

  /** The host's connect window for a freshly started container, in qits-workspaces. */
  private static final long HOST_CONNECT_WINDOW_MS = 30_000;

  @Test
  void beforeTheFirstSocketNoWaitBetweenDialsExceedsTwoSeconds() {
    for (int attempt = 0; attempt <= 20; attempt++) {
      long delay = ControlSocket.dialDelayMs(attempt, false, MAX_BACKOFF_MS);
      assertTrue(delay <= 2_000, "attempt " + attempt + " waits " + delay + " ms");
      assertTrue(delay > 0, "attempt " + attempt + " must still wait, not spin");
    }
  }

  @Test
  void beforeTheFirstSocketTheScheduleStartsShortAndDoublesUpToTheCap() {
    assertEquals(500, ControlSocket.dialDelayMs(0, false, MAX_BACKOFF_MS));
    assertEquals(1_000, ControlSocket.dialDelayMs(1, false, MAX_BACKOFF_MS));
    assertEquals(2_000, ControlSocket.dialDelayMs(2, false, MAX_BACKOFF_MS));
    assertEquals(2_000, ControlSocket.dialDelayMs(3, false, MAX_BACKOFF_MS));
  }

  @Test
  void theSixthBootDialGoesOutWellInsideTheHostsWindow() {
    // Dial n+1 goes out after the waits that followed dials 0..n-1. The schedule this replaced put
    // the 6th at 15.5 s and the 7th past the window.
    long sixth = 0;
    for (int attempt = 0; attempt < 5; attempt++) {
      sixth += ControlSocket.dialDelayMs(attempt, false, MAX_BACKOFF_MS);
    }
    assertEquals(7_500, sixth);
    assertTrue(sixth < 15_000);

    int dialsInWindow = 1;
    long at = 0;
    for (int attempt = 0; ; attempt++) {
      at += ControlSocket.dialDelayMs(attempt, false, MAX_BACKOFF_MS);
      if (at >= HOST_CONNECT_WINDOW_MS) {
        break;
      }
      dialsInWindow++;
    }
    assertTrue(dialsInWindow >= 12, dialsInWindow + " dials fit the window");
  }

  @Test
  void afterASocketHasExistedReconnectsKeepTheOldScheduleUpToTheMaxBackoff() {
    long[] expected = {500, 1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000};
    for (int attempt = 0; attempt < expected.length; attempt++) {
      assertEquals(
          expected[attempt],
          ControlSocket.dialDelayMs(attempt, true, MAX_BACKOFF_MS),
          "attempt " + attempt);
    }
    assertEquals(30_000, ControlSocket.dialDelayMs(20, true, MAX_BACKOFF_MS));
  }

  @Test
  void aConfiguredMaxBackoffBelowTheBootCapStillBindsAfterTheFirstSocket() {
    assertEquals(1_000, ControlSocket.dialDelayMs(6, true, 1_000));
  }
}
