package eu.wohlben.qits.workspacedaemon.agents;

import io.vertx.core.json.JsonObject;

/**
 * Reusable, harness-agnostic MCP server config objects. An MCP server is carried through a {@link
 * CodingAgent} as a {@code key → config} pair; these generators produce the {@code config} object in
 * the shape the MCP config format expects, so the same server definition can be attached to any
 * agent that speaks MCP (the agent serializes it however it must — only the outer flag wrapping is
 * agent-specific).
 *
 * <p>The config is a {@link JsonObject} rather than a {@code Map}: both harnesses interpolate the
 * serialized form into a shell argument or a heredoc, and their tests assert the rendered command
 * line as a literal, so key order is part of the contract. Building it explicitly keeps the order
 * out of a JSON codec's hands.
 */
public final class McpServers {

  private McpServers() {}

  /**
   * The config for an HTTP (Streamable HTTP) MCP server: {@code {"type":"http","url":"<url>"}}. The
   * {@code url}'s variable parts (scope ids) must already be validated by the caller, since an agent
   * may interpolate the serialized config into a shell argument (see {@link AgentLaunchService}).
   */
  public static JsonObject httpMcp(String url) {
    return new JsonObject().put("type", "http").put("url", url);
  }
}
