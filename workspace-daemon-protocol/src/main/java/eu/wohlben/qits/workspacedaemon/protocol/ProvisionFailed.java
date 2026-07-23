package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * {@code workspace-daemon} → qits: the autonomous self-clone (or submodule materialization) failed
 * in-container — the "degrade loudly" signal. The host marks the workspace {@code FAILED} with the
 * message and removes the container (parity with the old host-driven clone's {@code
 * InternalServerErrorException} + {@code containers.rm}). See
 * docs/epics/qits-workspace-daemon/features/ (autonomous self-clone on boot).
 */
public record ProvisionFailed(String workspaceId, String message) implements DaemonMessage {}
