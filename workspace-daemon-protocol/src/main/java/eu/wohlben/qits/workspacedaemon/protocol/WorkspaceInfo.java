package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon}'s answer to {@link Describe}: the workspace's identity plus the
 * in-container git {@code HEAD} and dirty flag. A Part-1 stub proving the request/response
 * direction — consumers (the coding agent, the detail UI) are wired in later parts.
 */
public record WorkspaceInfo(
    String workspaceId, String repoId, String branch, String parent, String head, boolean dirty)
    implements DaemonMessage {}
