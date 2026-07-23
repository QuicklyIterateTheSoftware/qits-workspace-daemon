package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * The sealed set of control-plane messages. {@link DaemonCodec} encodes any of these to a
 * framework-free {@code Map} and decodes one back, so the backend can {@code switch} exhaustively
 * over the received type.
 */
public sealed interface DaemonMessage
    permits Hello,
        Heartbeat,
        DaemonLog,
        CommandChunk,
        CommandExit,
        WorkspaceInfo,
        Provisioned,
        ProvisionFailed,
        Ack,
        RunCommand,
        Describe {}
