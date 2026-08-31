package eu.wohlben.qits.workspacedaemon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    Hello hello =
        new Hello(
            "ws-1",
            "repo-1",
            "feature",
            "main",
            DaemonProtocol.CAPABILITY_VERSION,
            "1.0.0-SNAPSHOT",
            "2026-07-25T09:14:03Z");
    assertEquals(hello, roundTrip(hello));
    assertEquals(
        DaemonProtocol.Type.HELLO, DaemonCodec.encode(hello).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void helloFromAnOlderDaemonDecodesMissingBuildIdentityAsNull() {
    // An older daemon image predating the build-identity fields sends a Hello without them; the map
    // simply lacks those keys and they must decode to null (the backend records the connection all
    // the same). Simulate by encoding a full Hello and dropping the two keys before decode.
    var map =
        new java.util.LinkedHashMap<>(
            DaemonCodec.encode(
                new Hello(
                    "ws-1", "repo-1", "feature", "main", 1, "1.0.0", "2026-07-25T09:14:03Z")));
    map.remove(DaemonProtocol.Field.DAEMON_VERSION);
    map.remove(DaemonProtocol.Field.DAEMON_BUILD_TIME);
    Hello decoded = (Hello) DaemonCodec.decode(map);
    assertEquals(new Hello("ws-1", "repo-1", "feature", "main", 1, null, null), decoded);
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
  void describeConfigRoundTrips() {
    DescribeConfig describeConfig = new DescribeConfig("c1");
    assertEquals(describeConfig, roundTrip(describeConfig));
    assertEquals(
        DaemonProtocol.Type.DESCRIBE_CONFIG,
        DaemonCodec.encode(describeConfig).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void configViewRoundTrips() {
    ConfigView view =
        new ConfigView("ws-1", "c1", "{\"actions\":[],\"daemons\":[]}", "invalid version");
    assertEquals(view, roundTrip(view));
    assertEquals(
        DaemonProtocol.Type.CONFIG_VIEW, DaemonCodec.encode(view).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void configViewToleratesNullWarning() {
    ConfigView view = new ConfigView("ws-1", "c1", "{}", null);
    assertEquals(view, roundTrip(view));
  }

  @Test
  void bootstrapStepRoundTrips() {
    BootstrapStep step = new BootstrapStep("ws-1", "install", BootstrapStep.Phase.EXECUTE);
    assertEquals(step, roundTrip(step));
    assertEquals(
        DaemonProtocol.Type.BOOTSTRAP_STEP,
        DaemonCodec.encode(step).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void bootstrapOutcomeRoundTrips() {
    BootstrapOutcome ok =
        new BootstrapOutcome("ws-1", "install", BootstrapOutcome.Result.SUCCEEDED, 0);
    BootstrapOutcome skipped =
        new BootstrapOutcome("ws-1", "seed", BootstrapOutcome.Result.SKIPPED, 1);
    assertEquals(ok, roundTrip(ok));
    assertEquals(skipped, roundTrip(skipped));
    assertEquals(
        DaemonProtocol.Type.BOOTSTRAP_OUTCOME,
        DaemonCodec.encode(ok).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void bootstrappedRoundTripsBothOutcomes() {
    Bootstrapped ok = new Bootstrapped("ws-1", true);
    Bootstrapped failed = new Bootstrapped("ws-1", false);
    assertEquals(ok, roundTrip(ok));
    assertEquals(failed, roundTrip(failed));
    assertEquals(
        DaemonProtocol.Type.BOOTSTRAPPED, DaemonCodec.encode(ok).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void runBootstrapRoundTrips() {
    RunBootstrap chain = new RunBootstrap("c1", null);
    RunBootstrap single = new RunBootstrap("c1", "install");
    assertEquals(chain, roundTrip(chain));
    assertEquals(single, roundTrip(single));
    assertEquals(
        DaemonProtocol.Type.RUN_BOOTSTRAP,
        DaemonCodec.encode(chain).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void bootstrapCorrelationIdIsPrefixed() {
    assertEquals("bootstrap:install", DaemonProtocol.bootstrapCorrelationId("install"));
  }

  @Test
  void startDaemonRoundTrips() {
    StartService start = new StartService("c1", "dev", "quarkus dev", Map.of("PORT", "8080"));
    assertEquals(start, roundTrip(start));
    assertEquals(
        DaemonProtocol.Type.START_SERVICE,
        DaemonCodec.encode(start).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void startDaemonRoundTripsWithBlankScriptAndEmptyEnv() {
    StartService start = new StartService("c1", "dev", "", Map.of());
    assertEquals(start, roundTrip(start));
  }

  @Test
  void signalDaemonRoundTrips() {
    SignalService signal = new SignalService("c1", "dev", "TERM");
    assertEquals(signal, roundTrip(signal));
    assertEquals(
        DaemonProtocol.Type.SIGNAL_SERVICE,
        DaemonCodec.encode(signal).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void daemonEventRoundTripsWithExitCode() {
    ServiceTransition crashed =
        new ServiceTransition("ws-1", "dev", ServiceTransition.State.CRASHED, 3);
    assertEquals(crashed, roundTrip(crashed));
    assertEquals(
        DaemonProtocol.Type.SERVICE_TRANSITION,
        DaemonCodec.encode(crashed).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void daemonEventRoundTripsWithNullExitCode() {
    ServiceTransition ready =
        new ServiceTransition("ws-1", "dev", ServiceTransition.State.READY, null);
    assertEquals(ready, roundTrip(ready));
  }

  @Test
  void gitStatusRoundTripsBothCleanStates() {
    GitStatus clean = new GitStatus("ws-1", true, "abc123");
    GitStatus dirty = new GitStatus("ws-1", false, "abc123");
    assertEquals(clean, roundTrip(clean));
    assertEquals(dirty, roundTrip(dirty));
    assertEquals(
        DaemonProtocol.Type.GIT_STATUS, DaemonCodec.encode(clean).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void workspaceChangedRoundTrips() {
    WorkspaceChanged changed = new WorkspaceChanged("ws-1", "COMMANDS");
    assertEquals(changed, roundTrip(changed));
    assertEquals(
        DaemonProtocol.Type.WORKSPACE_CHANGED,
        DaemonCodec.encode(changed).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void workspaceChangedToleratesAnUnknownTopic() {
    // The backend drops a topic it has no view for; the codec must still carry it, so the drop is
    // a decision the backend makes rather than a decode failure that kills the frame.
    WorkspaceChanged future = new WorkspaceChanged("ws-1", "SOMETHING_NEWER");
    assertEquals(future, roundTrip(future));
  }

  @Test
  void agentActivityRoundTrips() {
    AgentActivity sessionStart =
        new AgentActivity(
            "cmd-1",
            "11111111-1111-1111-1111-111111111111",
            DaemonProtocol.AgentState.IDLE,
            "SessionStart",
            "startup",
            "projects/-workspace/session.jsonl",
            1_700_000_000_000L);
    AgentActivity busy =
        new AgentActivity(
            "cmd-1", null, DaemonProtocol.AgentState.BUSY, "UserPromptSubmit", null, null, 42L);
    assertEquals(sessionStart, roundTrip(sessionStart));
    assertEquals(busy, roundTrip(busy));
    assertEquals(
        DaemonProtocol.Type.AGENT_ACTIVITY,
        DaemonCodec.encode(busy).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void serviceCorrelationIdIsPrefixed() {
    assertEquals("service:dev", DaemonProtocol.serviceCorrelationId("dev"));
  }

  @Test
  void pullBranchRoundTrips() {
    PullBranch pull = new PullBranch("c1", "feature");
    assertEquals(pull, roundTrip(pull));
    assertEquals(
        DaemonProtocol.Type.PULL_BRANCH, DaemonCodec.encode(pull).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void openStreamRoundTrips() {
    OpenStream open = new OpenStream("Zm9vYmFy", "/workspaces/daemon/stream/Zm9vYmFy");
    assertEquals(open, roundTrip(open));
    assertEquals(
        DaemonProtocol.Type.OPEN_STREAM, DaemonCodec.encode(open).get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void openStreamWithoutATargetIsTheApiAndPutsNothingOnTheWire() {
    // Both halves of the backward compatibility, in one place. The two-arg form is what every
    // caller wrote before targets existed and must still mean the API; and the API target must not
    // appear as a key, so the frame a newer host sends for an ordinary stream is byte-identical to
    // the one an older host sends — a daemon image that never learned the field cannot mis-read it.
    OpenStream open = new OpenStream("Zm9vYmFy", "/workspaces/daemon/stream/Zm9vYmFy");
    assertEquals(StreamTarget.API, open.target());
    assertFalse(
        DaemonCodec.encode(open).containsKey(DaemonProtocol.Field.TARGET),
        "the default target is an absence on the wire");
  }

  @Test
  void openStreamCarriesANonDefaultTarget() {
    OpenStream editor =
        new OpenStream("Zm9vYmFy", "/workspaces/daemon/stream/Zm9vYmFy", StreamTarget.EDITOR);
    assertEquals(editor, roundTrip(editor));
    assertEquals("EDITOR", DaemonCodec.encode(editor).get(DaemonProtocol.Field.TARGET));
  }

  @Test
  void openStreamFromAnOlderHostDecodesAnAbsentTargetAsTheApi() {
    // The frame an old qits sends a new daemon: nonce and path, no target key at all.
    Map<String, Object> map =
        Map.of(
            DaemonProtocol.Field.TYPE,
            DaemonProtocol.Type.OPEN_STREAM,
            DaemonProtocol.Field.NONCE,
            "Zm9vYmFy",
            DaemonProtocol.Field.PATH,
            "/workspaces/daemon/stream/Zm9vYmFy");
    OpenStream decoded = (OpenStream) DaemonCodec.decode(map);
    assertEquals(StreamTarget.API, decoded.target());
    assertEquals("/workspaces/daemon/stream/Zm9vYmFy", decoded.path());
  }

  @Test
  void openStreamRefusesATargetItCannotName() {
    // Fail closed, not fall back: an unknown target must not resolve to the API. The frame is
    // undecodable and ControlSocket drops it, so a stream meant for a listener this daemon does not
    // have is never served by the one it does.
    Map<String, Object> map =
        Map.of(
            DaemonProtocol.Field.TYPE,
            DaemonProtocol.Type.OPEN_STREAM,
            DaemonProtocol.Field.NONCE,
            "n",
            DaemonProtocol.Field.PATH,
            "/x",
            DaemonProtocol.Field.TARGET,
            "DEBUGGER");
    assertThrows(IllegalArgumentException.class, () -> DaemonCodec.decode(map));
  }

  @Test
  void editorStateRoundTripsEveryState() {
    for (String state :
        List.of(
            EditorState.State.STARTING, EditorState.State.RUNNING, EditorState.State.ENDED)) {
      EditorState message = new EditorState(state);
      assertEquals(message, roundTrip(message));
    }
    assertEquals(
        DaemonProtocol.Type.EDITOR_STATE,
        DaemonCodec.encode(new EditorState(EditorState.State.RUNNING))
            .get(DaemonProtocol.Field.TYPE));
  }

  @Test
  void theTunnelCapabilityIsTheVersionThatIntroducedIt() {
    // The compatibility branch is keyed on this pair agreeing: a daemon at TUNNEL_CAPABILITY_VERSION
    // binds loopback and serves OpenStream, one below binds qits-net and does not. If the current
    // version ever drops under it, every workspace silently takes the direct address to a port that
    // is not listening.
    assertEquals(4, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
    assertTrue(DaemonProtocol.CAPABILITY_VERSION >= DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
  }

  @Test
  void theWebEditorLandedAtCapabilityFive() {
    // Spelled as a literal because this file is also the drift detector between this module and the
    // copy qits-workspaces vendors: two copies at two versions is exactly the disagreement that
    // shows up as a workspace whose editor never appears, and nowhere else.
    assertEquals(5, DaemonProtocol.CAPABILITY_VERSION);
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
