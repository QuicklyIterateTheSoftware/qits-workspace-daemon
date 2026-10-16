package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentAuthStatus;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.HarnessCapabilities;
import eu.wohlben.qits.agents.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The boot report's sign-in is read live, at most once per TTL, and afresh after invalidate. */
class HarnessAuthOverlayTest {

  private static final Duration TTL = Duration.ofSeconds(10);

  private static final String SIGNED_IN = "Signed in on the shared credential volume.";

  private static final String KIMI_SIGNED_OUT =
      "Nobody has signed in on the shared credential volume. Open the KIMI sign-in terminal to"
          + " complete it once for every container on this volume.";

  @TempDir Path workspace;
  @TempDir Path claudeMount;

  private final AtomicInteger probes = new AtomicInteger();
  private final AtomicLong now = new AtomicLong();

  /**
   * {@code claude auth status} always answers signed in; the kimi probe looks for the credential
   * file on the real temporary volume, the way the library's {@code test -f} does — so writing that
   * file is what a person signing in looks like here.
   */
  private final ProcessRunner processes =
      (command, cwd, env, timeout) -> {
        probes.incrementAndGet();
        if (command.get(0).equals("claude")) {
          return new ProcessRunner.Result(0, "{\"loggedIn\":true}", "", false);
        }
        Path home = Path.of(env.get("KIMI_CODE_HOME"));
        boolean present =
            Files.isRegularFile(home.resolve("credentials"))
                || Files.isRegularFile(home.resolve("credentials/kimi-code.json"));
        return new ProcessRunner.Result(present ? 0 : 1, "", "", false);
      };

  /** What the boot probe left: Claude signed in, Kimi not, and Kimi's probe failed. */
  private List<HarnessCapabilities> boot;

  @BeforeEach
  void bootReport() {
    boot =
        List.of(
            new HarnessCapabilities(
                AgentType.CLAUDE,
                "2.1.0",
                HarnessCapabilities.CLAUDE_MODELS,
                false,
                true,
                HarnessCapabilities.CLAUDE_EFFORT_LEVELS,
                true,
                SIGNED_IN,
                false,
                ""),
            HarnessCapabilities.shipped(AgentType.KIMI, "kimi could not be probed: gone")
                .withAuth(false, KIMI_SIGNED_OUT));
  }

  private HarnessAuthOverlay overlay(Path workspaceRoot) {
    AgentAuthStatus auth = new AgentAuthStatus(processes, claudeMount.toString(), workspaceRoot);
    return new HarnessAuthOverlay(() -> boot, auth, TTL, now::get);
  }

  private void signKimiIn() throws Exception {
    Path credentials = Files.createDirectories(claudeMount.resolve(".kimi-code/credentials"));
    Files.writeString(credentials.resolve("kimi-code.json"), "{}");
  }

  @Test
  void aSignInAfterBootShowsOnTheNextReadOnceInvalidated() throws Exception {
    HarnessAuthOverlay overlay = overlay(workspace);
    assertFalse(overlay.get().get(1).authenticated(), "nobody has signed kimi in yet");

    signKimiIn();
    overlay.invalidate();
    List<HarnessCapabilities> after = overlay.get();

    HarnessCapabilities kimi = after.get(1);
    assertTrue(kimi.authenticated());
    assertEquals(SIGNED_IN, kimi.authDetail());
    // Only sign-in is re-read; a probe that failed at boot still says so.
    assertEquals(boot.get(1).withAuth(true, SIGNED_IN), kimi);
    assertEquals(boot.get(0), after.get(0), "claude was already signed in, and still is");
  }

  @Test
  void aSignedOutHarnessCarriesTheLibrarysOwnSentence() {
    HarnessCapabilities kimi = overlay(workspace).get().get(1);

    assertFalse(kimi.authenticated());
    assertEquals(KIMI_SIGNED_OUT, kimi.authDetail());
  }

  @Test
  void withinTheTtlNoProbeRunsAndOnceItExpiresOneDoes() throws Exception {
    HarnessAuthOverlay overlay = overlay(workspace);
    List<HarnessCapabilities> first = overlay.get();
    assertEquals(2, probes.get(), "one probe per harness");

    signKimiIn();
    now.addAndGet(TTL.toNanos() - 1);
    assertSame(first, overlay.get(), "inside the TTL the cached answer is served");
    assertEquals(2, probes.get(), "and nothing is spawned for it");

    now.addAndGet(1);
    assertTrue(overlay.get().get(1).authenticated(), "past the TTL the volume is read again");
    assertEquals(4, probes.get());
  }

  @Test
  void invalidateMakesTheNextReadProbeEvenInsideTheTtl() {
    HarnessAuthOverlay overlay = overlay(workspace);
    overlay.get();

    overlay.invalidate();
    overlay.get();

    assertEquals(4, probes.get());
  }

  @Test
  void aMissingCheckoutReadsAsNotSignedInAndSaysWhy() {
    Path missing = workspace.resolve("not-provisioned");

    List<HarnessCapabilities> answer = overlay(missing).get();

    for (HarnessCapabilities entry : answer) {
      assertFalse(entry.authenticated(), entry.harness() + " cannot be checked, so is not in");
      assertEquals(
          "Could not be checked: No checkout at " + missing + " — the self-provision did not"
              + " complete.",
          entry.authDetail());
    }
    assertEquals(0, probes.get(), "nothing is spawned without a checkout to spawn it in");
  }

  @Test
  void anEmptyBootReportPassesThroughAndProbesNothing() {
    boot = List.of();

    assertEquals(List.of(), overlay(workspace).get());
    assertEquals(0, probes.get());
  }

  @Test
  void aBootReportThatLandsLaterIsReadAtOnceRatherThanAfterTheTtl() {
    AtomicReference<List<HarnessCapabilities>> landed = new AtomicReference<>(List.of());
    HarnessAuthOverlay overlay =
        new HarnessAuthOverlay(
            landed::get,
            new AgentAuthStatus(processes, claudeMount.toString(), workspace),
            TTL,
            now::get);
    assertEquals(List.of(), overlay.get());

    landed.set(boot);

    assertEquals(2, overlay.get().size());
  }
}
