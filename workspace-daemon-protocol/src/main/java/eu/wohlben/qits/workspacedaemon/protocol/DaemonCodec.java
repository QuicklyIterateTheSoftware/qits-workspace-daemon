package eu.wohlben.qits.workspacedaemon.protocol;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.Field;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the control-plane messages become (and un-become) a flat {@code Map<String,Object>}
 * — the wire's lowest common denominator. Framework-free on purpose: the {@code service} backend
 * bridges the map to/from JSON with its Jackson {@code ObjectMapper}, the {@code workspace-daemon}
 * binary with a Vert.x {@code JsonObject} ({@code new JsonObject(map)} / {@code
 * jsonObject.getMap()}), so neither side reimplements the field mapping and a rename lands in
 * exactly one file.
 *
 * <p>Numbers are read through {@link Number} so it doesn't matter whether the JSON layer decoded an
 * {@code int} as {@code Integer} (Jackson) or {@code Long} (Vert.x). Absent optional fields decode
 * to {@code null}.
 */
public final class DaemonCodec {

  private DaemonCodec() {}

  /** Flatten a message to its wire map, including the {@code "type"} discriminator. */
  public static Map<String, Object> encode(DaemonMessage message) {
    Map<String, Object> map = new LinkedHashMap<>();
    switch (message) {
      case Hello m -> {
        map.put(Field.TYPE, Type.HELLO);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.REPO_ID, m.repoId());
        map.put(Field.BRANCH, m.branch());
        map.put(Field.PARENT, m.parent());
        map.put(Field.CAPABILITY_VERSION, m.capabilityVersion());
        map.put(Field.DAEMON_VERSION, m.daemonVersion());
        map.put(Field.DAEMON_BUILD_TIME, m.daemonBuildTime());
      }
      case Heartbeat m -> {
        map.put(Field.TYPE, Type.HEARTBEAT);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
      }
      case DaemonLog m -> {
        map.put(Field.TYPE, Type.CLIENT_LOG);
        map.put(Field.LEVEL, m.level());
        map.put(Field.MESSAGE, m.message());
      }
      case CommandChunk m -> {
        map.put(Field.TYPE, Type.COMMAND_CHUNK);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.STREAM, m.stream().name());
        map.put(Field.TEXT, m.text());
      }
      case CommandExit m -> {
        map.put(Field.TYPE, Type.COMMAND_EXIT);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.EXIT_CODE, m.exitCode());
      }
      case WorkspaceInfo m -> {
        map.put(Field.TYPE, Type.WORKSPACE_INFO);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.REPO_ID, m.repoId());
        map.put(Field.BRANCH, m.branch());
        map.put(Field.PARENT, m.parent());
        map.put(Field.HEAD, m.head());
        map.put(Field.DIRTY, m.dirty());
      }
      case Provisioned m -> {
        map.put(Field.TYPE, Type.PROVISIONED);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.HEAD, m.head());
      }
      case ProvisionFailed m -> {
        map.put(Field.TYPE, Type.PROVISION_FAILED);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.MESSAGE, m.message());
      }
      case ConfigView m -> {
        map.put(Field.TYPE, Type.CONFIG_VIEW);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.CONFIG_JSON, m.configJson());
        map.put(Field.WARNING, m.warning());
      }
      case BootstrapStep m -> {
        map.put(Field.TYPE, Type.BOOTSTRAP_STEP);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.NAME, m.name());
        map.put(Field.PHASE, m.phase());
      }
      case BootstrapOutcome m -> {
        map.put(Field.TYPE, Type.BOOTSTRAP_OUTCOME);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.NAME, m.name());
        map.put(Field.OUTCOME, m.outcome());
        map.put(Field.EXIT_CODE, m.exitCode());
      }
      case Bootstrapped m -> {
        map.put(Field.TYPE, Type.BOOTSTRAPPED);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.OK, m.ok());
      }
      case ServiceTransition m -> {
        map.put(Field.TYPE, Type.SERVICE_TRANSITION);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.ID, m.id());
        map.put(Field.STATE, m.state());
        map.put(Field.EXIT_CODE, m.exitCode());
      }
      case WorkspaceChanged m -> {
        map.put(Field.TYPE, Type.WORKSPACE_CHANGED);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.TOPIC, m.topic());
      }
      case GitStatus m -> {
        map.put(Field.TYPE, Type.GIT_STATUS);
        map.put(Field.WORKSPACE_ID, m.workspaceId());
        map.put(Field.CLEAN, m.clean());
        map.put(Field.HEAD, m.head());
      }
      case AgentActivity m -> {
        map.put(Field.TYPE, Type.AGENT_ACTIVITY);
        map.put(Field.COMMAND_ID, m.commandId());
        map.put(Field.SESSION_ID, m.sessionId());
        map.put(Field.STATE, m.state());
        map.put(Field.HOOK_EVENT, m.hookEvent());
        map.put(Field.SOURCE, m.source());
        map.put(Field.TRANSCRIPT_PATH, m.transcriptPath());
        map.put(Field.AT, m.at());
      }
      case Ack _ -> map.put(Field.TYPE, Type.ACK); // no fields beyond the discriminator
      case RunCommand m -> {
        map.put(Field.TYPE, Type.RUN_COMMAND);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.ARGV, m.argv() == null ? List.of() : new ArrayList<>(m.argv()));
        map.put(Field.CWD, m.cwd());
        map.put(Field.ENV, m.env() == null ? Map.of() : new LinkedHashMap<>(m.env()));
      }
      case Describe m -> {
        map.put(Field.TYPE, Type.DESCRIBE);
        map.put(Field.CORRELATION_ID, m.correlationId());
      }
      case DescribeConfig m -> {
        map.put(Field.TYPE, Type.DESCRIBE_CONFIG);
        map.put(Field.CORRELATION_ID, m.correlationId());
      }
      case RunBootstrap m -> {
        map.put(Field.TYPE, Type.RUN_BOOTSTRAP);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.NAME, m.name());
      }
      case StartService m -> {
        map.put(Field.TYPE, Type.START_SERVICE);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.ID, m.id());
        map.put(Field.SCRIPT, m.script());
        map.put(Field.ENV, m.env() == null ? Map.of() : new LinkedHashMap<>(m.env()));
      }
      case SignalService m -> {
        map.put(Field.TYPE, Type.SIGNAL_SERVICE);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.ID, m.id());
        map.put(Field.SIGNAL, m.signal());
      }
      case PullBranch m -> {
        map.put(Field.TYPE, Type.PULL_BRANCH);
        map.put(Field.CORRELATION_ID, m.correlationId());
        map.put(Field.BRANCH, m.branch());
      }
    }
    return map;
  }

  /** Rebuild a message from its wire map, dispatching on the {@code "type"} discriminator. */
  public static DaemonMessage decode(Map<String, Object> map) {
    String type = str(map, Field.TYPE);
    if (type == null) {
      throw new IllegalArgumentException(
          "workspace-daemon message has no '" + Field.TYPE + "' field");
    }
    return switch (type) {
      case Type.HELLO ->
          new Hello(
              str(map, Field.WORKSPACE_ID),
              str(map, Field.REPO_ID),
              str(map, Field.BRANCH),
              str(map, Field.PARENT),
              intVal(map, Field.CAPABILITY_VERSION),
              str(map, Field.DAEMON_VERSION),
              str(map, Field.DAEMON_BUILD_TIME));
      case Type.HEARTBEAT -> new Heartbeat(str(map, Field.WORKSPACE_ID));
      case Type.CLIENT_LOG -> new DaemonLog(str(map, Field.LEVEL), str(map, Field.MESSAGE));
      case Type.COMMAND_CHUNK ->
          new CommandChunk(
              str(map, Field.CORRELATION_ID),
              Stream.valueOf(str(map, Field.STREAM)),
              str(map, Field.TEXT));
      case Type.COMMAND_EXIT ->
          new CommandExit(str(map, Field.CORRELATION_ID), intVal(map, Field.EXIT_CODE));
      case Type.WORKSPACE_INFO ->
          new WorkspaceInfo(
              str(map, Field.WORKSPACE_ID),
              str(map, Field.REPO_ID),
              str(map, Field.BRANCH),
              str(map, Field.PARENT),
              str(map, Field.HEAD),
              boolVal(map, Field.DIRTY));
      case Type.PROVISIONED -> new Provisioned(str(map, Field.WORKSPACE_ID), str(map, Field.HEAD));
      case Type.PROVISION_FAILED ->
          new ProvisionFailed(str(map, Field.WORKSPACE_ID), str(map, Field.MESSAGE));
      case Type.CONFIG_VIEW ->
          new ConfigView(
              str(map, Field.WORKSPACE_ID),
              str(map, Field.CORRELATION_ID),
              str(map, Field.CONFIG_JSON),
              str(map, Field.WARNING));
      case Type.BOOTSTRAP_STEP ->
          new BootstrapStep(
              str(map, Field.WORKSPACE_ID), str(map, Field.NAME), str(map, Field.PHASE));
      case Type.BOOTSTRAP_OUTCOME ->
          new BootstrapOutcome(
              str(map, Field.WORKSPACE_ID),
              str(map, Field.NAME),
              str(map, Field.OUTCOME),
              intVal(map, Field.EXIT_CODE));
      case Type.BOOTSTRAPPED ->
          new Bootstrapped(str(map, Field.WORKSPACE_ID), boolVal(map, Field.OK));
      case Type.SERVICE_TRANSITION ->
          new ServiceTransition(
              str(map, Field.WORKSPACE_ID),
              str(map, Field.ID),
              str(map, Field.STATE),
              intObj(map, Field.EXIT_CODE));
      case Type.WORKSPACE_CHANGED ->
          new WorkspaceChanged(str(map, Field.WORKSPACE_ID), str(map, Field.TOPIC));
      case Type.GIT_STATUS ->
          new GitStatus(
              str(map, Field.WORKSPACE_ID), boolVal(map, Field.CLEAN), str(map, Field.HEAD));
      case Type.AGENT_ACTIVITY ->
          new AgentActivity(
              str(map, Field.COMMAND_ID),
              str(map, Field.SESSION_ID),
              str(map, Field.STATE),
              str(map, Field.HOOK_EVENT),
              str(map, Field.SOURCE),
              str(map, Field.TRANSCRIPT_PATH),
              longVal(map, Field.AT));
      case Type.ACK -> new Ack();
      case Type.RUN_COMMAND ->
          new RunCommand(
              str(map, Field.CORRELATION_ID),
              strList(map, Field.ARGV),
              str(map, Field.CWD),
              strMap(map, Field.ENV));
      case Type.DESCRIBE -> new Describe(str(map, Field.CORRELATION_ID));
      case Type.DESCRIBE_CONFIG -> new DescribeConfig(str(map, Field.CORRELATION_ID));
      case Type.RUN_BOOTSTRAP ->
          new RunBootstrap(str(map, Field.CORRELATION_ID), str(map, Field.NAME));
      case Type.START_SERVICE ->
          new StartService(
              str(map, Field.CORRELATION_ID),
              str(map, Field.ID),
              str(map, Field.SCRIPT),
              strMap(map, Field.ENV));
      case Type.SIGNAL_SERVICE ->
          new SignalService(
              str(map, Field.CORRELATION_ID), str(map, Field.ID), str(map, Field.SIGNAL));
      case Type.PULL_BRANCH ->
          new PullBranch(str(map, Field.CORRELATION_ID), str(map, Field.BRANCH));
      default ->
          throw new IllegalArgumentException("unknown workspace-daemon message type: " + type);
    };
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : value.toString();
  }

  private static int intVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static Integer intObj(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Number number ? number.intValue() : null;
  }

  private static long longVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static boolean boolVal(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Boolean bool && bool;
  }

  private static List<String> strList(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object element : list) {
      out.add(element == null ? null : element.toString());
    }
    return out;
  }

  private static Map<String, String> strMap(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      out.put(
          String.valueOf(entry.getKey()),
          entry.getValue() == null ? null : entry.getValue().toString());
    }
    return out;
  }
}
