package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * An unsolicited nudge that something the workspace UI renders has changed, pushed from {@code
 * workspace-daemon} to qits so the browser refetches instead of polling on its own cadence. Payload
 * free by design, exactly like the backend's own change hints: a dropped frame costs one stale view
 * until the next one, and carrying the new state would mean two sources of truth for it.
 *
 * <p>Deliberately generic. The commands module needed one of these ({@code CommandChangeListener}
 * had been wired to null since the commands pass, because there was no frame to carry it) and the
 * transcript sweep needs the same, so rather than mint a message per subject the frame carries a
 * {@code topic} and the next thing that needs a nudge costs nothing.
 *
 * <p>{@code topic} is a plain String rather than an enum for the same reason {@link
 * DaemonProtocol.AgentState} is: this module stays free of any backend domain type. The values are
 * the names of the backend's own {@code WorkspaceChangeHint.Topic}, and a topic the backend does not
 * recognise is dropped rather than treated as an error — a newer daemon may nudge about something an
 * older backend has no view for.
 *
 * @param workspaceId the workspace whose view changed
 * @param topic which view — a {@code WorkspaceChangeHint.Topic} name, e.g. {@code COMMANDS}
 */
public record WorkspaceChanged(String workspaceId, String topic) implements DaemonMessage {}
