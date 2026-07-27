package eu.wohlben.qits.workspacedaemon.agents;

/**
 * One agent launch as the caller asks for it.
 *
 * <p>The host's controller took eight loose arguments and the workspace ids besides. The ids are
 * ambient here, and the rest gathers into a record so the two launch shapes share one shape of
 * request.
 *
 * @param scope which MCP servers to attach, and how they are narrowed. Required.
 * @param mode chat or the interactive TUI; null means {@link AgentLaunchMode#CHAT}
 * @param initialContext the seed turn, or null for none
 * @param resumeSessionId a session of this container to continue, or null for a fresh one
 * @param fork branch {@code resumeSessionId} into a new session instead of continuing it (Claude
 *     only)
 * @param deliverTaskPrompt seed with the one-sentence bootstrap turn so the agent fetches the real
 *     prompt over MCP, instead of pushing {@code initialContext} literally. On the host this was
 *     additionally gated on a stored draft existing; there is no draft store here, so the caller's
 *     word is taken — it is the one that has the draft.
 * @param agentType the harness to use, or null for the resolved default
 */
public record AgentLaunchRequest(
    AgentMcpScope scope,
    AgentLaunchMode mode,
    String initialContext,
    String resumeSessionId,
    boolean fork,
    boolean deliverTaskPrompt,
    AgentType agentType) {

  public AgentLaunchMode modeOrDefault() {
    return mode == null ? AgentLaunchMode.CHAT : mode;
  }
}
