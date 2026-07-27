package eu.wohlben.qits.workspacedaemon.agents;

/**
 * Which MCP server an agent session is launched with, and how it is scoped — a first-class agent
 * launch parameter, resolved to a scoped server URL by {@link AgentLaunchService}.
 *
 * <ul>
 *   <li>{@link #ACTIONS} — the "configure this repository" pairing: the "actions" server scoped to
 *       the repository the session runs in (for managing that repository's actions), plus the
 *       "repository" server narrowed to it (its branches, workspaces and commits).
 *   <li>{@link #REPOSITORY} — the "repository" server, scoped to the session's project and narrowed
 *       to that one repository (for driving a single repository from within a subtree).
 *   <li>{@link #PROJECT} — the "repository" server scoped to the whole project, with no repository
 *       narrowing (for driving every repository in the project).
 * </ul>
 *
 * <p>The "observability" server rides along with both workspace-narrowed scopes rather than being a
 * scope of its own: telemetry answers only for one workspace, so it is never the thing a session is
 * scoped <em>to</em> — it is what the narrowing unlocks.
 */
public enum AgentMcpScope {
  ACTIONS,
  REPOSITORY,
  PROJECT
}
