package eu.wohlben.qits.workspacedaemon;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The bodies of the {@code agent-worktrees} routes (qits-1152). Hand-built like {@link AgentJson},
 * and for the same reason: the field names are a wire contract with qits-workspaces, written down
 * in {@code docs/openapi.yml} and asserted as literal strings by {@code AgentWorktreesApiTest}.
 */
final class AgentWorktreeJson {

  private AgentWorktreeJson() {}

  /** {@code POST agent-worktrees}. */
  static JsonObject started(AgentRuntime.Started started) {
    return new JsonObject()
        .put("agentId", started.agentId())
        .put("path", started.path().toString())
        .put("commandId", started.commandId())
        .put("launched", started.launched())
        .put("resumed", started.resumed())
        .put("sessionId", started.sessionId());
  }

  /** {@code GET agent-worktrees}: one entry per agent. */
  static JsonArray views(java.util.List<AgentRuntime.View> views) {
    JsonArray out = new JsonArray();
    views.forEach(view -> out.add(view(view)));
    return out;
  }

  /** One agent: where it is, whether its harness runs, and each repository's branch. */
  static JsonObject view(AgentRuntime.View view) {
    JsonArray branches = new JsonArray();
    boolean dirty = false;
    boolean unpushed = false;
    for (AgentWorktrees.RepoState state : view.repositories()) {
      dirty |= state.dirty();
      unpushed |= state.unpushedCommits() > 0;
      if (state.branch() != null) {
        branches.add(
            new JsonObject()
                .put("repository", state.repository())
                .put("path", state.path())
                .put("branch", state.branch())
                .put("head", state.head())
                .put("pushed", state.pushed()));
      }
    }
    return new JsonObject()
        .put("agentId", view.agentId())
        .put("workId", view.workId())
        .put("wrapperBranch", view.wrapperBranch())
        .put("path", view.path().toString())
        .put("harnessRunning", view.harnessRunning())
        .put("commandId", view.commandId())
        .put("sessionId", view.sessionId())
        .put("branches", branches)
        .put("dirty", dirty)
        .put("unpushed", unpushed);
  }

  /** {@code GET agent-worktrees/{agentId}/cleanup-check}, and a refused delete's body. */
  static JsonObject cleanupCheck(AgentWorktrees.CleanupCheck check) {
    JsonArray unpushed = new JsonArray();
    for (AgentWorktrees.Leftover leftover : check.unpushed()) {
      unpushed.add(
          new JsonObject()
              .put("repository", leftover.repository())
              .put("branch", leftover.branch())
              .put("commits", leftover.commits()));
    }
    return new JsonObject()
        .put("clean", check.clean())
        .put("dirty", new JsonArray(check.dirty()))
        .put("unpushed", unpushed);
  }

  /** {@code POST agent-worktrees/{agentId}/yield}. */
  static JsonObject yielded(String agentId, boolean stopped) {
    return new JsonObject().put("agentId", agentId).put("yielded", stopped);
  }

  /** {@code POST agent-worktrees/{agentId}/turn}. */
  static JsonObject turn(AgentRuntime.Turn turn) {
    JsonObject json =
        new JsonObject().put("delivered", turn.delivered()).put("restarted", turn.restarted());
    if (turn.commandId() != null) {
      json.put("commandId", turn.commandId());
    }
    if (turn.kind() != null) {
      json.put("kind", turn.kind());
    }
    return json;
  }

  /** {@code DELETE agent-worktrees/{agentId}}. */
  static JsonObject removed(String agentId) {
    return new JsonObject().put("agentId", agentId).put("removed", true);
  }
}
