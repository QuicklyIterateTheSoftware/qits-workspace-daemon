package eu.wohlben.qits.workspacedaemon;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Reads the branch's checkout's qits config — {@code /workspace/.config/qits/repository.yml},
 * falling back to the legacy {@code /workspace/.qits-config.yml} — and parses it into the
 * wire-ready {@link State} the daemon holds and answers {@link
 * eu.wohlben.qits.workspacedaemon.protocol.DescribeConfig} from (docs/epics/qits-workspace-daemon/
 * Part 2). "Degrade loudly, never block": an absent/blank file yields the empty config with no
 * warning; an unreadable or structurally invalid file yields the empty config plus a warning. The
 * daemon runs this once, right after its self-clone completes, so the config is the workspace's
 * <em>branch's</em> config — what the host's {@code mainBranch}-only read never could express.
 */
public final class ConfigReader {

  private static final File CONFIG_FILE = new File("/workspace/.config/qits/repository.yml");
  private static final File LEGACY_CONFIG_FILE = new File("/workspace/.qits-config.yml");

  /**
   * The parsed config, held three ways: the framework-free {@link DaemonQitsConfig} tree the daemon
   * runs bootstrap/services from in-container (parts 3/4), the same tree as {@code
   * QitsConfig}-shaped JSON for {@link eu.wohlben.qits.workspacedaemon.protocol.ConfigView}
   * replies, and a non-null {@code warning} when the read degraded. A degraded read yields {@link
   * DaemonQitsConfig#EMPTY} + empty JSON, so an empty chain and an empty view stay consistent.
   */
  public record State(DaemonQitsConfig config, String configJson, String warning) {}

  private ConfigReader() {}

  /**
   * Read + parse the checkout's config file: {@code /workspace/.config/qits/repository.yml} if
   * present, else the legacy {@code /workspace/.qits-config.yml}.
   */
  public static State read() {
    return read(CONFIG_FILE, LEGACY_CONFIG_FILE);
  }

  static State read(File file, File legacyFile) {
    return read(file.isFile() ? file : legacyFile);
  }

  static State read(File file) {
    String content;
    try {
      if (!file.isFile()) {
        return new State(DaemonQitsConfig.EMPTY, ConfigJson.empty(), null);
      }
      content = Files.readString(file.toPath());
    } catch (IOException e) {
      return new State(
          DaemonQitsConfig.EMPTY,
          ConfigJson.empty(),
          "could not read " + file.getName() + ": " + e.getMessage());
    }
    try {
      DaemonQitsConfig config = ConfigParser.parse(content);
      return new State(config, ConfigJson.toJson(config), null);
    } catch (ConfigParser.ConfigException e) {
      return new State(DaemonQitsConfig.EMPTY, ConfigJson.empty(), e.getMessage());
    }
  }
}
