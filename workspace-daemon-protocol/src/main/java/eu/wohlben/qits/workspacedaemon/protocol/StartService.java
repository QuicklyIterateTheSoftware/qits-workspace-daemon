package eu.wohlben.qits.workspacedaemon.protocol;

import java.util.Map;

/**
 * qits → {@code workspace-daemon}: start one workspace service (dev server) on demand — the
 * manual/subsequent start the Services tab triggers, correlated by {@code correlationId}. {@code
 * id} is the service name (the config key, unique within a workspace's config, the same token
 * {@link ServiceTransition} and the {@code service:<name>} {@link CommandChunk} correlation carry).
 * {@code script}/{@code env} carry the definition so a manual start works even for a service not in
 * the daemon's in-container config; when {@code script} is blank the daemon looks the name up in
 * its held config. Auto-start services are NOT started this way — the daemon self-starts them as
 * the tail of its boot sequence, so qits sends nothing for those. docs/epics/qits-workspace-daemon/
 * Part 4.
 */
public record StartService(String correlationId, String id, String script, Map<String, String> env)
    implements DaemonMessage {}
