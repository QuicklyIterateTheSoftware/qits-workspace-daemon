package eu.wohlben.qits.workspacedaemon.agents;

import eu.wohlben.qits.workspacedaemon.commands.AgentSessionRef;
import eu.wohlben.qits.workspacedaemon.commands.Command;
import eu.wohlben.qits.workspacedaemon.commands.CommandStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles this workspace's agent sessions into the tree the session-history UI renders: one node
 * per session (commands that resumed a session collapse onto it), fork edges nesting children under
 * their {@code forkedFromSessionId}, and the sweep-aggregated stats attached as message counts and
 * subagent rows.
 *
 * <p>The host took {@code (repoId, workspaceId)} and checked the workspace existed before querying.
 * Inside the container both are ambient — every command in the store belongs to this workspace by
 * construction — so the arguments and the existence check are gone.
 *
 * <p>The tree therefore only ever shows sessions <em>this container</em> drove: {@link CommandStore}
 * does not outlive it. The transcripts do (they are on the shared volume), but nothing here indexes
 * them. See {@link AgentSessionStore}.
 */
public final class AgentSessionQueryService {

  private final CommandStore store;
  private final AgentSessionStore sessionStore;

  public AgentSessionQueryService(CommandStore store, AgentSessionStore sessionStore) {
    this.store = store;
    this.sessionStore = sessionStore;
  }

  /** The workspace's session tree — roots newest-first, fork children chronological. */
  public List<AgentSessionNodeDto> sessionTree() {
    // Newest command first, so the first command seen per session is its navigation target.
    List<Command> commands = store.listByLaunchedAtDesc();
    Map<String, Node> nodes = new LinkedHashMap<>();
    for (Command command : commands) {
      for (AgentSessionRef ref : command.agentSessions()) {
        Node node = nodes.computeIfAbsent(ref.sessionId(), Node::new);
        if (node.newestCommandId == null) {
          node.newestCommandId = command.id();
        }
        if (node.firstRecordedAt == null || ref.recordedAt().isBefore(node.firstRecordedAt)) {
          node.firstRecordedAt = ref.recordedAt();
        }
        if (node.forkedFromSessionId == null) {
          node.forkedFromSessionId = ref.forkedFromSessionId();
        }
      }
    }

    for (AgentSessionStore.Stat stat : sessionStore.findBySessionIds(nodes.keySet())) {
      Node node = nodes.get(stat.sessionId());
      if (node == null) {
        continue;
      }
      if (stat.agentId() == null) {
        node.messageCount = stat.messageCount();
      } else {
        node.subagents.add(
            new AgentSubagentDto(
                stat.agentId(),
                stat.agentType(),
                stat.description(),
                stat.messageCount(),
                stat.firstTimestamp()));
      }
    }

    // Fork edges nest children under their origin; an edge to a session this workspace never
    // drove (or none at all) makes the node a root.
    List<Node> roots = new ArrayList<>();
    for (Node node : nodes.values()) {
      Node parent = node.forkedFromSessionId == null ? null : nodes.get(node.forkedFromSessionId);
      if (parent != null && parent != node) {
        parent.children.add(node);
      } else {
        roots.add(node);
      }
    }
    roots.sort(Comparator.comparing((Node n) -> n.firstRecordedAt).reversed());
    return roots.stream().map(Node::toDto).toList();
  }

  private static class Node {
    private final String sessionId;
    private String newestCommandId;
    private Instant firstRecordedAt;
    private String forkedFromSessionId;
    private Integer messageCount;
    private final List<AgentSubagentDto> subagents = new ArrayList<>();
    private final List<Node> children = new ArrayList<>();

    private Node(String sessionId) {
      this.sessionId = sessionId;
    }

    private AgentSessionNodeDto toDto() {
      subagents.sort(
          Comparator.comparing(
                  AgentSubagentDto::firstTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(AgentSubagentDto::agentId));
      children.sort(Comparator.comparing(n -> n.firstRecordedAt));
      return new AgentSessionNodeDto(
          sessionId,
          firstRecordedAt,
          forkedFromSessionId,
          messageCount,
          newestCommandId,
          List.copyOf(subagents),
          children.stream().map(Node::toDto).toList());
    }
  }
}
