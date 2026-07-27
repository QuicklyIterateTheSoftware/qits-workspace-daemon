package eu.wohlben.qits.workspacedaemon.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launch path, end to end, with the two things the move changed most: actions resolved from the
 * checkout's own config instead of the host's featureflow tables, and no workspace/repository
 * arguments anywhere.
 */
@EnabledOnOs(OS.LINUX)
class CommandServiceTest {

  private static final WorkspaceContext WORKSPACE =
      new WorkspaceContext() {
        @Override
        public String repoId() {
          return "repo-1";
        }

        @Override
        public String workspaceId() {
          return "feature-x";
        }

        @Override
        public String branch() {
          return "feature/x";
        }

        @Override
        public String commitHash() {
          return "0123456789abcdef";
        }
      };

  /** Stands in for the daemon module's reader over {@code .qits-config.yml}. */
  private record DeclaredActions(List<ResolvedAction> declared) implements ActionResolver {
    @Override
    public Optional<ResolvedAction> resolve(String actionId) {
      return declared.stream().filter(action -> action.id().equals(actionId)).findFirst();
    }

    @Override
    public List<ResolvedAction> actions() {
      return declared;
    }
  }

  private record Harness(CommandService service, CommandStore store, CommandRegistry registry) {}

  private static Harness harness(Path workspace, ActionResolver actions) {
    CommandStore store = new CommandStore();
    CommandLogService logs = new CommandLogService(store, null);
    CommandLifecycleService lifecycle = new CommandLifecycleService(store, null);
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CommandService service =
        new CommandService(store, registry, lifecycle, logs, WORKSPACE, actions, null);
    return new Harness(service, store, registry);
  }

  @Test
  void launchesADeclaredActionAndSnapshotsTheCheckout(@TempDir Path workspace) throws Exception {
    ActionResolver actions =
        new DeclaredActions(
            List.of(
                new ActionResolver.ResolvedAction(
                    "build", "Build", "echo building", false, Map.of())));
    Harness h = harness(workspace, actions);

    Command command = h.service().launch("build");

    assertEquals("build", command.actionId());
    assertEquals("Build", command.actionName());
    assertEquals("feature/x", command.branch(), "the branch comes from the workspace context");
    assertEquals("0123456789abcdef", command.commitHash());
    assertEquals(CommandKind.TERMINAL, command.kind());
    waitForFinish(h, command.id());
  }

  @Test
  void anUndeclaredActionIsARequestError(@TempDir Path workspace) {
    Harness h = harness(workspace, new DeclaredActions(List.of()));

    InvalidCommandRequestException thrown =
        assertThrows(InvalidCommandRequestException.class, () -> h.service().launch("nope"));
    assertTrue(thrown.getMessage().contains(".qits-config.yml"), "got: " + thrown.getMessage());
  }

  @Test
  void anUnknownCommandIsNotFound(@TempDir Path workspace) {
    Harness h = harness(workspace, new DeclaredActions(List.of()));
    assertThrows(CommandNotFoundException.class, () -> h.service().get("never-launched"));
  }

  @Test
  void aFinishedCommandRecordsItsExitCodeAndEndTime(@TempDir Path workspace) throws Exception {
    ActionResolver actions =
        new DeclaredActions(
            List.of(new ActionResolver.ResolvedAction("fail", "Fail", "exit 3", false, Map.of())));
    Harness h = harness(workspace, actions);

    Command launched = h.service().launch("fail");
    Command finished = waitForFinish(h, launched.id());

    assertEquals(CommandStatus.EXITED, finished.status());
    assertEquals(3, finished.exitCode());
    assertNotNull(finished.finishedAt());
  }

  @Test
  void listNarrowsByStatusAndNothingElse(@TempDir Path workspace) throws Exception {
    ActionResolver actions =
        new DeclaredActions(
            List.of(new ActionResolver.ResolvedAction("noop", "Noop", "true", false, Map.of())));
    Harness h = harness(workspace, actions);

    Command one = h.service().launch("noop");
    waitForFinish(h, one.id());

    assertEquals(1, h.service().list(null).size());
    assertEquals(1, h.service().list(CommandStatus.EXITED).size());
    assertEquals(0, h.service().list(CommandStatus.RUNNING).size());
  }

  @Test
  void aSessionReportFillsInTheTranscriptPathWithoutAppending(@TempDir Path workspace)
      throws Exception {
    CommandStore store = new CommandStore();
    CommandLifecycleService lifecycle = new CommandLifecycleService(store, null);
    String sessionId = "11111111-2222-3333-4444-555555555555";
    Command launched =
        lifecycle.createRunning(
            "main",
            "abc",
            null,
            "Agent",
            "claude",
            true,
            CommandKind.TERMINAL,
            "cmd-1",
            new AgentSessionRef(sessionId, AgentSessionSource.PINNED, null, null, Instant.now()),
            "CLAUDE");
    assertNull(launched.currentSession().transcriptPath());

    Command reported =
        lifecycle.recordAgentSessionReport("cmd-1", sessionId, "/claude-home/.claude/x.jsonl");

    assertEquals(1, reported.agentSessions().size(), "the same session must not be appended twice");
    assertEquals("/claude-home/.claude/x.jsonl", reported.currentSession().transcriptPath());
  }

  @Test
  void aDifferentReportedSessionIsAppendedAsSwitched(@TempDir Path workspace) {
    CommandStore store = new CommandStore();
    CommandLifecycleService lifecycle = new CommandLifecycleService(store, null);
    String pinned = "11111111-2222-3333-4444-555555555555";
    String switched = "99999999-8888-7777-6666-555555555555";
    lifecycle.createRunning(
        "main", "abc", null, "Agent", "claude", true, CommandKind.TERMINAL, "cmd-2",
        new AgentSessionRef(pinned, AgentSessionSource.PINNED, null, null, Instant.now()), "CLAUDE");

    Command reported = lifecycle.recordAgentSessionReport("cmd-2", switched, null);

    assertEquals(2, reported.agentSessions().size());
    assertEquals(AgentSessionSource.SWITCHED, reported.currentSession().source());
    assertEquals(switched, reported.currentSession().sessionId());
  }

  @Test
  void aMalformedSessionIdIsRejected(@TempDir Path workspace) {
    Harness h = harness(workspace, new DeclaredActions(List.of()));
    assertThrows(
        InvalidCommandRequestException.class,
        () -> h.service().reportAgentSession("cmd", "not a session id", null));
  }

  /** Poll until the command leaves RUNNING, then return it. */
  private static Command waitForFinish(Harness h, String commandId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      Command command = h.service().get(commandId);
      if (!command.isRunning()) {
        return command;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("command " + commandId + " never finished");
  }
}
