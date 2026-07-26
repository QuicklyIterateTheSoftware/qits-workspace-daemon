package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: the workspace's parsed {@code .qits-config.yml}, read
 * in-container from the branch's checkout (so a feature branch that edits the file reports
 * <em>its</em> config, not {@code mainBranch}'s). The reply to a {@link DescribeConfig}, correlated
 * by {@code correlationId}.
 *
 * <p>{@code configJson} is the config serialized as JSON whose field names match the host's {@code
 * QitsConfig} record tree, so the backend deserializes it straight into a {@code QitsConfig} (a
 * single wire schema; the daemon owns the YAML parse — docs/epics/qits-workspace-daemon/ Part 2).
 * An absent/blank file yields the empty-config JSON; a structurally invalid file yields the
 * empty-config JSON plus a non-null {@code warning} (the "degrade loudly, never block" contract).
 */
public record ConfigView(
    String workspaceId, String correlationId, String configJson, String warning)
    implements DaemonMessage {}
