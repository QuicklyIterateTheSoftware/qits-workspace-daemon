package eu.wohlben.qits.workspacedaemon.agents;

import static eu.wohlben.qits.workspacedaemon.agents.McpServers.httpMcp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-renderer tests for the coding-agent builder — no CDI, just the shell it produces. */
public class CodingAgentFactoryTest {

  private static final String ACTIONS_URL =
      "http://localhost:8080/actions/mcp?repositoryId=11111111-1111-1111-1111-111111111111";

  @Test
  public void interactiveWithoutMcpJustExecsClaude() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).start();

    assertEquals("exec claude", spec.script());
    assertTrue(spec.interactive());
  }

  @Test
  public void flatOutputAddsTheScreenReaderFlag() {
    // Opt-in (off by default); renders flat text so a captured PTY session log stays readable.
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).flatOutput().start();

    assertEquals("exec claude --ax-screen-reader", spec.script());
  }

  @Test
  public void interactiveWithMcpRendersStrictConfigAndAllowlist() {
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .mcpServer("actions", httpMcp(ACTIONS_URL))
            .allowedTools(
                List.of("mcp__actions__listGlobalActions", "mcp__actions__getGlobalAction"))
            .start();

    String cmd = spec.script();
    assertTrue(cmd.startsWith("exec claude "), cmd);
    assertTrue(cmd.contains("--strict-mcp-config"), cmd);
    assertTrue(
        cmd.contains(
            "--mcp-config '{\"mcpServers\":{\"actions\":{\"type\":\"http\",\"url\":\""
                + ACTIONS_URL
                + "\"}}}'"),
        cmd);
    assertTrue(
        cmd.contains(
            "--allowedTools 'mcp__actions__listGlobalActions,mcp__actions__getGlobalAction'"),
        cmd);
  }

  @Test
  public void interactiveWithInitialContextEmbedsTheSeedDirectly() {
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .initialContext("please look at the failing test")
            .start();

    assertEquals("exec claude 'please look at the failing test'", spec.script());
  }

  @Test
  public void runRendersPrintModeWithTheEmbeddedPrompt() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).run("resolve the conflict");

    assertEquals("claude -p 'resolve the conflict'", spec.script());
    assertFalse(spec.interactive());
  }

  @Test
  public void promptWithShellMetacharactersStaysASingleArgument() {
    // An untrusted prompt (e.g. built from attacker-controlled commit messages) full of shell
    // metacharacters: a single quote, a command separator, a command substitution and backticks.
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).run("a'b`c$(d);e");

    // POSIX single-quoting neutralizes all of them: everything is literal inside '…', and the one
    // embedded ' is escaped as '\'' so it can't close the quote early. The whole prompt is one arg
    // —
    // no separator runs, no substitution expands.
    assertEquals("claude -p 'a'\\''b`c$(d);e'", spec.script());
  }

  @Test
  public void modelFlagRendersOnEveryLaunchVariant() {
    assertEquals(
        "claude -p 'hi' --model 'haiku'",
        CodingAgentFactory.ofType(AgentType.CLAUDE).model("haiku").run("hi").script());
    assertEquals(
        "exec claude --model 'haiku'",
        CodingAgentFactory.ofType(AgentType.CLAUDE).model("haiku").start().script());
    assertTrue(
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .model("haiku")
            .chat()
            .script()
            .endsWith(" --model 'haiku'"));
  }

  @Test
  public void modelFlagIsAbsentWhenUnset() {
    assertFalse(CodingAgentFactory.ofType(AgentType.CLAUDE).run("hi").script().contains("--model"));
  }

  @Test
  public void chatRendersTheStreamJsonProtocol() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).chat();

    assertEquals(
        "exec claude --print --input-format stream-json --output-format stream-json"
            + " --include-hook-events --verbose",
        spec.script());
    assertFalse(spec.interactive());
  }

  @Test
  public void skipPermissionsAddsTheAutonomousFlag() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).skipPermissions().run("go");

    assertTrue(spec.script().endsWith(" --dangerously-skip-permissions"), spec.script());
  }

  private static final String SESSION_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SESSION_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REPORT_URL =
      "http://qits:8080/api/commands/cccccccc-cccc-cccc-cccc-cccccccccccc/agent-session";

  @Test
  public void sessionIdPinsAFreshSessionOnEveryLaunchVariant() {
    assertEquals(
        "exec claude --session-id " + SESSION_A,
        CodingAgentFactory.ofType(AgentType.CLAUDE).sessionId(SESSION_A).start().script());
    assertEquals(
        "claude -p 'hi' --session-id " + SESSION_A,
        CodingAgentFactory.ofType(AgentType.CLAUDE).sessionId(SESSION_A).run("hi").script());
    assertTrue(
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .sessionId(SESSION_A)
            .chat()
            .script()
            .endsWith(" --session-id " + SESSION_A));
  }

  @Test
  public void resumeContinuesTheSessionInPlace() {
    assertEquals(
        "exec claude --resume " + SESSION_A,
        CodingAgentFactory.ofType(AgentType.CLAUDE).resume(SESSION_A).start().script());
  }

  @Test
  public void resumeWithForkBranchesIntoTheNewPinnedId() {
    assertEquals(
        "exec claude --resume " + SESSION_A + " --fork-session --session-id " + SESSION_B,
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .resume(SESSION_A)
            .fork(SESSION_B)
            .start()
            .script());
  }

  @Test
  public void forkWithoutResumeThrows() {
    CodingAgent agent = CodingAgentFactory.ofType(AgentType.CLAUDE).fork(SESSION_B);

    assertThrows(IllegalStateException.class, agent::start);
  }

  @Test
  public void pinAndResumeWithoutForkThrows() {
    // A pinned id is create-only; combining it with resume is contradictory unless forking.
    CodingAgent agent =
        CodingAgentFactory.ofType(AgentType.CLAUDE).sessionId(SESSION_B).resume(SESSION_A);

    assertThrows(IllegalStateException.class, agent::start);
  }

  @Test
  public void nonUuidSessionIdsAreRejected() {
    // The ids are interpolated into an argv, so only canonical UUIDs may pass.
    assertThrows(
        IllegalArgumentException.class,
        () -> CodingAgentFactory.ofType(AgentType.CLAUDE).sessionId("$(reboot)").start());
    assertThrows(
        IllegalArgumentException.class,
        () -> CodingAgentFactory.ofType(AgentType.CLAUDE).resume("1-2-3-4-5").start());
  }

  @Test
  public void sessionReportingRendersASessionStartHookSettingsLayer() {
    String cmd =
        CodingAgentFactory.ofType(AgentType.CLAUDE).sessionReporting(REPORT_URL).chat().script();

    // One --settings layer whose SessionStart hook POSTs the hook's stdin JSON to the endpoint.
    assertTrue(cmd.contains(" --settings '"), cmd);
    assertTrue(cmd.contains("\"SessionStart\""), cmd);
    assertTrue(
        cmd.contains(
            "curl -fsS -m 5 -X POST -H \\\"Content-Type: application/json\\\" --data-binary @- "
                + REPORT_URL),
        cmd);
    // Without activity tracking, only SessionStart is wired (lineage) — no turn-boundary events.
    assertFalse(cmd.contains("\"UserPromptSubmit\""), cmd);
    assertFalse(cmd.contains("\"Stop\""), cmd);
    assertFalse(cmd.contains("\"Notification\""), cmd);
    assertFalse(cmd.contains("\"SessionEnd\""), cmd);
  }

  @Test
  public void activityTrackingAddsTheTurnBoundaryHooks() {
    String cmd =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .sessionReporting(REPORT_URL)
            .activityTracking(true)
            .chat()
            .script();

    // SessionStart stays (lineage) and the four activity events are added.
    assertTrue(cmd.contains("\"SessionStart\""), cmd);
    assertTrue(cmd.contains("\"UserPromptSubmit\""), cmd);
    assertTrue(cmd.contains("\"Stop\""), cmd);
    assertTrue(cmd.contains("\"Notification\""), cmd);
    assertTrue(cmd.contains("\"SessionEnd\""), cmd);
  }

  @Test
  public void sessionReportingUrlWithAQuoteIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CodingAgentFactory.ofType(AgentType.CLAUDE)
                .sessionReporting("http://qits:8080/a'b")
                .chat());
  }

  @Test
  public void transcriptLocatorsFollowTheProjectsConvention() {
    CodingAgent agent = CodingAgentFactory.ofType(AgentType.CLAUDE);

    // cwd /workspace escapes to -workspace (every non-alphanumeric character becomes a dash).
    assertEquals(
        Path.of("projects", "-workspace", SESSION_A + ".jsonl"),
        agent.transcriptPath("/workspace", SESSION_A));
    assertEquals(
        Path.of("projects", "-workspace", SESSION_A, "subagents"),
        agent.subagentsDir("/workspace", SESSION_A));
  }

  @Test
  public void multipleMcpServersMergeIntoOneConfigAndOneAllowlist() {
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .mcpServer("actions", httpMcp(ACTIONS_URL))
            .allowedTools(List.of("mcp__actions__listGlobalActions"))
            .mcpServer("repository", httpMcp("http://localhost:8080/projects/mcp?projectId=p"))
            .allowedTools(List.of("mcp__repository__listBranches"))
            .start();

    String cmd = spec.script();
    // Both servers appear in one mcp-config object (insertion order)...
    assertTrue(cmd.contains("\"actions\":{"), cmd);
    assertTrue(cmd.contains("\"repository\":{"), cmd);
    // ...and every allowlist entry is joined into a single --allowedTools argument.
    assertTrue(
        cmd.contains(
            "--allowedTools 'mcp__actions__listGlobalActions,mcp__repository__listBranches'"),
        cmd);
  }
}
