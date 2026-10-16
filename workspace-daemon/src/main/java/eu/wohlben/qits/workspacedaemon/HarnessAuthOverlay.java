package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.AgentAuthStatus;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.HarnessCapabilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The boot-time harness capability report with each harness's sign-in read live (qits-1269).
 *
 * <p>Versions, models and effort levels belong to the binary in the image, so they are probed once
 * at boot and never change. Sign-in does not: a person signs a harness in on the shared credential
 * volume a minute after the boot report was taken, and a report that kept saying "nobody has signed
 * in" until the container restarted sent the CLI's {@code sign-in status} and the Terminal tab's
 * notice on asking somebody to do what they had just done. So every read re-asks {@link
 * AgentAuthStatus} — the same probe a launch is gated on — and folds the answer over the boot entry
 * with {@link HarnessCapabilities#withAuth}, touching no other field. That includes an entry whose
 * probe failed at boot: the library folds auth over its shipped fallback too.
 *
 * <p><b>The detail sentences are the library's, word for word</b> ({@code
 * HarnessCapabilityService.authDetail}), so a re-checked entry is indistinguishable from one the
 * boot report would have produced at that moment. Unlike the library this asks once per harness and
 * derives both values from that one answer, rather than spawning the probe twice.
 *
 * <p><b>Cached for {@link #DEFAULT_TTL}</b>, because a Claude re-check spawns {@code claude auth
 * status} and the host and the landing app both poll this route. {@link #invalidate()} drops the
 * cache — wired to the commands lifecycle, so the sign-in terminal's exit makes the very next read
 * live. A boot list that changed (by identity: the boot probe landing) is recomputed at once; an
 * empty one, the "nothing reported yet" answer, passes through untouched and probes nothing.
 *
 * <p>Thread-safe without a lock: two concurrent reads past the TTL may both probe, which costs a
 * spawn and is cheaper than making every reader wait on one.
 */
final class HarnessAuthOverlay implements Supplier<List<HarnessCapabilities>> {

  /** How long a re-checked answer is served before the next read probes again. */
  static final Duration DEFAULT_TTL = Duration.ofSeconds(10);

  private final Supplier<List<HarnessCapabilities>> boot;
  private final AgentAuthStatus authStatus;
  private final long ttlNanos;
  private final LongSupplier nanos;

  /** The last answer and what it was computed from; null when there is none or it was dropped. */
  private volatile Cached cached;

  /**
   * Bumped by {@link #invalidate()}. A probe that was already running when a sign-in ended stores
   * its answer only if no invalidation happened meanwhile, so a pre-sign-in answer cannot be put
   * back over the drop and served for a whole TTL.
   */
  private final AtomicLong generation = new AtomicLong();

  private record Cached(
      List<HarnessCapabilities> boot, List<HarnessCapabilities> answer, long atNanos) {}

  HarnessAuthOverlay(Supplier<List<HarnessCapabilities>> boot, AgentAuthStatus authStatus) {
    this(boot, authStatus, DEFAULT_TTL, System::nanoTime);
  }

  HarnessAuthOverlay(
      Supplier<List<HarnessCapabilities>> boot,
      AgentAuthStatus authStatus,
      Duration ttl,
      LongSupplier nanos) {
    this.boot = boot;
    this.authStatus = authStatus;
    this.ttlNanos = ttl.toNanos();
    this.nanos = nanos;
  }

  @Override
  public List<HarnessCapabilities> get() {
    List<HarnessCapabilities> reported = boot.get();
    if (reported == null || reported.isEmpty()) {
      return List.of();
    }
    long now = nanos.getAsLong();
    Cached last = cached;
    if (last != null && last.boot() == reported && now - last.atNanos() < ttlNanos) {
      return last.answer();
    }
    long gen = generation.get();
    List<HarnessCapabilities> answer = new ArrayList<>(reported.size());
    for (HarnessCapabilities entry : reported) {
      answer.add(recheck(entry));
    }
    List<HarnessCapabilities> fresh = List.copyOf(answer);
    if (generation.get() == gen) {
      cached = new Cached(reported, fresh, now);
    }
    return fresh;
  }

  /** Drop the cached answer, so the next read asks the credential volume again. */
  void invalidate() {
    generation.incrementAndGet();
    cached = null;
  }

  /**
   * One entry with the credential volume's answer folded in. Fail-closed like the library: anything
   * that stops the probe answering reads as not signed in, and a missing checkout says so rather
   * than sending an operator to a login terminal for a failed self-provision.
   */
  private HarnessCapabilities recheck(HarnessCapabilities entry) {
    AgentType harness = entry.harness();
    if (harness == null) {
      return entry;
    }
    if (authStatus == null) {
      return entry.withAuth(false, "Nobody has checked whether this harness is signed in.");
    }
    try {
      return authStatus.isLoggedIn(harness)
          ? entry.withAuth(true, "Signed in on the shared credential volume.")
          : entry.withAuth(
              false,
              "Nobody has signed in on the shared credential volume. Open the "
                  + harness.name()
                  + " sign-in terminal to complete it once for every container on this volume.");
    } catch (RuntimeException e) {
      // Including CheckoutUnavailableException, whose message names the missing checkout.
      return entry.withAuth(false, "Could not be checked: " + e.getMessage());
    }
  }
}
