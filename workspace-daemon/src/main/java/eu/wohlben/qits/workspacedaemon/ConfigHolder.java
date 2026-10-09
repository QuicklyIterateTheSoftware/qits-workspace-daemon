package eu.wohlben.qits.workspacedaemon;

import java.util.List;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The checkout config the daemon answers from, and the one place it is (re-)read.
 *
 * <p>Framework-free so the reload semantics are testable without CDI: {@link ControlSocket} owns
 * one, reads it at boot right after the self-clone, and reloads it on {@code SIGHUP}. Every
 * consumer reads {@link #state()} / {@link #config()} through a supplier, so a reload is visible to
 * the next action resolution, bootstrap listing, framework hint and config view without restarting
 * anything.
 *
 * <p><b>A broken edit keeps the last good config.</b> An agent saving a half-written file and
 * signalling would otherwise wipe every declared action mid-session; instead the previous {@code
 * config}/{@code configJson} stay and only the {@code warning} changes, so the config view says
 * what is wrong while everything keeps working. With no good read held yet (boot, or a file that
 * has been broken since boot) the degraded read is taken as is — the empty config plus a warning,
 * the boot semantics {@link ConfigReader} documents. "Good" means a read that returned no warning,
 * which includes the empty config of an absent file; the initial placeholder is not a read and so
 * is never good.
 *
 * <p><b>Reload never runs the bootstrap chain.</b> This class has no bootstrap dependency at all:
 * the chain is a fresh-clone boot step ({@code ControlSocket.runBootstrapOnBoot}) or an explicit
 * {@code RunBootstrap}, and re-running install steps because a config file was saved would be a
 * surprise nobody asked for.
 */
final class ConfigHolder {

  private static final Logger LOG = Logger.getLogger(ConfigHolder.class);

  private final Supplier<ConfigReader.State> reader;

  /**
   * Initialized to the empty config so a describe that races ahead of provisioning gets a benign
   * empty answer rather than null.
   */
  private volatile ConfigReader.State state =
      new ConfigReader.State(DaemonQitsConfig.EMPTY, ConfigJson.empty(), null);

  /** Whether {@link #state}'s config came from a clean read. Guarded by {@code this}. */
  private boolean holdsGoodRead;

  ConfigHolder(Supplier<ConfigReader.State> reader) {
    this.reader = reader;
  }

  /** The checkout's own config: {@link ConfigReader#read()}. */
  static ConfigHolder forCheckout() {
    return new ConfigHolder(ConfigReader::read);
  }

  ConfigReader.State state() {
    return state;
  }

  DaemonQitsConfig config() {
    return state.config();
  }

  /**
   * Read the config and swap it in, keeping the last good config when the new read degraded.
   * Synchronized so two overlapping signals cannot interleave their read and swap.
   */
  synchronized ConfigReader.State reload() {
    ConfigReader.State before = state;
    ConfigReader.State read = reader.get();
    ConfigReader.State after;
    boolean kept = read.warning() != null && holdsGoodRead;
    if (kept) {
      after = new ConfigReader.State(before.config(), before.configJson(), read.warning());
    } else {
      after = read;
      holdsGoodRead = read.warning() == null;
    }
    state = after;
    LOG.infof(
        "Checkout config read: actions %d -> %d, bootstrap steps %d -> %d, frameworks %d -> %d%s",
        size(before.config().actions()),
        size(after.config().actions()),
        size(before.config().bootstrap()),
        size(after.config().bootstrap()),
        size(before.config().frameworks()),
        size(after.config().frameworks()),
        after.warning() == null
            ? ""
            : (kept ? " (kept the last good config)" : "") + ", warning: " + after.warning());
    return after;
  }

  private static int size(List<?> list) {
    return list == null ? 0 : list.size();
  }
}
