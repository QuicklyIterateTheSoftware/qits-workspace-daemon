package eu.wohlben.qits.workspacedaemon.agents;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Kimi Code harness — a {@link CodingAgent} that renders {@code kimi} command lines. Interactive
 * launches spin up a throwaway {@code KIMI_CODE_HOME} (a symlink farm back to the shared volume for
 * credentials/sessions, plus a launch-local {@code mcp.json} and, when session reporting is on, a
 * launch-local {@code config.toml} carrying the SessionStart report hook) and then run {@code kimi}
 * as the script's last command — deliberately not {@code exec}, which would replace the shell and
 * keep the farm's EXIT trap from ever firing. A one-off run uses {@code kimi -p '…' --output-format
 * stream-json}. Kimi ids are {@code session_<uuid>} rather than canonical UUIDs, and fresh sessions
 * cannot be pinned at launch — the daemon learns the id from the SessionStart hook.
 *
 * <p>Native chat rides the ACP JSON-RPC stdio protocol: {@link #chat()} renders {@code exec kimi
 * acp}, driven by an in-JVM ACP client ({@code eu.wohlben.qits.workspacedaemon.agents.acp}).
 */
public class KimiCodeAgent extends CodingAgent {

  /**
   * Kimi session ids are {@code session_<uuid>} today, but opaque on the ACP transport — accept any
   * {@code session_}-prefixed path-safe slug (a strict superset), so an id that was reported can also
   * be resumed.
   *
   * <p>The commands module guards the same ids on the way back in, with {@code
   * CommandService.KIMI_SESSION_PATTERN} = {@code [A-Za-z0-9_-]{1,64}} — no required prefix, but a
   * shorter cap. The two windows are <em>not</em> identical: this one admits up to 136 characters,
   * that one 64. Every id either harness actually mints is {@code session_} plus a UUID, 44
   * characters, so they agree in practice and have never disagreed in the field. Do not widen either
   * side without checking the other; a longer id would render into a launch here and then be refused
   * when the SessionStart hook reported it, which surfaces as a session that silently never gains
   * lineage.
   */
  private static final String KIMI_SESSION_PATTERN = "session_[A-Za-z0-9_-]{1,128}";

  @Override
  protected boolean isSessionIdValid(String value) {
    if (value == null) {
      return true;
    }
    return value.matches(KIMI_SESSION_PATTERN);
  }

  @Override
  protected void validateSessionConfiguration() {
    if (forkRequested) {
      throw new IllegalStateException("Kimi Code does not support fork");
    }
    super.validateSessionConfiguration();
  }

  @Override
  public LaunchSpec start() {
    validateSessionConfiguration();
    StringBuilder script = new StringBuilder();
    appendKimiHomePrelude(script);
    appendMcpConfig(script);
    appendSessionReportHook(script);
    StringBuilder command = new StringBuilder("kimi");
    if (skipPermissions) {
      command.append(" --yolo");
    }
    if (resumeSessionId != null) {
      command.append(" -S ").append(resumeSessionId);
    }
    if (model != null && !model.isBlank()) {
      command.append(" -m ").append(shellQuote(model));
    }
    if (initialContext != null && !initialContext.isBlank()) {
      command.append(' ').append(shellQuote(initialContext));
    }
    script.append('\n').append(command);
    return new LaunchSpec(script.toString(), true, environment);
  }

  @Override
  public LaunchSpec run(String prompt) {
    validateSessionConfiguration();
    StringBuilder script = new StringBuilder();
    appendKimiHomePrelude(script);
    appendMcpConfig(script);
    appendSessionReportHook(script);
    StringBuilder command = new StringBuilder("kimi -p ").append(shellQuote(prompt));
    if (!plainTextOutput) {
      command.append(" --output-format stream-json");
    }
    if (model != null && !model.isBlank()) {
      command.append(" -m ").append(shellQuote(model));
    }
    script.append('\n').append(command);
    return new LaunchSpec(script.toString(), false, environment);
  }

  /**
   * Kimi chat rides the ACP JSON-RPC stdio protocol: an in-JVM ACP client (see {@code
   * eu.wohlben.qits.workspacedaemon.agents.acp}) drives these pipes, normalizing {@code
   * session/update} notifications into the shared event envelope. Unlike the interactive/autonomous
   * shapes there is <strong>no</strong> per-launch {@code KIMI_CODE_HOME} symlink farm and
   * <strong>no</strong> {@code mcp.json} — the scoped MCP servers ride the ACP {@code session/new},
   * and credentials/sessions already live on the real volume home the container's {@code
   * KIMI_CODE_HOME} points at. A plain {@code exec} (no trap to fire) is right here; the process is
   * long-lived and managed exactly like Claude's stream-json chat.
   */
  @Override
  public LaunchSpec chat() {
    validateSessionConfiguration();
    return new LaunchSpec("exec kimi acp", false, environment);
  }

