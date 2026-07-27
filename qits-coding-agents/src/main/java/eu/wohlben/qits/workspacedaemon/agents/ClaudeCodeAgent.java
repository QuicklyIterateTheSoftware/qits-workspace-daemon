package eu.wohlben.qits.workspacedaemon.agents;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;

/**
 * The Claude Code harness — a {@link CodingAgent} that renders its accumulated configuration into a
 * {@code claude} command. Interactive launches {@code exec claude}; a one-off run uses {@code claude
 * -p …}. Attached MCP servers are merged into one {@code --strict-mcp-config --mcp-config '{…}'} and
 * the collected allowlist into a single {@code --allowedTools '…'}; an autonomous run adds {@code
 * --dangerously-skip-permissions}.
 *
 * <p>The initial context / prompt is embedded directly as a shell-quoted argument (single-quoting is
 * injection-safe for any content), so a prompt can come from anywhere the caller reads it — a
 * literal, a classpath resource, a request body — without a side file.
 */
public class ClaudeCodeAgent extends CodingAgent {

  @Override
  public LaunchSpec start() {
    StringBuilder command = new StringBuilder("exec claude");
    if (flatOutput) {
      // --ax-screen-reader renders flat text (no alternate-screen TUI/animations), so the PTY
      // session log captures the readable conversation instead of terminal control sequences that
      // get wiped when the interactive UI exits.
      command.append(" --ax-screen-reader");
    }
    if (initialContext != null && !initialContext.isBlank()) {
      command.append(' ').append(shellQuote(initialContext));
    }
    appendFlags(command);
    return new LaunchSpec(command.toString(), true, environment);
  }

  @Override
  public LaunchSpec run(String prompt) {
    StringBuilder command = new StringBuilder("claude -p ").append(shellQuote(prompt));
    appendFlags(command);
    return new LaunchSpec(command.toString(), false, environment);
  }

  @Override
  public LaunchSpec chat() {
    // Bidirectional stream-json: user messages are fed on stdin as JSON, structured events
    // (assistant messages, tool calls, result) come back on stdout — driven programmatically over
    // plain pipes, not a PTY. --verbose emits every event, not just the final result.
    // --include-hook-events surfaces hook lifecycle events (e.g. Stop) in the stream, giving the
    // daemon turn-boundary awareness for busy/idle detection and well-timed service-event
    // injection. exec'd because the process is long-lived and managed.
    StringBuilder command =
        new StringBuilder(
            "exec claude --print --input-format stream-json --output-format stream-json"
                + " --include-hook-events --verbose");
    appendFlags(command);
    return new LaunchSpec(command.toString(), false, environment);
  }

  /**
   * Appends the session flags, the model, the MCP config, the allowlist, the session-report hook and
   * the skip-permissions flag.
   */
  private void appendFlags(StringBuilder command) {
    validateSessionConfiguration();
    if (resumeSessionId != null) {
      command.append(" --resume ").append(resumeSessionId);
      if (forkRequested) {
        command.append(" --fork-session --session-id ").append(sessionId);
      }
    } else if (sessionId != null) {
      command.append(" --session-id ").append(sessionId);
    }
    if (model != null && !model.isBlank()) {
      command.append(" --model ").append(shellQuote(model));
    }
    if (!mcpServers.isEmpty()) {
      JsonObject servers = new JsonObject();
      mcpServers.forEach(servers::put);
      String json = new JsonObject().put("mcpServers", servers).encode();
      command.append(" --strict-mcp-config --mcp-config ").append(shellQuote(json));
    }
    if (!allowedTools.isEmpty()) {
      command.append(" --allowedTools ").append(shellQuote(String.join(",", allowedTools)));
    }
    if (sessionReportingUrl != null) {
      command.append(" --settings ").append(shellQuote(activityHookSettings().encode()));
    }
    if (skipPermissions) {
      command.append(" --dangerously-skip-permissions");
    }
  }

  /**
   * A settings layer wiring the agent-activity lifecycle hooks, each POSTing the hook's stdin JSON
   * ({@code {hook_event_name, session_id, transcript_path, source, …}}) to the daemon's loopback hook
   * webhook. {@code SessionStart} is <b>always</b> emitted — it fires on {@code startup}/{@code
   * resume}/{@code clear}/{@code compact} and drives session-lineage, which is non-optional. When
   * {@link #activityTracking} is on, the turn-boundary events ({@code UserPromptSubmit}/{@code
   * Stop}/{@code Notification}/{@code SessionEnd}) are added too, so the workspace can show the live
   * "cooking / idle / waiting" state.
   */
  private JsonObject activityHookSettings() {
    if (sessionReportingUrl.contains("'")) {
      // Defense in depth: the URL is composed of a fixed loopback host, a port, and a UUID command
      // id, none of which can contain a quote — but it ends up inside a single-quoted argv.
      throw new IllegalArgumentException(
          "Session-reporting URL must not contain quotes: " + sessionReportingUrl);
    }
    String post =
        "curl -fsS -m 5 -X POST -H \"Content-Type: application/json\" --data-binary @- "
            + sessionReportingUrl;
    JsonArray group =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "hooks",
                        new JsonArray()
                            .add(new JsonObject().put("type", "command").put("command", post))));
    JsonObject hooks = new JsonObject();
    hooks.put("SessionStart", group); // always — session-lineage
    if (activityTracking) {
      hooks.put("UserPromptSubmit", group);
      hooks.put("Stop", group);
      hooks.put("Notification", group);
      hooks.put("SessionEnd", group);
    }
    return new JsonObject().put("hooks", hooks);
  }

  /**
   * Claude Code persists a session's transcript under {@code
   * $CLAUDE_CONFIG_DIR/projects/<escaped-cwd>/<sessionId>.jsonl}, where the escaped cwd replaces
   * every non-alphanumeric character with {@code -} (verified against CLI 2.1.204, the pinned image
   * version).
   */
  @Override
  public Path transcriptPath(String cwd, String sessionId) {
    return Path.of("projects", escapeCwd(cwd), sessionId + ".jsonl");
  }

  /**
   * Task-tool subagents persist beside the main JSONL: {@code
   * projects/<escaped-cwd>/<sessionId>/subagents/agent-<agentId>.jsonl} plus a sibling {@code
   * agent-<agentId>.meta.json} carrying {@code {agentType, description, toolUseId, spawnDepth}}.
   */
  @Override
  public Path subagentsDir(String cwd, String sessionId) {
    return Path.of("projects", escapeCwd(cwd), sessionId, "subagents");
  }

  private static String escapeCwd(String cwd) {
    return cwd.replaceAll("[^A-Za-z0-9]", "-");
  }

  /**
   * POSIX single-quoting: safe for any content, since only {@code '} is special inside {@code '…'}.
   */
  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
