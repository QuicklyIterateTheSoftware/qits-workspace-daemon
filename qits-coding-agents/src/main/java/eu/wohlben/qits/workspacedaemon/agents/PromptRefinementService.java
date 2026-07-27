package eu.wohlben.qits.workspacedaemon.agents;

import eu.wohlben.qits.workspacedaemon.commands.InvalidCommandRequestException;
import eu.wohlben.qits.workspacedaemon.commands.WorkspaceContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites a raw speech-to-text transcript into a task prompt, by running the harness once
 * non-interactively and taking its stdout.
 *
 * <p>The host built a {@code docker exec -w /workspace -e HOME=… <container> bash -lc <agent>} argv
 * for this. Inside the container the docker prefix is gone and the argv is just {@code bash -lc
 * <agent>}; it still goes through {@link ProcessRunner} rather than the command registry because the
 * refined prompt is stdout <em>only</em> and must not be polluted by stderr.
 *
 * <p>The workspace's branch comes from {@link WorkspaceContext}. Its <em>preamble</em> has no source
 * in the container — it is host-side workspace metadata — so the caller passes it on the request
 * instead. That is the whole of what {@code WorkspaceRepository} was doing here.
 */
public final class PromptRefinementService {

  private static final Duration TIMEOUT = Duration.ofSeconds(120);

  private static final String META_PROMPT =
      """
      You rewrite raw speech-to-text transcripts into prompts for an autonomous coding agent.

      The transcript below was dictated by a developer describing work to do in a workspace.
      Workspace branch: %s
      Workspace goal (preamble):
      %s

      Rewrite the transcript into a clear, imperative task prompt for the coding agent:
      - Fix speech-recognition artifacts: misheard words, missing punctuation, filler words,
        false starts and self-corrections (keep only the corrected intent).
      - Preserve every technical detail (names, paths, constraints). Do not invent requirements
        or add steps the speaker did not ask for.
      - Structure: one summary sentence of the goal, then bullet points for specifics if any.
      - Output ONLY the rewritten prompt text - no commentary, no headings, no code fences.

      Transcript:
      %s
      """;

  private final ProcessRunner processes;
  private final WorkspaceContext workspace;
  private final AgentDefaults defaults;
  private final String claudeMount;
  private final Path workspaceRoot;

  public PromptRefinementService(
      ProcessRunner processes,
      WorkspaceContext workspace,
      AgentDefaults defaults,
      String claudeMount,
      Path workspaceRoot) {
    this.processes = processes;
    this.workspace = workspace;
    this.defaults = defaults;
    this.claudeMount = claudeMount;
    this.workspaceRoot = workspaceRoot;
  }

  /**
   * @param preamble the workspace's stated goal, or null — host-side metadata the request carries
   */
  public String refine(String transcript, String preamble) {
    if (transcript == null || transcript.isBlank()) {
      throw new InvalidCommandRequestException("transcript is required");
    }

    // Refinement is a default-scoped surface (no per-launch tab choice): resolve the default
    // harness once.
    AgentType agentType = defaults.defaultAgentType();

    String metaPrompt =
        META_PROMPT.formatted(
            workspace.branch(),
            preamble == null || preamble.isBlank() ? "(none)" : preamble,
            transcript);
    // Plain-text output: refinement consumes stdout verbatim, so Kimi's stream-json default must
    // not apply. The model id is harness-specific; unset means Claude's haiku, Kimi's default.
    CodingAgent refinementAgent = CodingAgentFactory.ofType(agentType).plainTextOutput();
    String model =
        defaults
            .refinementModel()
            .filter(m -> !m.isBlank())
            .orElse(agentType == AgentType.CLAUDE ? "haiku" : null);
    if (model != null) {
      refinementAgent.model(model);
    }
    LaunchSpec spec = refinementAgent.run(metaPrompt);

    Map<String, String> env = new HashMap<>(spec.environment());
    if (claudeMount != null && !claudeMount.isBlank() && agentType == AgentType.CLAUDE) {
      env.put("HOME", claudeMount);
      // Kimi Code's data root is already set at the container level via KIMI_CODE_HOME; no overlay
      // needed for refinement.
    }

    ProcessRunner.Result result =
        processes.exec(
            List.of("bash", "-lc", spec.script()), workspaceRoot, env, TIMEOUT);

    if (result.timedOut()) {
      throw new IllegalStateException(
          "Prompt refinement timed out after " + TIMEOUT.toSeconds() + "s");
    }
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "Prompt refinement failed (exit " + result.exitCode() + "): " + tail(result.stderr()));
    }
    String prompt = result.stdout().strip();
    if (prompt.isEmpty()) {
      throw new IllegalStateException("Prompt refinement produced no output");
    }
    return prompt;
  }

  private static String tail(String text) {
    String stripped = text == null ? "" : text.strip();
    int max = 500;
    return stripped.length() <= max ? stripped : stripped.substring(stripped.length() - max);
  }
}
