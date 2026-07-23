package eu.wohlben.qits.workspacedaemon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire contract's fast, framework-free guard: every message survives {@code encode → decode}
 * unchanged, and the discriminator round-trips through the {@link DaemonProtocol.Type} constants.
 * The {@code service}/{@code workspace-daemon} sides only bridge the map to their JSON library, so
 * this test covers the shared mapping both depend on.
 */
class DaemonCodecTest {

  private static DaemonMessage roundTrip(DaemonMessage message) {
    return DaemonCodec.decode(DaemonCodec.encode(message));
  }

  @Test
  void helloRoundTrips() {
    Hello hello = new Hello("ws-1", "repo-1", "feature", "main", DaemonProtocol.CAPABILITY_VERSION);
    assertEquals(hello, roundTrip(hello));
    assertEquals(
        DaemonProtocol.Type.HELLO, DaemonCodec.encode(hello).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void heartbeatRoundTrips() {
    Heartbeat heartbeat = new Heartbeat("ws-1");
    assertEquals(heartbeat, roundTrip(heartbeat));
  }

  @Test
  void clientLogRoundTrips() {
    DaemonLog log = new DaemonLog("INFO", "hello from workspace-daemon");
    assertEquals(log, roundTrip(log));
  }

  @Test
  void commandChunkRoundTripsBothStreams() {
    CommandChunk out = new CommandChunk("c1", Stream.STDOUT, "line\n");
    CommandChunk err = new CommandChunk("c1", Stream.STDERR, "oops\n");
    assertEquals(out, roundTrip(out));
    assertEquals(err, roundTrip(err));
  }

  @Test
  void commandExitRoundTrips() {
    CommandExit exit = new CommandExit("c1", 137);
    assertEquals(exit, roundTrip(exit));
  }

  @Test
  void workspaceInfoRoundTrips() {
    WorkspaceInfo info = new WorkspaceInfo("ws-1", "repo-1", "feature", "main", "abc123", true);
    assertEquals(info, roundTrip(info));
  }

  @Test
  void provisionedRoundTrips() {
    Provisioned provisioned = new Provisioned("ws-1", "abc123");
    assertEquals(provisioned, roundTrip(provisioned));
    assertEquals(
        DaemonProtocol.Type.PROVISIONED,
        DaemonCodec.encode(provisioned).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void provisionFailedRoundTrips() {
    ProvisionFailed failed = new ProvisionFailed("ws-1", "git clone exited 128");
    assertEquals(failed, roundTrip(failed));
    assertEquals(
        DaemonProtocol.Type.PROVISION_FAILED,
        DaemonCodec.encode(failed).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void ackRoundTrips() {
    assertEquals(new Ack(), roundTrip(new Ack()));
  }

  @Test
  void runCommandRoundTripsArgvAndEnv() {
    RunCommand command =
        new RunCommand(
            "c1", List.of("git", "rev-parse", "HEAD"), "/workspace", Map.of("FOO", "bar"));
    assertEquals(command, roundTrip(command));
  }

  @Test
  void runCommandToleratesNullCollections() {
    RunCommand command = new RunCommand("c1", null, null, null);
    RunCommand decoded = (RunCommand) roundTrip(command);
    assertEquals(List.of(), decoded.argv());
    assertEquals(Map.of(), decoded.env());
  }

  @Test
  void describeRoundTrips() {
    Describe describe = new Describe("c1");
    assertEquals(describe, roundTrip(describe));
  }

  @Test
  void decodeRejectsMissingType() {
    assertThrows(IllegalArgumentException.class, () -> DaemonCodec.decode(Map.of()));
  }

  @Test
  void decodeRejectsUnknownType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonCodec.decode(Map.of(DaemonProtocol.Field.TYPE, "nope")));
  }
}
