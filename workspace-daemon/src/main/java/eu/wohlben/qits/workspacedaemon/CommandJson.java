package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.commands.ActionResolver;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandLogLine;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Serializes {@code qits-commands}' result records to the JSON {@link WorkspaceApi} answers with.
 * The third of the module's serializers, alongside {@link WorkspaceJson} and {@link ConfigJson},
 * and deliberately the same shape: hand-built {@code JsonObject}s, because the native daemon
 * carries no Jackson and a databind reflection registration is exactly what the image builder would
 * have to be told about.
 *
 * <p><b>Every key here is a wire contract, not a naming choice.</b> These bodies deserialize into
 * the host's existing {@code CommandDto} / {@code CommandLogLineDto} / {@code AgentSessionRefDto}
 * record tree, which is what the SPA's Commands list, terminal and chat views already consume. The
 * whole point of the move is that the frontend contract does not change; renaming a key here breaks
 * the Commands UX rather than failing a build.
 *
 * <h2>The two keys the daemon has to synthesize</h2>
 *
 * <p>{@code repoId} and {@code workspaceId} are on {@code CommandDto} but no longer on {@link
 * Command} — the host read them off the command's {@code Workspace} relation, and inside the
 * container they are ambient. They come from {@link DaemonWorkspaceContext} instead, so the body
 * the host receives is identical to the one it used to build.
 *
 * <p>{@code shortCommitHash} was a MapStruct {@code expression} on the host mapper rather than a
 * stored column. It is reproduced here rather than being left to the host, because the host maps
 * these bodies straight onto the DTO and a missing component would decode to null — the Commands
 * list would silently lose its commit column.
 *
 * <p>Instants are ISO-8601 strings, which is what Jackson's {@code JavaTimeModule} reads back into
 * {@link Instant} on the host; the alternative (epoch millis) decodes too, but not into the same
 * value for a null.
 */
final class CommandJson {

  private static final Logger LOG = Logger.getLogger(CommandJson.class);

  private CommandJson() {}

  /** One command, in the shape the host's {@code CommandDto} reconstructs. */
  static JsonObject command(Command command, String repoId, String workspaceId) {
    JsonArray sessions = new JsonArray();
    for (AgentSessionRef session : command.agentSessions()) {
      sessions.add(agentSession(session));
    }
    JsonObject body =
        new JsonObject()
            .put("id", command.id())
            .put("repoId", repoId)
            .put("workspaceId", workspaceId)
            .put("branch", command.branch())
            .put("actionName", command.actionName())
            .put("status", command.status().name())
            .put("interactive", command.interactive())
            .put("kind", command.kind().name())
            .put("launchedAt", iso(command.launchedAt()))
            .put("agentSessions", sessions);
    // Absent optionals are omitted rather than emitted as explicit nulls, matching WorkspaceJson:
    // Jackson maps a missing component to null when it reconstructs a record, so the host sees the
    // same value either way.
    putIfPresent(body, "commitHash", command.commitHash());
    putIfPresent(body, "shortCommitHash", shortCommitHash(command.commitHash()));
    putIfPresent(body, "actionId", command.actionId());
    // WHERE IN THE PRODUCT THIS SESSION WAS STARTED FROM, coming back on the answer so a caller
    // never has to infer it. Absent for a non-agent command, for the sign-in terminal, and for
    // every agent command launched before the daemon learned to accept the field — which is why it
    // is omitted rather than emitted as an empty string: a reader can tell "not an agent session"
    // from "an agent session that predates this".
    //
    // It is what lets the frontend stop matching a display string. The surface distinction that
    // existed before this was carried in CommandDto.actionName — the projects frontend sorted a
    // project's sessions by looking for "(tickets desk)" in it — which made a label a cross-repo
    // contract, and renaming the label silently moved every ticket session into the wrong list.
    putIfPresent(body, "agentSurface", command.agentSurface());
    putLaunchRecord(body, command);
    if (command.exitCode() != null) {
      body.put("exitCode", command.exitCode());
    }
    putIfPresent(body, "finishedAt", iso(command.finishedAt()));
    return body;
  }

  /** {@code GET /commands} — the list, newest first. */
  static JsonObject commands(List<Command> commands, String repoId, String workspaceId) {
    JsonArray entries = new JsonArray();
    for (Command command : commands) {
      // The host's ListCommandsRequest.Response wraps each row in an Entry record with a single
      // `command` component; keeping that envelope means its controller needs no translation.
      entries.add(new JsonObject().put("command", command(command, repoId, workspaceId)));
    }
    return new JsonObject().put("entries", entries);
  }

