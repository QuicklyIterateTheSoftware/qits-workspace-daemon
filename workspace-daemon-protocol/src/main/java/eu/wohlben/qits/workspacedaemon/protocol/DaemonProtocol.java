package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * The single source of truth for the workspace-daemon control-plane wire contract's tags and field
 * names.
 *
 * <p>Messages are JSON objects with a {@code "type"} discriminator ({@link Type}) and a flat set of
 * fields ({@link Field}). The records in this package model each message's shape; the {@code
 * service} backend (de)serializes them with its Jackson {@code ObjectMapper}, the {@code
 * workspace-daemon} binary maps them to/from a Vert.x {@code JsonObject} field-by-field — both
 * against these constants, so a rename is caught in one place.
 *
 * <p>Part 1 (docs/epics/qits-workspace-daemon/) defines only what proves the transport: the {@link
 * Hello}/{@link Ack} handshake, {@link Heartbeat}, {@link DaemonLog}, the {@link RunCommand}→{@link
 * CommandChunk}*→{@link CommandExit} round-trip, and the {@link Describe}→{@link WorkspaceInfo}
 * stub. Later parts extend it.
 */
public final class DaemonProtocol {

  /**
   * The capability version {@code workspace-daemon} announces in its {@link Hello}. Bumped when the
   * wire contract changes in a way the backend must branch on; the backend records it but does not
   * gate on it.
   *
   * <p>3 added {@link WorkspaceChanged}. A backend still on 2 does not know the tag and drops the
   * frame when decoding it — {@code DaemonControlSocket} catches an undecodable frame and logs it
   * rather than failing the connection — so a newer daemon against an older backend degrades to the
   * refetch cadence that was the status quo, and nothing else about the socket changes.
   *
   * <p><b>4 added {@link OpenStream}, and this one the backend must gate on.</b> A daemon at 4 binds
   * its HTTP API to {@code 127.0.0.1} and can only be reached through the reverse tunnel; a daemon
   * at 3 binds {@code 0.0.0.0} and cannot answer an {@code OpenStream} at all. The two are strictly
   * complementary and keyed by this one number, so there is no ambiguous middle: a host reads the
   * version out of {@link Hello} and picks the tunnel or the direct address accordingly. This is
   * also the asymmetry that makes the direction of a change matter — a daemon→qits addition
   * degrades safely, but {@code OpenStream} travels qits→daemon and an older image simply never
   * handles it.
   *
   * <p><b>5 added the web editor: {@link EditorState}, and {@link OpenStream#target()}.</b> Both
   * halves are backward compatible in both directions, so nothing gates on this number — it is a
   * fact the host records. The daemon→qits half degrades as {@link WorkspaceChanged} did: a backend
   * still on 4 drops the unknown {@code editorState} tag and simply has no editor to show. The
   * qits→daemon half degrades the other way, which the {@code OPEN_STREAM} note above says is
   * ordinarily impossible — it works here only because the new field is <em>optional</em>: an old
   * host omits it and a new daemon decodes {@link StreamTarget#API}, the one behaviour that existed
   * before; a new host asking a 4 daemon for the editor is asking an image that has no editor in it,
   * and the old decoder ignores the field rather than mis-serving it.
   *
   * <p><b>6 added a {@code SERVICE} stream target and an {@code OpenStream.serviceId}</b>: the
   * dev-server web view through the tunnel. Both existed from 6 and were removed at 9 (below); the
   * number is kept here only so the history of the field reads straight.
   *
   * <p><b>7 added the killed agent</b> (qits-951): an {@link AgentActivity} the daemon sends itself,
   * {@code ENDED} with an {@link AgentEvent} as its {@code hookEvent}, when an agent's process dies
   * by SIGKILL — and the frame's two optional fields, {@link AgentActivity#exitCode()} and {@link
   * AgentActivity#message()}, that say what killed it. Nothing gates on this number. A backend on
   * 6 decodes the frame (unknown keys are not read) and gets an {@code ENDED} it already
   * understands, which is already better than the stale {@code IDLE} it kept before; it simply has
   * no sentence to show for it.
   *
   * <p><b>8 added {@link AgentActivity#awaitingInput()}</b> (qits-895): a nullable verdict on
   * whether the agent is blocked on the user rather than merely between turns, computed by {@code
   * workspace-daemon} from the hook payload a {@code Stop}/{@code Notification}/{@code
   * SessionEnd}/{@code UserPromptSubmit} carries and not from {@code state} or {@code hookEvent}
   * alone — {@code Stop} and {@code Notification} already collapse several payload shapes onto
   * one {@code state}, which is exactly why neither can answer this by itself. Nothing gates on
   * this number. A backend on 7 decodes the frame (the new key is unread) and is exactly as blind
   * to whether a session is blocked as it was before this field existed; it simply has no verdict
   * to show. The field is optional on the wire in both directions, the {@code exitCode}/{@code
   * message} rule again: written only when present, so a frame with no verdict — {@code
   * SessionStart}, an {@code other}-typed {@code Notification}, a {@code Stop} whose arrays are
   * missing or not JSON arrays — is byte-identical to what it was before, and a newer host
   * reading one of those from an older daemon decodes {@code null} rather than guessing {@code
   * false}.
   *
   * <p><b>9 removed workspace services</b> (qits-947): the checkout-declared dev servers and
   * everything that carried them — the {@code daemonEvent}, {@code startDaemon} and {@code
   * signalDaemon} frames, the {@code SERVICE} stream target and {@code OpenStream.serviceId}, and
   * the {@code service:<name>} output correlation. Nothing replaces them. A daemon at 9 never sends
   * {@code daemonEvent}, and drops a {@code startDaemon}/{@code signalDaemon} or a {@code SERVICE}
   * stream from an older host as an undecodable frame; a host that still knows the frames simply
   * never receives one.
   */
  public static final int CAPABILITY_VERSION = 9;

