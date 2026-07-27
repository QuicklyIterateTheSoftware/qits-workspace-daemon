package eu.wohlben.qits.workspacedaemon.agents;

/**
 * One Claude Code plugin present on the shared credential volume.
 *
 * <p>A property of the volume, not of a workspace — every workspace container mounts the same one,
 * so installing from any of them installs for all. The field names are a wire contract; see {@link
 * AgentSubagentDto}.
 *
 * @param pluginId the marketplace-qualified id, e.g. {@code jdtls-lsp@claude-plugins-official}
 * @param enabled false means installed but switched off
 */
public record InstalledPluginDto(String pluginId, boolean enabled) {}
