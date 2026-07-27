package eu.wohlben.qits.workspacedaemon.agents;

import static eu.wohlben.qits.workspacedaemon.agents.McpServers.httpMcp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-renderer tests for the Kimi Code harness — no CDI, just the shell it produces. */
public class KimiCodeAgentTest {

  private static final String ACTIONS_URL =
      "http://localhost:8080/actions/mcp?repositoryId=11111111-1111-1111-1111-111111111111";

  private static final String KIMI_SESSION = "session_aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @Test
  public void startRendersKimiTuiWithPrelude() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.KIMI).start();

    String script = spec.script();
    assertTrue(script.contains("QITS_KIMI_HOME=\"$KIMI_CODE_HOME\""), script);
    assertTrue(script.contains("mktemp -d /tmp/qits-kimi-XXXXXX"), script);
    assertTrue(script.contains("\nkimi"), script);
    // No exec: it would replace the shell, so the farm's EXIT trap would never fire.
    assertFalse(script.contains("exec kimi"), script);
    assertTrue(spec.interactive(), "interactive TUI");
  }

  @Test
  public void runRendersKimiAutonomousWithStreamJson() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.KIMI).run("resolve the conflict");

    String script = spec.script();
    assertTrue(script.contains("kimi -p 'resolve the conflict'"), script);
    assertTrue(script.contains("--output-format stream-json"), script);
    assertFalse(spec.interactive(), "autonomous is not interactive");
  }

  @Test
  public void skipPermissionsAddsYoloToInteractive() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.KIMI).skipPermissions().start();

    assertTrue(spec.script().contains("\nkimi --yolo"), spec.script());
  }

  @Test
  public void resumeAddsSessionFlag() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.KIMI).resume(KIMI_SESSION).start();

    assertTrue(spec.script().contains("\nkimi -S " + KIMI_SESSION), spec.script());
  }

  @Test
  public void forkIsRejected() {
    CodingAgent agent =
        CodingAgentFactory.ofType(AgentType.KIMI)
            .resume(KIMI_SESSION)
            .fork("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    assertThrows(IllegalStateException.class, agent::start);
  }

  @Test
  public void nonKimiSessionIdsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CodingAgentFactory.ofType(AgentType.KIMI).sessionId("not-a-session").start());
    assertThrows(
        IllegalArgumentException.class,
        () -> CodingAgentFactory.ofType(AgentType.KIMI).resume("1-2-3-4-5").start());
    // A canonical UUID is a Claude id — Kimi ids are session_<uuid>.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CodingAgentFactory.ofType(AgentType.KIMI)
                .resume("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                .start());
  }

  @Test
  public void anOpaqueKimiSessionIdIsAcceptedForResume() {
    // Kimi's ACP session id is opaque; a session_-prefixed path-safe slug (not a canonical uuid)
    // resumes fine — the transcript path stays safe and no session is dropped for a non-uuid shape.
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.KIMI).resume("session_abc123DEF").start();

    assertTrue(spec.script().contains("\nkimi -S session_abc123DEF"), spec.script());
  }

  @Test
  public void aPathUnsafeKimiSessionIdIsRejected() {
    // A '/' or '.' in the id would escape the transcript directory, so it must not validate.
    assertThrows(
        IllegalArgumentException.class,
        () -> CodingAgentFactory.ofType(AgentType.KIMI).resume("session_../evil").start());
  }

  @Test
  public void kimiSessionIdIsAccepted() {
    // A kimi-shaped id is valid; for a fresh run it is silently dropped (kimi cannot pin).
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.KIMI).sessionId(KIMI_SESSION).run("hi");

    assertTrue(spec.script().contains("kimi -p 'hi'"), spec.script());
    assertFalse(spec.script().contains(KIMI_SESSION), spec.script());
  }

  @Test
  public void mcpConfigRendersAsMcpJsonHeredocWithStrippedToolNames() {
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.KIMI)
            .mcpServer("actions", httpMcp(ACTIONS_URL))
            .allowedTools(
                List.of("mcp__actions__listGlobalActions", "mcp__actions__getGlobalAction"))
            .start();

    String script = spec.script();
    assertTrue(script.contains("cat > \"$KIMI_CODE_HOME/mcp.json\" <<'EOF'"), script);
    assertTrue(script.contains("\"mcpServers\""), script);
    assertTrue(script.contains("\"actions\""), script);
    assertTrue(
        script.contains("\"enabledTools\":[\"listGlobalActions\",\"getGlobalAction\"]"), script);
    // The qits-prefixed form must not leak into the heredoc.
    assertFalse(script.contains("mcp__actions__listGlobalActions"), script);
  }

  @Test
  public void mcpConfigOnlyIncludesToolsForTheMatchingServer() {
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.KIMI)
            .mcpServer("actions", httpMcp(ACTIONS_URL))
            .allowedTools(List.of("mcp__actions__listGlobalActions"))
            .mcpServer("repository", httpMcp("http://localhost:8080/projects/mcp?projectId=p"))
            .allowedTools(List.of("mcp__repository__listBranches"))
            .start();

    String script = spec.script();
    assertTrue(
        script.contains("\"actions\":{\"type\":\"http\",\"url\":\"" + ACTIONS_URL + "\","), script);
    assertTrue(script.contains("\"enabledTools\":[\"listGlobalActions\"]"), script);
    assertTrue(script.contains("\"enabledTools\":[\"listBranches\"]"), script);
  }

  @Test
  public void chatRendersKimiAcpWithoutFarmOrMcpJson() {
    // Chat rides the ACP transport: a plain `exec kimi acp`, no per-launch KIMI_CODE_HOME farm and
    // no mcp.json (the MCP scope rides the ACP session/new, not a file). Even with servers attached
    // — the renderer ignores them for chat — none leak into the script.
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.KIMI)
            .mcpServer("actions", httpMcp(ACTIONS_URL))
            .allowedTools(List.of("mcp__actions__listGlobalActions"))
            .skipPermissions()
            .chat();

    assertEquals("exec kimi acp", spec.script());
    assertFalse(spec.interactive(), "chat is driven over pipes, not a PTY");
  }

  @Test
  public void stripServerPrefixYieldsBareToolNamesForTheMatchingServer() {
    List<String> stripped =
        KimiCodeAgent.stripServerPrefix(
            "repository",
            List.of("mcp__repository__listBranches", "mcp__actions__listGlobalActions"));

    assertEquals(List.of("listBranches"), stripped);
  }

  @Test
  public void transcriptLocatorsFollowKimiSessionsConvention() {
    CodingAgent agent = CodingAgentFactory.ofType(AgentType.KIMI);

    // wd_<basename>_<sha256(cwd)[:12]>, verified against CLI 0.28.1.
    assertEquals(
        Path.of(
            "sessions", "wd_workspace_c52ddf65534b", KIMI_SESSION, "agents", "main", "wire.jsonl"),
        agent.transcriptPath("/workspace", KIMI_SESSION));
    assertEquals(
        Path.of("sessions", "wd_workspace_c52ddf65534b", KIMI_SESSION, "agents"),
        agent.subagentsDir("/workspace", KIMI_SESSION));
    // The hash disambiguates same-named dirs (verified vectors from real kimi runs).
    assertEquals(
        Path.of("sessions", "wd_tmp_e9671acd2448", KIMI_SESSION, "agents", "main", "wire.jsonl"),
        agent.transcriptPath("/tmp", KIMI_SESSION));
    assertEquals(
        Path.of("sessions", "wd_sub_7e6a66d1ac42", KIMI_SESSION, "agents", "main", "wire.jsonl"),
        agent.transcriptPath("/tmp/probe-nest/sub", KIMI_SESSION));
  }

  @Test
  public void shellMetacharactersInPromptStayQuoted() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.KIMI).run("a'b`c$(d);e");

    assertTrue(spec.script().contains("kimi -p 'a'\\''b`c$(d);e'"), spec.script());
  }

  @Test
  public void modelFlagRendersOnEveryLaunchVariant() {
    assertTrue(
        CodingAgentFactory.ofType(AgentType.KIMI)
            .model("moonshot")
            .run("hi")
            .script()
            .contains("kimi -p 'hi' --output-format stream-json -m 'moonshot'"));
    assertTrue(
        CodingAgentFactory.ofType(AgentType.KIMI)
            .model("moonshot")
            .start()
            .script()
            .contains("\nkimi -m 'moonshot'"));
  }

  @Test
  public void mcpConfigIsWrittenBeforeTheKimiInvocation() {
    // The heredoc must land before the launch: exec-less or not, kimi reads mcp.json at startup.
    CodingAgent configured =
        CodingAgentFactory.ofType(AgentType.KIMI).mcpServer("actions", httpMcp(ACTIONS_URL));

    String interactive = configured.start().script();
    assertTrue(
        interactive.indexOf("mcp.json") >= 0
            && interactive.indexOf("mcp.json") < interactive.indexOf("\nkimi"),
        interactive);

    String autonomous = configured.run("hi").script();
    assertTrue(
        autonomous.indexOf("mcp.json") >= 0
            && autonomous.indexOf("mcp.json") < autonomous.indexOf("\nkimi"),
        autonomous);
  }

  @Test
  public void sessionReportingRendersALaunchLocalConfigTomlHook() {
    String url = "http://qits:8080/api/commands/11111111-1111-1111-1111-111111111111/agent-session";
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.KIMI).sessionReporting(url).start();

    String script = spec.script();
    // config.toml is copied into the throwaway home (not symlinked) so the launch can append its
    // own hook — the report URL is per-command, so a volume-level hook can't carry it.
    assertTrue(script.contains("cat \"$QITS_KIMI_HOME/config.toml\""), script);
    assertFalse(script.contains("for e in config.toml"), script);
    assertTrue(script.contains("[[hooks]]"), script);
    assertTrue(script.contains("event = \"SessionStart\""), script);
    assertTrue(
        script.contains(
            "command = 'curl -fsS -m 5 -X POST -H \"Content-Type: application/json\""
                + " --data-binary @- "
                + url
                + "'"),
        script);
    // The hook must be in place before kimi starts.
    assertTrue(script.indexOf("[[hooks]]") < script.indexOf("\nkimi"), script);
    // Without activity tracking, only the SessionStart lineage hook is wired.
    assertFalse(script.contains("event = \"UserPromptSubmit\""), script);
    assertFalse(script.contains("event = \"Stop\""), script);
  }

  @Test
  public void activityTrackingRendersAllLifecycleHooks() {
    String url =
        "http://127.0.0.1:13337/hooks/claude-code?commandId=11111111-1111-1111-1111-111111111111";
    String script =
        CodingAgentFactory.ofType(AgentType.KIMI)
            .sessionReporting(url)
            .activityTracking(true)
            .start()
            .script();

    // SessionStart (lineage) plus the four turn-boundary events, each its own [[hooks]] block.
    assertTrue(script.contains("event = \"SessionStart\""), script);
    assertTrue(script.contains("event = \"UserPromptSubmit\""), script);
    assertTrue(script.contains("event = \"Stop\""), script);
    assertTrue(script.contains("event = \"Notification\""), script);
    assertTrue(script.contains("event = \"SessionEnd\""), script);
  }

  @Test
  public void withoutSessionReportingConfigTomlStaysSymlinked() {
    String script = CodingAgentFactory.ofType(AgentType.KIMI).start().script();

    assertTrue(script.contains("for e in config.toml"), script);
    assertFalse(script.contains("[[hooks]]"), script);
  }

  @Test
  public void plainTextRunOmitsStreamJson() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.KIMI).plainTextOutput().run("hi");

    assertTrue(spec.script().contains("kimi -p 'hi'"), spec.script());
    assertFalse(spec.script().contains("--output-format stream-json"), spec.script());
  }
}