  /**
   * The first version whose daemon can serve a reverse-tunnel stream <em>and</em> has stopped
   * listening on {@code qits-net}. Named rather than spelled as a literal at the branch, because
   * the branch is a compatibility rule and not a magic number: below this, reach the daemon
   * directly on {@code 13338}; at or above it, that port is not listening.
   */
  public static final int TUNNEL_CAPABILITY_VERSION = 4;

  /**
   * The fixed {@code correlationId} the daemon tags its autonomous-self-provision output ({@link
   * CommandChunk}) with, so the backend can route those chunks to the workspace's {@code clone}
   * process segment (docs/epics/qits-workspace-daemon/ Part 1). A provision is not a request/reply
   * round-trip, so it has no per-call id — this shared constant stands in for one on both sides.
   */
  public static final String PROVISION_CORRELATION_ID = "provision";

  /**
   * The prefix a bootstrap step's streamed output ({@link CommandChunk}) is correlated with, so the
   * backend routes those chunks to the workspace's {@code bootstrap:<name>} process segment
   * (docs/epics/qits-workspace-daemon/ Part 3). Like {@link #PROVISION_CORRELATION_ID}, a bootstrap
   * step is not a request/reply round-trip, so its output correlation is a well-known value both
   * sides compute from the step name rather than a per-call id.
   */
  public static final String BOOTSTRAP_CORRELATION_PREFIX = "bootstrap:";

  /**
   * The output correlation id for a bootstrap step — {@link #BOOTSTRAP_CORRELATION_PREFIX}{@code +
   * name}.
   */
  public static String bootstrapCorrelationId(String stepName) {
    return BOOTSTRAP_CORRELATION_PREFIX + stepName;
  }

  private DaemonProtocol() {}

  /** The {@code "type"} discriminator values. */
  public static final class Type {
    // workspace-daemon -> qits
    public static final String HELLO = "hello";
    public static final String HEARTBEAT = "heartbeat";
    public static final String CLIENT_LOG = "clientLog";
    public static final String COMMAND_CHUNK = "commandChunk";
    public static final String COMMAND_EXIT = "commandExit";
    public static final String WORKSPACE_INFO = "workspaceInfo";
    public static final String PROVISIONED = "provisioned";
    public static final String PROVISION_FAILED = "provisionFailed";
    public static final String CONFIG_VIEW = "configView";
    public static final String BOOTSTRAP_STEP = "bootstrapStep";
    public static final String BOOTSTRAP_OUTCOME = "bootstrapOutcome";
    public static final String BOOTSTRAPPED = "bootstrapped";
    public static final String GIT_STATUS = "gitStatus";
    public static final String AGENT_ACTIVITY = "agentActivity";
    public static final String WORKSPACE_CHANGED = "workspaceChanged";
    public static final String EDITOR_STATE = "editorState";
    // qits -> workspace-daemon
    public static final String ACK = "ack";
    public static final String RUN_COMMAND = "runCommand";
    public static final String DESCRIBE = "describe";
    public static final String DESCRIBE_CONFIG = "describeConfig";
    public static final String RUN_BOOTSTRAP = "runBootstrap";
    public static final String PULL_BRANCH = "pullBranch";
    public static final String OPEN_STREAM = "openStream";

