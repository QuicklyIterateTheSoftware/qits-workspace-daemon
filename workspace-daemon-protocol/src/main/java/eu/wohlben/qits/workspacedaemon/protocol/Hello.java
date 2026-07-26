package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * The first frame {@code workspace-daemon} sends on connect: its identity (read from container env
 * the factory injected) plus its {@link DaemonProtocol#CAPABILITY_VERSION} and its build identity.
 * The backend registers the connection keyed by {@code workspaceId} and replies with {@link Ack}.
 *
 * <p>{@code daemonVersion}/{@code daemonBuildTime} are the daemon binary's own release identity,
 * baked into the native image at build time (Maven {@code project.version} + {@code
 * maven.build.timestamp}, see {@code workspace-daemon/pom.xml}). Together they distinguish both
 * numbered releases (different {@code daemonVersion}) and floating pre-release builds sharing one
 * {@code -SNAPSHOT} version (different {@code daemonBuildTime}). Both are optional on the wire — an
 * older daemon image that predates these fields sends {@code null}, and the backend records the
 * connection all the same. {@code daemonBuildTime} is an ISO-8601 instant string ({@code
 * yyyy-MM-dd'T'HH:mm:ss'Z'}); the backend parses it to an {@code Instant}.
 */
public record Hello(
    String workspaceId,
    String repoId,
    String branch,
    String parent,
    int capabilityVersion,
    String daemonVersion,
    String daemonBuildTime)
    implements DaemonMessage {}
