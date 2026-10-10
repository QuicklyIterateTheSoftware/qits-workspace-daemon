package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: the daemon pushed an agent's branch (capability 10, qits-1152).
 *
 * <p>An agent works in its own agent worktree and creates its own branches: the wrapper branch is
 * made for it, and in each submodule it changes it names one itself. Nothing is created on the git
 * host up front, so the host learns which branches an agent owns only from this frame. It is sent
 * once per successful push, so the host can keep one row per (agent, repository) and update its
 * {@code sha}.
 *
 * <p>Unsolicited and not correlated, like {@link AgentActivity}. A backend older than 10 does not
 * know the tag and drops the frame; nothing else about the socket changes.
 *
 * @param agentId the agent worktree the branch belongs to
 * @param repository the repository's name on the git host: the wrapper's {@code repoName}, or the
 *     sibling name a submodule's origin points at
 * @param branch the pushed branch, without {@code refs/heads/}
 * @param sha the commit the branch points at after the push
 */
public record AgentBranchPushed(String agentId, String repository, String branch, String sha)
    implements DaemonMessage {}
