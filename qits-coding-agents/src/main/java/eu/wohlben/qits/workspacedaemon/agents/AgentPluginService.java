package eu.wohlben.qits.workspacedaemon.agents;

import eu.wohlben.qits.workspacedaemon.agents.json.Json;
import eu.wohlben.qits.workspacedaemon.commands.InvalidCommandRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Installs and lists Claude Code plugins on the shared credential volume.
 *
 * <p>Two things changed by moving inside the container. The install still shells out — {@code claude
 * plugin install} is the only supported way to do it — but the <em>listing</em> does not: it was
 * {@code docker exec … cat <mount>/.claude/settings.json}, and a file on a locally mounted volume is
 * just a file. And the workspace ids the host validated and turned into a container name are gone;
 * there is one container and this is it.
 */
public final class AgentPluginService {

  /** The marketplace plugins are installed from. */
  static final String MARKETPLACE = "claude-plugins-official";

  private static final Pattern PLUGIN_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private final ProcessRunner processes;
  private final String claudeMount;
  private final Path workspaceRoot;
  private final AgentDefaults defaults;

  public AgentPluginService(
      ProcessRunner processes, String claudeMount, Path workspaceRoot, AgentDefaults defaults) {
    this.processes = processes;
    this.claudeMount = claudeMount;
    this.workspaceRoot = workspaceRoot;
    this.defaults = defaults;
  }

  /**
   * The plugins recorded in the shared volume's {@code settings.json}. An absent file (nothing ever
   * installed) reads as an empty list, not an error.
   */
  public List<InstalledPluginDto> listInstalled() {
    requireClaude();
    return installed();
  }

  /**
   * Installs {@code pluginId} from the official marketplace onto the shared volume ({@code claude
   * plugin install <id>@claude-plugins-official} with {@code HOME} on the mount). Idempotent — the
   * CLI no-ops an already-installed plugin. Returns the refreshed installed set so the caller can
   * reflect the flip without a second round trip.
   */
  public List<InstalledPluginDto> install(String pluginId) {
    requireClaude();
    if (pluginId == null || !PLUGIN_ID.matcher(pluginId).matches()) {
      throw new InvalidCommandRequestException("Invalid plugin id: " + pluginId);
    }
    ProcessRunner.Result result =
        processes.exec(
            List.of("claude", "plugin", "install", pluginId + "@" + MARKETPLACE),
            workspaceRoot,
            Map.of("HOME", claudeMount),
            TIMEOUT);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "Failed to install plugin " + pluginId + ": " + result.output());
    }
    return installed();
  }

  /**
   * Reads and parses {@code enabledPlugins} out of the shared volume's {@code settings.json}. A
   * local read: the volume is mounted into this container, so there is no process to fork.
   */
  private List<InstalledPluginDto> installed() {
    try {
      return parseEnabledPlugins(Files.readString(settingsPath()));
    } catch (IOException e) {
      // No settings.json on the volume yet (nothing ever installed) — not an error, just empty.
      return List.of();
    }
  }

  /** The shared volume's Claude Code settings file. */
  Path settingsPath() {
    return Path.of(claudeMount, ".claude", "settings.json");
  }

  private void requireClaude() {
    if (defaults.defaultAgentType() != AgentType.CLAUDE) {
      throw new InvalidCommandRequestException("LSP plugins are only supported by Claude Code");
    }
  }

  /**
   * Parses the {@code enabledPlugins} object of a Claude Code {@code settings.json}: each key is a
   * marketplace-qualified plugin id ({@code jdtls-lsp@claude-plugins-official}) and each value a
   * boolean ({@code true} = enabled, {@code false} = installed-but-disabled). An id absent from the
   * object is not installed and produces no entry. Any missing object / malformed JSON reads as an
   * empty list — the file's exact schema is undocumented, so tolerate shape drift rather than 500.
   * Static + package-visible so it can be unit-tested without a volume.
   */
  static List<InstalledPluginDto> parseEnabledPlugins(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    Json enabled = Json.parse(json).path("enabledPlugins");
    if (!enabled.isObject()) {
      return List.of();
    }
    List<InstalledPluginDto> result = new ArrayList<>();
    enabled
        .fields()
        .forEach((key, value) -> result.add(new InstalledPluginDto(key, value.asBoolean(false))));
    return result;
  }
}
