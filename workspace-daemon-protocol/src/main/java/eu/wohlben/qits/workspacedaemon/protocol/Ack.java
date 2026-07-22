package eu.wohlben.qits.workspacedaemon.protocol;

/** The backend's acknowledgement of a {@link Hello}: the handshake is complete. */
public record Ack() implements DaemonMessage {}
