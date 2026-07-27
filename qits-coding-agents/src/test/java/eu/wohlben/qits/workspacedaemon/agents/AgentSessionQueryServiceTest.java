package eu.wohlben.qits.workspacedaemon.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.commands.AgentSessionRef;
import eu.wohlben.qits.workspacedaemon.commands.AgentSessionSource;
import eu.wohlben.qits.workspacedaemon.commands.CommandKind;
import eu.wohlben.qits.workspacedaemon.commands.CommandLifecycleService;
import eu.wohlben.qits.workspacedaemon.commands.CommandStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The session tree: collapse onto sessions, nest forks, attach the sweep's aggregates. */
class AgentSessionQueryServiceTest {

  private static final Instant T0 = Instant.parse("2026-07-27T08:00:00Z");

  private CommandStore store;
  private CommandLifecycleService lifecycle;
  private AgentSessionStore sessionStore;
  private AgentSessionQueryService service;

  @BeforeEach
  void setUp() {
    store = new CommandStore();
    lifecycle = new CommandLifecycleService(store, null);
    sessionStore = new AgentSessionStore();
    service = new AgentSessionQueryService(store, sessionStore);
  }

  private void command(String id, AgentSessionRef session) {
    lifecycle.createRunning(
        "main", "abc", "agent", "Agent", "exec claude", false, CommandKind.CHAT, id, session, "CLAUDE");
  }

  private static AgentSessionRef pinned(String sessionId, Instant at) {
    return new AgentSessionRef(sessionId, AgentSessionSource.PINNED, null, null, at);
  }

  private static AgentSessionRef forkOf(String sessionId, String parent, Instant at) {
    return new AgentSessionRef(sessionId, AgentSessionSource.FORKED, parent, null, at);
  }

  @Test
  void severalCommandsOnOneSessionCollapseOntoOneNode() {
    command("older", pinned("s1", T0));
    command("newer", pinned("s1", T0.plusSeconds(60)));

    List<AgentSessionNodeDto> tree = service.sessionTree();

    assertEquals(1, tree.size());
    assertEquals("s1", tree.get(0).sessionId());
    assertEquals("newer", tree.get(0).newestCommandId(), "the newest command is the nav target");
    assertEquals(T0, tree.get(0).firstRecordedAt(), "the node keeps the earliest sighting");
  }

  @Test
  void aForkNestsUnderTheSessionItBranchedFrom() {
    command("c1", pinned("parent", T0));
    command("c2", forkOf("child", "parent", T0.plusSeconds(30)));

    List<AgentSessionNodeDto> tree = service.sessionTree();

    assertEquals(1, tree.size(), "only the origin is a root");
    assertEquals("parent", tree.get(0).sessionId());
    assertEquals(1, tree.get(0).children().size());
    assertEquals("child", tree.get(0).children().get(0).sessionId());
    assertEquals("parent", tree.get(0).children().get(0).forkedFromSessionId());
  }

  @Test
  void aForkOfASessionThisContainerNeverDroveIsItsOwnRoot() {
    // The lineage survived on the shared volume but the command store did not -- a resume after a
    // container recreate produces exactly this shape, so it must render rather than disappear.
    command("c1", forkOf("orphan", "gone-with-the-container", T0));

    List<AgentSessionNodeDto> tree = service.sessionTree();

    assertEquals(1, tree.size());
    assertEquals("orphan", tree.get(0).sessionId());
    assertTrue(tree.get(0).children().isEmpty());
  }

  @Test
  void rootsAreNewestFirstAndForkChildrenChronological() {
    command("c1", pinned("old", T0));
    command("c2", pinned("new", T0.plusSeconds(120)));
    command("c3", forkOf("fork-late", "old", T0.plusSeconds(90)));
    command("c4", forkOf("fork-early", "old", T0.plusSeconds(30)));

    List<AgentSessionNodeDto> tree = service.sessionTree();

    assertEquals(List.of("new", "old"), tree.stream().map(AgentSessionNodeDto::sessionId).toList());
    assertEquals(
        List.of("fork-early", "fork-late"),
        tree.get(1).children().stream().map(AgentSessionNodeDto::sessionId).toList());
  }

  @Test
  void sweptStatsAttachAsCountsAndSubagentRows() {
    command("c1", pinned("s1", T0));
    sessionStore.replace(
        List.of("s1"),
        List.of(
            new AgentSessionStore.Stat("c1", "s1", null, null, null, 7, T0),
            new AgentSessionStore.Stat("c1", "s1", "b", "Explore", "second", 2, T0.plusSeconds(20)),
            new AgentSessionStore.Stat("c1", "s1", "a", "Plan", "first", 3, T0.plusSeconds(10))));

    AgentSessionNodeDto node = service.sessionTree().get(0);

    assertEquals(7, node.messageCount());
    assertEquals(
        List.of("a", "b"),
        node.subagents().stream().map(AgentSubagentDto::agentId).toList(),
        "subagents sort by first timestamp");
    assertEquals("Plan", node.subagents().get(0).agentType());
  }

  @Test
  void aSessionWithNoSweepYetHasANullCount() {
    command("c1", pinned("s1", T0));

    assertNull(
        service.sessionTree().get(0).messageCount(),
        "null means not swept yet, which the UI renders differently from zero");
  }

  @Test
  void anEmptyStoreIsAnEmptyTree() {
    assertTrue(service.sessionTree().isEmpty());
  }

  @Test
  void nonAgentCommandsContributeNoNodes() {
    lifecycle.createRunning(
        "main", "abc", "build", "Build", "make", false, CommandKind.TERMINAL, "c1", null, null);

    assertTrue(service.sessionTree().isEmpty());
  }
}