    private Type() {}
  }

  /** The JSON field names shared by both codecs. */
  public static final class Field {
    public static final String TYPE = "type";
    public static final String WORKSPACE_ID = "workspaceId";
    public static final String REPO_ID = "repoId";
    public static final String BRANCH = "branch";
    public static final String PARENT = "parent";
    public static final String CAPABILITY_VERSION = "capabilityVersion";
    public static final String DAEMON_VERSION = "daemonVersion";
    public static final String DAEMON_BUILD_TIME = "daemonBuildTime";
    public static final String LEVEL = "level";
    public static final String MESSAGE = "message";
    public static final String CORRELATION_ID = "correlationId";
    public static final String STREAM = "stream";
    public static final String TEXT = "text";
    // Also optional on AgentActivity, with MESSAGE: written only on a killed agent's frame.
    public static final String EXIT_CODE = "exitCode";
    public static final String HEAD = "head";
    public static final String DIRTY = "dirty";
    public static final String CLEAN = "clean";
    public static final String ARGV = "argv";
    public static final String CWD = "cwd";
    public static final String ENV = "env";
    public static final String CONFIG_JSON = "configJson";
    public static final String WARNING = "warning";
    public static final String NAME = "name";
    public static final String PHASE = "phase";
    public static final String OUTCOME = "outcome";
    public static final String OK = "ok";
    public static final String STATE = "state";
    public static final String COMMAND_ID = "commandId";
    public static final String SESSION_ID = "sessionId";
    public static final String HOOK_EVENT = "hookEvent";
    public static final String SOURCE = "source";
    public static final String TRANSCRIPT_PATH = "transcriptPath";
    public static final String AT = "at";
    public static final String TOPIC = "topic";
    public static final String NONCE = "nonce";
    public static final String PATH = "path";
    // Optional on OpenStream: written only for a non-default target, so an absent key decodes to
    // StreamTarget.API and the frame an older host sends is byte-identical to the one it always
    // sent. See DaemonCodec's OpenStream arms.
    public static final String TARGET = "target";
    // Optional on AgentActivity (qits-895, capability 8): written only when the daemon has a
    // verdict, so a frame with none — SessionStart, an unrecognized Notification, a Stop whose
    // arrays are absent or not arrays — is byte-identical to one built before this field existed.
    public static final String AWAITING_INPUT = "awaitingInput";

    private Field() {}
  }

  /**
   * The {@code state} values an {@link AgentActivity} frame carries. The wire uses a plain String
   * (like {@link GitStatus}'s primitive {@code boolean}) so the framework-free protocol module
   * stays free of any {@code domain} enum; the daemon renders these constants and the backend's
   * {@code AgentActivityState} enum mirrors them by name.
   */
  public static final class AgentState {
    /** Session established, or a turn finished and control yielded back. */
    public static final String IDLE = "IDLE";

    /** Prompt submitted — the agent is generating a response. */
    public static final String BUSY = "BUSY";

    /** Blocked on the user (permission prompt / idle input). */
    public static final String WAITING = "WAITING";

    /** Session over. */
    public static final String ENDED = "ENDED";

    private AgentState() {}
  }

  /**
   * The {@code hookEvent} values of the {@link AgentActivity} frames the daemon sends <em>without</em>
   * a hook — always with {@link AgentState#ENDED}. Named like the harness's own events so a reader
   * of the host's log cannot mistake one for the other, and never a name Claude Code or Kimi Code
   * fires.
   *
   * <p>An agent killed by SIGKILL (exit 137) runs no hook, so the daemon reports its end from the
   * process exit. Which of the two it is depends on the container's cgroup v2 {@code memory.events}:
   * an {@code oom_kill} counted while the command ran is the OOM killer; no count, or no cgroup v2 to
   * read, is a plain SIGKILL — still a death mid-turn, and still not a turn that finished.
   */
  public static final class AgentEvent {
    /** The cgroup's out-of-memory killer took the agent's process. */
    public static final String OOM_KILLED = "OomKilled";

    /** The agent's process died by SIGKILL, and the cgroup counted no OOM kill for it. */
    public static final String KILLED = "Killed";

    private AgentEvent() {}
  }
}
