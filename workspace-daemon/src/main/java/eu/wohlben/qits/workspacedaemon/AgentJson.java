package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.agents.AgentSessionNodeDto;
import eu.wohlben.qits.workspacedaemon.agents.AgentSubagentDto;
import eu.wohlben.qits.workspacedaemon.agents.AgentType;
import eu.wohlben.qits.workspacedaemon.agents.InstalledPluginDto;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.List;

/**
 * The agent surface's response bodies, built by hand with {@code io.vertx.core.json} exactly as
 * {@link CommandJson} and {@link WorkspaceJson} are.
 *
 * <p><strong>Every key here is a wire contract.</strong> These bodies deserialize into the host's
 * existing {@code AgentSessionNodeDto}, {@code AgentSubagentDto} and {@code InstalledPluginDto}
 * records, which the SPA consumes unchanged — a renamed key is a broken Agents view that nothing in
 * this reactor would notice. {@code AgentsApiTest} therefore asserts them as literal strings; a test
 * that read them off the records would rename itself along with the bug.
 *
 * <p>Same conventions as its siblings: absent optionals are omitted rather than emitted as null,
 * lists are always present (empty rather than absent), primitives are always present, enums go out
 * as {@code name()}, and an {@link Instant} as its ISO-8601 string.
 */
final class AgentJson {

  private AgentJson() {}

  /** {@code POST /agents} — the launched command, in the same shape the commands routes use. */
  static JsonObject launched(
      eu.wohlben.qits.workspacedaemon.commands.Command command, String repoId, String workspaceId) {
    return new JsonObject().put("command", CommandJson.command(command, repoId, workspaceId));
  }

  /** {@code GET /agents/available} — the harnesses this daemon can launch, and the default. */
  static JsonObject available(AgentType defaultAgent) {
    JsonArray agents = new JsonArray();
    for (AgentType type : AgentType.values()) {
      agents.add(type.name());
    }
    return new JsonObject().put("agents", agents).put("defaultAgent", defaultAgent.name());
  }

  /** {@code GET /agent-sessions} — the session tree. */
  static JsonObject sessions(List<AgentSessionNodeDto> sessions) {
    JsonArray array = new JsonArray();
    for (AgentSessionNodeDto session : sessions) {
      array.add(session(session));
    }
    return new JsonObject().put("sessions", array);
  }

  private static JsonObject session(AgentSessionNodeDto node) {
    JsonObject json = new JsonObject().put("sessionId", node.sessionId());
    putIfPresent(json, "firstRecordedAt", iso(node.firstRecordedAt()));
    putIfPresent(json, "forkedFromSessionId", node.forkedFromSessionId());
    if (node.messageCount() != null) {
      // Absent means "not swept yet", which the UI renders differently from a swept zero.
      json.put("messageCount", node.messageCount());
    }
    putIfPresent(json, "newestCommandId", node.newestCommandId());
    JsonArray subagents = new JsonArray();
    for (AgentSubagentDto subagent : node.subagents()) {
      subagents.add(subagent(subagent));
    }
    json.put("subagents", subagents);
    JsonArray children = new JsonArray();
    for (AgentSessionNodeDto child : node.children()) {
      children.add(session(child));
    }
    return json.put("children", children);
  }

  private static JsonObject subagent(AgentSubagentDto subagent) {
    JsonObject json =
        new JsonObject()
            .put("agentId", subagent.agentId())
            .put("messageCount", subagent.messageCount());
    putIfPresent(json, "agentType", subagent.agentType());
    putIfPresent(json, "description", subagent.description());
    putIfPresent(json, "firstTimestamp", iso(subagent.firstTimestamp()));
    return json;
  }

  /** {@code GET·POST /agent-plugins} — the plugins present on the shared credential volume. */
  static JsonObject plugins(List<InstalledPluginDto> plugins) {
    JsonArray array = new JsonArray();
    for (InstalledPluginDto plugin : plugins) {
      array.add(
          new JsonObject().put("pluginId", plugin.pluginId()).put("enabled", plugin.enabled()));
    }
    return new JsonObject().put("installed", array);
  }

  /** {@code POST /prompt-refinements} — the rewritten prompt. */
  static JsonObject refinement(String prompt) {
    return new JsonObject().put("prompt", prompt);
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static void putIfPresent(JsonObject json, String key, String value) {
    if (value != null) {
      json.put(key, value);
    }
  }
}