  /**
   * Renders the per-launch {@code KIMI_CODE_HOME} symlink farm. The container arrives with {@code
   * KIMI_CODE_HOME} pointing at the shared volume ({@code /claude-home/.kimi-code}); we capture that,
   * repoint {@code KIMI_CODE_HOME} at a throwaway dir, and symlink credentials, config, hooks, and
   * the session store back to the volume. The launch-local {@code mcp.json} is written into the
   * throwaway dir so concurrent launches never clobber each other's MCP scope.
   *
   * <p>When session reporting is on, {@code config.toml} is deliberately left out of the farm —
   * {@link #appendSessionReportHook} writes a launch-local copy instead, because the report hook's
   * URL is per-command.
   *
   * <p>The prelude is intentionally self-contained: it makes no assumption about what already exists
   * on the volume beyond the container-set {@code KIMI_CODE_HOME} env var.
   */
  private void appendKimiHomePrelude(StringBuilder script) {
    String farmEntries =
        sessionReportingUrl == null
            ? "config.toml tui.toml credentials oauth device_id"
            : "tui.toml credentials oauth device_id";
    script.append(
        "QITS_KIMI_HOME=\"$KIMI_CODE_HOME\"\n"
            + "export KIMI_CODE_HOME=\"$(mktemp -d /tmp/qits-kimi-XXXXXX)\"\n"
            + "trap 'rm -rf \"$KIMI_CODE_HOME\"' EXIT\n"
            + "for e in "
            + farmEntries
            + "; do\n"
            + "  [ -e \"$QITS_KIMI_HOME/$e\" ] && ln -s \"$QITS_KIMI_HOME/$e\" \"$KIMI_CODE_HOME/$e\"\n"
            + "done\n"
            + "mkdir -p \"$QITS_KIMI_HOME/sessions\"\n"
            + "ln -s \"$QITS_KIMI_HOME/sessions\" \"$KIMI_CODE_HOME/sessions\"\n"
            + "[ -e \"$QITS_KIMI_HOME/session_index.jsonl\" ] || : > \"$QITS_KIMI_HOME/session_index.jsonl\"\n"
            + "ln -s \"$QITS_KIMI_HOME/session_index.jsonl\" \"$KIMI_CODE_HOME/session_index.jsonl\"\n");
  }

  /** Appends the scoped {@code mcp.json} heredoc if any MCP servers are attached. */
  private void appendMcpConfig(StringBuilder script) {
    if (mcpServers.isEmpty()) {
      return;
    }
    JsonObject kimiServers = new JsonObject();
    for (Map.Entry<String, JsonObject> entry : mcpServers.entrySet()) {
      String key = entry.getKey();
      JsonObject kimiConfig = entry.getValue().copy();
      List<String> enabled = enabledToolsFor(key);
      if (!enabled.isEmpty()) {
        kimiConfig.put("enabledTools", new JsonArray(enabled));
      }
      kimiServers.put(key, kimiConfig);
    }
    String json = new JsonObject().put("mcpServers", kimiServers).encode();
    script.append("\ncat > \"$KIMI_CODE_HOME/mcp.json\" <<'EOF'\n").append(json).append("\nEOF");
  }

  /**
   * The agent-activity lifecycle hooks Kimi reports. {@code SessionStart} is always emitted (it
   * drives session-lineage); the turn-boundary events are added only when {@link #activityTracking}
   * is on. Names match Claude's hook events — verify against the pinned Kimi CLI's {@code [[hooks]]}
   * vocabulary; an event Kimi doesn't recognize simply never fires (harmless).
   */
  private static final List<String> ACTIVITY_EVENTS =
      List.of("UserPromptSubmit", "Stop", "Notification", "SessionEnd");

