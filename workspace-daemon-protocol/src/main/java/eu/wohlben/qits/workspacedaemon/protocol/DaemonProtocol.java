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
 * stub. Later parts extend it (ReadFile, StartService, …).
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
   */
  public static final int CAPABILITY_VERSION = 3;

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

  /**
   * The prefix a running service's streamed stdout/stderr ({@link CommandChunk}) is correlated
   * with, so the backend routes those chunks to the workspace's {@code service:<name>} process
   * segment / log observers (docs/epics/qits-workspace-daemon/ Part 4). Like {@link
   * #BOOTSTRAP_CORRELATION_PREFIX}, a service's output is a continuous stream, not a request/reply
   * round-trip, so its correlation is a well-known value both sides compute from the service name.
   */
  public static final String SERVICE_CORRELATION_PREFIX = "service:";

  /**
   * The output correlation id for a running service — {@link #SERVICE_CORRELATION_PREFIX}{@code +
   * name}.
   */
  public static String serviceCorrelationId(String serviceName) {
    return SERVICE_CORRELATION_PREFIX + serviceName;
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
    // The workspace-service messages keep their pre-rename wire tags ("daemon*") so stale daemon
    // images keep speaking to a newer qits; retag them with the next CAPABILITY_VERSION bump.
    public static final String SERVICE_TRANSITION = "daemonEvent";
    public static final String GIT_STATUS = "gitStatus";
    public static final String AGENT_ACTIVITY = "agentActivity";
    public static final String WORKSPACE_CHANGED = "workspaceChanged";
    // qits -> workspace-daemon
    public static final String ACK = "ack";
    public static final String RUN_COMMAND = "runCommand";
    public static final String DESCRIBE = "describe";
    public static final String DESCRIBE_CONFIG = "describeConfig";
    public static final String RUN_BOOTSTRAP = "runBootstrap";
    // Pre-rename wire tags kept for stale daemon images — see SERVICE_TRANSITION above.
    public static final String START_SERVICE = "startDaemon";
    public static final String SIGNAL_SERVICE = "signalDaemon";
    public static final String PULL_BRANCH = "pullBranch";

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
    public static final String ID = "id";
    public static final String SCRIPT = "script";
    public static final String SIGNAL = "signal";
    public static final String STATE = "state";
    public static final String COMMAND_ID = "commandId";
    public static final String SESSION_ID = "sessionId";
    public static final String HOOK_EVENT = "hookEvent";
    public static final String SOURCE = "source";
    public static final String TRANSCRIPT_PATH = "transcriptPath";
    public static final String AT = "at";
    public static final String TOPIC = "topic";

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
}