  /** {@code POST /commands} — the launch response, same envelope as the host's. */
  static JsonObject launched(Command command, String repoId, String workspaceId) {
    return new JsonObject().put("command", command(command, repoId, workspaceId));
  }

  /** {@code GET /commands/{id}/log} — the captured lines in order. */
  static JsonObject log(List<CommandLogLine> lines) {
    JsonArray entries = new JsonArray();
    for (CommandLogLine line : lines) {
      JsonObject entry =
          new JsonObject()
              .put("sequence", line.sequence())
              .put("channel", line.channel().name())
              .put("content", line.content())
              .put("timestamp", iso(line.timestamp()));
      if (line.severity() != null) {
        entry.put("severity", line.severity().name());
      }
      entries.add(entry);
    }
    return new JsonObject().put("lines", entries);
  }

  /** One entry of a command's ordered agent-session list. */
  private static JsonObject agentSession(AgentSessionRef session) {
    JsonObject body =
        new JsonObject()
            .put("sessionId", session.sessionId())
            .put("source", session.source().name())
            .put("recordedAt", iso(session.recordedAt()));
    putIfPresent(body, "forkedFromSessionId", session.forkedFromSessionId());
    putIfPresent(body, "transcriptPath", session.transcriptPath());
    return body;
  }

  /**
   * {@code GET /commands/actions} — what this checkout declares, and therefore what {@code POST
   * /commands} will accept. New: the host had no equivalent, because actions lived in its own
   * featureflow tables and it already knew them. Now they come from the checkout's
   * {@code .qits-config.yml}, which only the daemon reads.
   */
  static JsonObject actions(List<ActionResolver.ResolvedAction> actions) {
    JsonArray entries = new JsonArray();
    for (ActionResolver.ResolvedAction action : actions) {
      entries.add(
          new JsonObject()
              .put("id", action.id())
              .put("name", action.name())
              .put("interactive", action.interactive()));
    }
    return new JsonObject().put("actions", entries);
  }

  /**
   * {@code agentLaunchRecord} — <b>what the session was actually launched with</b>: its surface,
   * harness, model, effort, permission mode, remote control, activity tracking, the platform MCP
   * servers it attached and the catalog entries it attached by key.
   *
   * <p>This is the daemon where it is worth the most, for the same reason the surface is. A
   * container keeps the configuration document it was born with for its whole life and an edit in
   * qits-projects applies to the next container, so the store cannot answer what a session that
   * behaved oddly last week ran with — only the record can, and until this key existed the record
   * was written at launch and readable by nobody: it lives on a command inside the container, and
   * {@code GET /commands} is the only door out. The epic's per-surface verification reads it rather
   * than the logs.
   *
   * <p>Served as a nested <b>object</b> rather than the string the command stores. The string is
   * the library's storage form — {@code qits-commands} keeps the record opaque on purpose — and a
   * caller that had to parse a JSON document out of a JSON string would be paying for that choice.
   * {@code AgentLaunchRecord.toJson} is the only writer on this path, so the parse cannot fail in
   * practice; it is guarded anyway, because one unreadable row must not take the whole Commands
   * list down with it, and the WARN is what stops the omission being silent.
   *
   * <p><b>No credential can appear here.</b> The record names attached external servers by key —
   * never a url, a header name or a header value — and this method reshapes nothing, so what is
   * served is what the library built. The rendered command line is a different field and is stored
   * already redacted ({@code AgentLaunchMetadata.redact}); nothing may be added here that
   * reintroduces a value either of those two deliberately keeps out.
   */
  private static void putLaunchRecord(JsonObject body, Command command) {
    String record = command.agentLaunchRecord();
    if (record == null || record.isBlank()) {
      // Absent, not null: a non-agent command has no record, the sign-in terminal has none, and
      // neither has an agent command launched before a launch recorded itself. All three stay
      // distinguishable from a session that ran with an empty configuration, which is an object.
      return;
    }
    try {
      body.put("agentLaunchRecord", new JsonObject(record));
    } catch (RuntimeException notJson) {
      LOG.warnf(
          "Command %s carries a launch record that is not a JSON object; omitting it from the"
              + " answer rather than failing the read",
          command.id());
    }
  }

  /** The host computed this in the mapper; reproduced so the DTO component is never null. */
  private static String shortCommitHash(String commitHash) {
    if (commitHash == null) {
      return null;
    }
    return commitHash.length() >= 7 ? commitHash.substring(0, 7) : commitHash;
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static void putIfPresent(JsonObject body, String key, String value) {
    if (value != null) {
      body.put(key, value);
    }
  }
}