  /**
   * Appends the launch-local {@code config.toml} carrying this launch's activity hooks. Kimi's only
   * hook channel is a {@code [[hooks]]} entry in {@code config.toml} — there is no per-launch flag
   * like Claude's {@code --settings} — and the report URL is per-command, so a static volume-level
   * hook can't carry it. Instead the launch copies the volume's {@code config.toml} into the
   * throwaway home and appends one {@code [[hooks]]} block per event, each POSTing the hook's stdin
   * JSON ({@code {hook_event_name, session_id, …}}) to the daemon's loopback hook webhook. {@code
   * SessionStart} is always appended (lineage); the turn-boundary events follow when {@link
   * #activityTracking} is on. Must run after {@link #appendKimiHomePrelude} (it writes into the
   * throwaway home) and before the {@code kimi} invocation.
   */
  private void appendSessionReportHook(StringBuilder script) {
    if (sessionReportingUrl == null) {
      return;
    }
    if (sessionReportingUrl.contains("'")) {
      // Defense in depth: the URL is composed of a fixed loopback host, a port, and a UUID command
      // id, none of which can contain a quote — but it ends up inside a TOML literal string.
      throw new IllegalArgumentException(
          "Session-reporting URL must not contain quotes: " + sessionReportingUrl);
    }
    String post =
        "curl -fsS -m 5 -X POST -H \"Content-Type: application/json\" --data-binary @- "
            + sessionReportingUrl;
    script.append(
        "\n{ [ ! -e \"$QITS_KIMI_HOME/config.toml\" ] || cat \"$QITS_KIMI_HOME/config.toml\"; }"
            + " > \"$KIMI_CODE_HOME/config.toml\"\n");
    script.append("cat >> \"$KIMI_CODE_HOME/config.toml\" <<'EOF'\n");
    appendHookBlock(script, "SessionStart", post); // always — session-lineage
    if (activityTracking) {
      for (String event : ACTIVITY_EVENTS) {
        appendHookBlock(script, event, post);
      }
    }
    script.append("EOF");
  }

  /** One {@code [[hooks]]} TOML block wiring {@code event} to the report {@code command}. */
  private static void appendHookBlock(StringBuilder script, String event, String command) {
    script
        .append("\n[[hooks]]\n")
        .append("event = \"")
        .append(event)
        .append("\"\n")
        .append("command = '")
        .append(command)
        .append("'\n");
  }

  /**
   * Kimi's {@code enabledTools} is per-server and takes bare tool names. The shared allowlists use
   * the Claude-prefixed {@code mcp__<server>__<tool>} form for every server — strip the prefix for
   * the server we are configuring.
   */
  private List<String> enabledToolsFor(String serverKey) {
    return stripServerPrefix(serverKey, allowedTools);
  }

  /**
   * Strips the Claude-prefixed {@code mcp__<server>__} from the tools that belong to {@code
   * serverKey}, yielding the bare per-server names kimi's {@code enabledTools} takes (both the
   * file-based {@code mcp.json} and the ACP {@code session/new} channel use this form). Shared with
   * the ACP chat launch, which builds its allowlist from the same {@code READ_ONLY_*} lists.
   */
  public static List<String> stripServerPrefix(String serverKey, List<String> tools) {
    String prefix = "mcp__" + serverKey + "__";
    List<String> result = new ArrayList<>();
    for (String tool : tools) {
      if (tool.startsWith(prefix)) {
        result.add(tool.substring(prefix.length()));
      }
    }
    return result;
  }

  /**
   * Kimi Code persists a session's transcript under {@code
   * $KIMI_CODE_HOME/sessions/<workDirKey>/<sessionId>/agents/main/wire.jsonl}, where {@code
   * workDirKey} is {@code wd_<basename(cwd)>_<sha256(cwd)[:12]>} — e.g. {@code /workspace} → {@code
   * wd_workspace_c52ddf65534b}. Verified against CLI 0.28.1 and pinned by a regression test (this is
   * NOT Claude's non-alphanumeric→{@code -} escaping, despite the similar purpose).
   */
  @Override
  public Path transcriptPath(String cwd, String sessionId) {
    return Path.of("sessions", workDirKey(cwd), sessionId, "agents", "main", "wire.jsonl");
  }

  /**
   * Subagent sidechains live as sibling {@code agents/<subagentId>/wire.jsonl} files under the same
   * session dir.
   */
  @Override
  public Path subagentsDir(String cwd, String sessionId) {
    return Path.of("sessions", workDirKey(cwd), sessionId, "agents");
  }

  /**
   * Kimi's per-working-directory session bucket: {@code wd_<basename>_<sha256(cwd)[:12]>}. The
   * basename keeps it human-recognizable; the hash of the full path disambiguates same-named dirs
   * (verified: {@code /tmp} → {@code wd_tmp_e9671acd2448}, {@code /tmp/probe-nest/sub} → {@code
   * wd_sub_7e6a66d1ac42}).
   */
  private static String workDirKey(String cwd) {
    String base = Path.of(cwd).getFileName() == null ? "" : Path.of(cwd).getFileName().toString();
    return "wd_" + base + "_" + sha256Hex(cwd).substring(0, 12);
  }

  private static String sha256Hex(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the platform", e);
    }
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
