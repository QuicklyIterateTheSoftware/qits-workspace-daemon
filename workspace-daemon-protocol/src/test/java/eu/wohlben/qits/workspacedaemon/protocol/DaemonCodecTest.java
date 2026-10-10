package eu.wohlben.qits.workspacedaemon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
  void aKilledAgentsFrameRoundTripsItsExitCodeAndMessage() {
    AgentActivity killed =
        new AgentActivity(
            "cmd-1",
            "11111111-1111-1111-1111-111111111111",
            DaemonProtocol.AgentState.ENDED,
            DaemonProtocol.AgentEvent.OOM_KILLED,
            null,
            null,
            42L,
            137,
            "the coding agent was killed by the out-of-memory killer (exit code 137, memory cap 4"
                + " GiB)");
    assertEquals(killed, roundTrip(killed));
    Map<String, Object> wire = DaemonCodec.encode(killed);
    assertEquals(137, wire.get(DaemonProtocol.Field.EXIT_CODE));
    assertEquals("OomKilled", wire.get(DaemonProtocol.Field.HOOK_EVENT));
  }

  @Test
  void aHookFramePutsNoExitCodeOrMessageOnTheWire() {
    // Both halves of the compatibility, OpenStream's way: the seven-arg form every hook frame is
    // built with leaves the new keys off entirely, so a backend that predates them receives exactly
    // the frame it always did.
    AgentActivity busy =
        new AgentActivity(
            "cmd-1", null, DaemonProtocol.AgentState.BUSY, "UserPromptSubmit", null, null, 42L);
    Map<String, Object> wire = DaemonCodec.encode(busy);
    assertFalse(wire.containsKey(DaemonProtocol.Field.EXIT_CODE));
    assertFalse(wire.containsKey(DaemonProtocol.Field.MESSAGE));
    AgentActivity decoded = (AgentActivity) DaemonCodec.decode(wire);
    assertEquals(null, decoded.exitCode());
    assertEquals(null, decoded.message());
  }

  @Test
  void awaitingInputRoundTripsWhenPresent() {
    AgentActivity waiting =
        new AgentActivity(
            "cmd-1",
            null,
            DaemonProtocol.AgentState.WAITING,
            "Notification",
            null,
            null,
            42L,
            Boolean.TRUE);
    assertEquals(waiting, roundTrip(waiting));
    Map<String, Object> wire = DaemonCodec.encode(waiting);
    assertEquals(Boolean.TRUE, wire.get(DaemonProtocol.Field.AWAITING_INPUT));
  }

  @Test
  void aFrameWithNoAwaitingInputVerdictPutsNoKeyOnTheWire() {
    // The exitCode/message rule again: a frame built with the pre-qits-895 constructors, or one
    // whose verdict genuinely is "unknown", must stay byte-identical to what it was before the
    // field existed.
    AgentActivity busy =
        new AgentActivity(
            "cmd-1", null, DaemonProtocol.AgentState.BUSY, "UserPromptSubmit", null, null, 42L);
    Map<String, Object> wire = DaemonCodec.encode(busy);
    assertFalse(wire.containsKey(DaemonProtocol.Field.AWAITING_INPUT));
    AgentActivity decoded = (AgentActivity) DaemonCodec.decode(wire);
    assertEquals(null, decoded.awaitingInput());
  }

  @Test
  void awaitingInputFalseRoundTripsDistinctlyFromAbsent() {
    // false is a verdict, not the default: must not collapse onto the "no key" / null case.
    AgentActivity busy =
        new AgentActivity(
            "cmd-1",
            null,
            DaemonProtocol.AgentState.IDLE,
            "Stop",
            null,
            null,
            42L,
            Boolean.FALSE);
    Map<String, Object> wire = DaemonCodec.encode(busy);
    assertEquals(Boolean.FALSE, wire.get(DaemonProtocol.Field.AWAITING_INPUT));
    AgentActivity decoded = (AgentActivity) DaemonCodec.decode(wire);
    assertEquals(Boolean.FALSE, decoded.awaitingInput());
  }

  @Test
  void aKilledAgentsFrameCanCarryAwaitingInputToo() {
    AgentActivity killed =
        new AgentActivity(
            "cmd-1",
            "11111111-1111-1111-1111-111111111111",
            DaemonProtocol.AgentState.ENDED,
            DaemonProtocol.AgentEvent.OOM_KILLED,
            null,
            null,
            42L,
            137,
            "killed by the out-of-memory killer",
            Boolean.TRUE);
    assertEquals(killed, roundTrip(killed));
    Map<String, Object> wire = DaemonCodec.encode(killed);
    assertEquals(Boolean.TRUE, wire.get(DaemonProtocol.Field.AWAITING_INPUT));
  }

  @Test
  void keysTheDecoderDoesNotKnowAreIgnoredRatherThanFatal() {
    // The property a capability-6 backend relies on to take the killed agent's frame: its decoder
    // reads the keys it knows by name and never enumerates the map, so exitCode and message are
    // simply not looked at. Proven here with a key no version knows, on the same decode path.
    Map<String, Object> wire =
        new java.util.LinkedHashMap<>(
            DaemonCodec.encode(
                new AgentActivity(
                    "cmd-1",
                    null,
                    DaemonProtocol.AgentState.ENDED,
                    DaemonProtocol.AgentEvent.KILLED,
                    null,
                    null,
                    42L)));
    wire.put("somethingNewer", Map.of("nested", 1));
    AgentActivity decoded = (AgentActivity) DaemonCodec.decode(wire);
    assertEquals(DaemonProtocol.AgentState.ENDED, decoded.state());
    assertEquals(DaemonProtocol.AgentEvent.KILLED, decoded.hookEvent());
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
  void anOldFrameWithNoTargetStillDecodesAsTheApi() {
    Map<String, Object> map =
        Map.of(
            DaemonProtocol.Field.TYPE,
            DaemonProtocol.Type.OPEN_STREAM,
            DaemonProtocol.Field.NONCE,
            "n",
            DaemonProtocol.Field.PATH,
            "/x");
    OpenStream decoded = (OpenStream) DaemonCodec.decode(map);
    assertEquals(new OpenStream("n", "/x"), decoded);
    assertEquals(StreamTarget.API, decoded.target());
  }

  @Test
  void aServiceStreamFromAnOlderHostIsUndecodable() {
    // Workspace services were removed at capability 9 (qits-947). A host that still asks for a
    // SERVICE stream sends a target this daemon cannot name, so the frame is undecodable and
    // ControlSocket drops it — the fail-closed rule below, not a fallback to the API.
    Map<String, Object> map =
        Map.of(
            DaemonProtocol.Field.TYPE,
            DaemonProtocol.Type.OPEN_STREAM,
            DaemonProtocol.Field.NONCE,
            "n",
            DaemonProtocol.Field.PATH,
            "/x",
            DaemonProtocol.Field.TARGET,
            "SERVICE");
    assertThrows(IllegalArgumentException.class, () -> DaemonCodec.decode(map));
  }

  @Test
  void theRetiredServiceFramesAreUndecodable() {
    // The pre-rename wire tags the removed service messages travelled under (qits-947). An older
    // host's start/signal is dropped by ControlSocket's catch rather than handled.
    for (String tag : List.of("daemonEvent", "startDaemon", "signalDaemon")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> DaemonCodec.decode(Map.of(DaemonProtocol.Field.TYPE, tag)),
          tag);
    }
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
  void theTunnelCapabilityIsTheVersionThatIntroducedIt() {
    // The compatibility branch is keyed on this pair agreeing: a daemon at TUNNEL_CAPABILITY_VERSION
    // binds loopback and serves OpenStream, one below binds qits-net and does not. If the current
    // version ever drops under it, every workspace silently takes the direct address to a port that
    // is not listening.
    assertEquals(4, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
    assertTrue(DaemonProtocol.CAPABILITY_VERSION >= DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
  }

  @Test
  void theKilledAgentFrameLandedAtCapabilitySeven() {
    // No longer the bleeding edge now that later versions exist, so this one steps down to a >=: the
    // fact this capability still holds does not need re-asserting every time a later one lands.
    assertTrue(DaemonProtocol.CAPABILITY_VERSION >= 7);
  }

  @Test
  void theAwaitingInputVerdictLandedAtCapabilityEight() {
    assertTrue(DaemonProtocol.CAPABILITY_VERSION >= 8);
  }

  @Test
  void workspaceServicesWereRemovedAtCapabilityNine() {
    assertTrue(DaemonProtocol.CAPABILITY_VERSION >= 9);
  }

  @Test
  void agentWorktreesLandedAtCapabilityTen() {
    // A literal: the host records it, and a capability that moved without this line moving is a
    // contract nobody re-read.
    assertEquals(10, DaemonProtocol.CAPABILITY_VERSION);
  }

  @Test
  void anAgentBranchPushRoundTripsWithItsFourKeys() {
    AgentBranchPushed pushed =
        new AgentBranchPushed("agent-1", "qits-ci-service", "ticket/qits-12-fix", "abc123");
    assertEquals(pushed, roundTrip(pushed));
    assertEquals(
        Map.of(
            "type", "agentBranchPushed",
            "agentId", "agent-1",
            "repository", "qits-ci-service",
            "branch", "ticket/qits-12-fix",
            "sha", "abc123"),
        DaemonCodec.encode(pushed));
  }

  @Test
  void anActivityCarriesItsAgentOnlyWhenItHasOne() {
    AgentActivity plain = new AgentActivity("cmd-1", "s-1", "IDLE", "Stop", null, null, 7L);
    assertFalse(DaemonCodec.encode(plain).containsKey("agentId"));
    assertNull(((AgentActivity) roundTrip(plain)).agentId());

    AgentActivity tagged = plain.withAgentId("agent-1");
    assertEquals("agent-1", DaemonCodec.encode(tagged).get("agentId"));
    assertEquals(tagged, roundTrip(tagged));
  }

  @Test
  void theRemovedEditorTargetIsRefusedLikeAnyUnknownOne() {
    Map<String, Object> map =
        Map.of(
            DaemonProtocol.Field.TYPE,
            DaemonProtocol.Type.OPEN_STREAM,
            DaemonProtocol.Field.NONCE,
            "n",
            DaemonProtocol.Field.PATH,
            "/x",
            DaemonProtocol.Field.TARGET,
            "EDITOR");
    assertThrows(IllegalArgumentException.class, () -> DaemonCodec.decode(map));
  }

  @Test
  void theRemovedBootstrapFramesAreUnknownTypes() {
    for (String type : List.of("runBootstrap", "bootstrapStep", "bootstrapped", "editorState")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> DaemonCodec.decode(Map.of(DaemonProtocol.Field.TYPE, type)));
    }
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
