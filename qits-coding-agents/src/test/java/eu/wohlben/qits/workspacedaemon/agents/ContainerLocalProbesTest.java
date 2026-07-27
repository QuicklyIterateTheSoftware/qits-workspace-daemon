package eu.wohlben.qits.workspacedaemon.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.commands.InvalidCommandRequestException;
import eu.wohlben.qits.workspacedaemon.commands.WorkspaceContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three surfaces that used to reach into a container over {@code docker exec}:
 * {@link AgentAuthStatus}, {@link AgentPluginService} and {@link PromptRefinementService}.
 *
 * <p>Beyond each one's own behaviour, every case asserts what the relocation was <em>for</em> — that
 * nothing forks a container runtime any more. {@link Runner} fails the test outright if an argv ever
 * starts with {@code docker} or {@code podman}, so a reintroduced exec cannot pass silently.
 */
class ContainerLocalProbesTest {

  @TempDir Path claudeMount;
  @TempDir Path workspaceRoot;

  /** A scripted {@link ProcessRunner} that records what it was asked to run. */
  private static final class Runner implements ProcessRunner {
    private final List<List<String>> argvs = new ArrayList<>();
    private final List<Map<String, String>> envs = new ArrayList<>();
    private final List<Path> cwds = new ArrayList<>();
    private Result next = new Result(0, "", "", false);

    Runner returning(Result result) {
      this.next = result;
      return this;
    }

    @Override
    public Result exec(List<String> command, Path cwd, Map<String, String> env, Duration timeout) {
      String executable = command.isEmpty() ? "" : command.get(0);
      if ("docker".equals(executable) || "podman".equals(executable)) {
        throw new AssertionError(
            "the daemon IS the container — nothing here may shell a container runtime: " + command);
      }
      argvs.add(List.copyOf(command));
      envs.add(Map.copyOf(env));
      cwds.add(cwd);
      return next;
    }

    List<String> lastArgv() {
      return argvs.get(argvs.size() - 1);
    }

    Map<String, String> lastEnv() {
      return envs.get(envs.size() - 1);
    }
  }

  private static AgentDefaults defaults(AgentType type, String model) {
    return new AgentDefaults() {
      @Override
      public AgentType defaultAgentType() {
        return type;
      }

      @Override
      public boolean activityTrackingEnabled() {
        return true;
      }

      @Override
      public Optional<String> refinementModel() {
        return Optional.ofNullable(model);
      }
    };
  }

  @Nested
  class AuthStatus {

    @Test
    void claudeProbesAuthStatusLocallyWithHomeOnTheVolume() {
      Runner runner = new Runner().returning(new ProcessRunner.Result(0, "{\"loggedIn\": true}", "", false));
      AgentAuthStatus auth = new AgentAuthStatus(runner, claudeMount.toString(), workspaceRoot);

      assertTrue(auth.isLoggedIn(AgentType.CLAUDE));
      assertEquals(List.of("claude", "auth", "status"), runner.lastArgv(), "no docker prefix");
      assertEquals(Map.of("HOME", claudeMount.toString()), runner.lastEnv());
    }

    @Test
    void kimiProbesEitherCredentialLayout() {
      Runner runner = new Runner();
      AgentAuthStatus auth = new AgentAuthStatus(runner, claudeMount.toString(), workspaceRoot);

      assertTrue(auth.isLoggedIn(AgentType.KIMI));
      assertEquals("bash", runner.lastArgv().get(0));
      assertTrue(
          runner.lastArgv().get(2).contains("credentials/kimi-code.json"),
          "the 0.28+ directory layout must be accepted, or every launch flickers through login");
      assertEquals(
          claudeMount + "/.kimi-code", runner.lastEnv().get("KIMI_CODE_HOME"));
    }

    @Test
    void theLoggedInFieldWinsOverTheExitCode() {
      // Ported verbatim from the host's AgentAuthStatusTest -- the parser is unchanged.
      assertTrue(AgentAuthStatus.parseClaudeLoggedIn(1, "{\"loggedIn\": true}"));
      assertFalse(AgentAuthStatus.parseClaudeLoggedIn(0, "{\"loggedIn\": false}"));
      assertTrue(AgentAuthStatus.parseClaudeLoggedIn(0, "no json here"));
      assertFalse(AgentAuthStatus.parseClaudeLoggedIn(1, "no json here"));
    }

    @Test
    void aFailedProbeReadsAsSignedOut() {
      Runner runner = new Runner().returning(new ProcessRunner.Result(1, "", "not logged in", false));
      AgentAuthStatus auth = new AgentAuthStatus(runner, claudeMount.toString(), workspaceRoot);

      assertFalse(auth.isLoggedIn(AgentType.CLAUDE));
    }
  }

  @Nested
  class Plugins {

    @Test
    void listingReadsTheVolumeDirectlyAndForksNothing() throws IOException {
      Runner runner = new Runner();
      AgentPluginService plugins =
          new AgentPluginService(
              runner, claudeMount.toString(), workspaceRoot, defaults(AgentType.CLAUDE, null));
      Files.createDirectories(plugins.settingsPath().getParent());
      Files.writeString(
          plugins.settingsPath(),
          "{\"enabledPlugins\":{\"jdtls-lsp@claude-plugins-official\":true,"
              + "\"off@claude-plugins-official\":false}}");

      List<InstalledPluginDto> installed = plugins.listInstalled();

      assertEquals(2, installed.size());
      assertEquals("jdtls-lsp@claude-plugins-official", installed.get(0).pluginId());
      assertTrue(installed.get(0).enabled());
      assertFalse(installed.get(1).enabled());
      assertTrue(runner.argvs.isEmpty(), "a file on a mounted volume is a file, not a docker exec");
    }

    @Test
    void anAbsentSettingsFileIsAnEmptyListNotAnError() {
      AgentPluginService plugins =
          new AgentPluginService(
              new Runner(), claudeMount.toString(), workspaceRoot, defaults(AgentType.CLAUDE, null));

      assertTrue(plugins.listInstalled().isEmpty());
    }

    @Test
    void malformedSettingsToleratesShapeDrift() {
      // Ported verbatim from the host's AgentPluginServiceTest.
      assertTrue(AgentPluginService.parseEnabledPlugins(null).isEmpty());
      assertTrue(AgentPluginService.parseEnabledPlugins("").isEmpty());
      assertTrue(AgentPluginService.parseEnabledPlugins("{not json").isEmpty());
      assertTrue(AgentPluginService.parseEnabledPlugins("{\"enabledPlugins\":[]}").isEmpty());
      assertTrue(AgentPluginService.parseEnabledPlugins("{\"other\":{}}").isEmpty());
    }

    @Test
    void installShellsTheCliAndRefreshesTheList() {
      Runner runner = new Runner();
      AgentPluginService plugins =
          new AgentPluginService(
              runner, claudeMount.toString(), workspaceRoot, defaults(AgentType.CLAUDE, null));

      plugins.install("jdtls-lsp");

      assertEquals(
          List.of("claude", "plugin", "install", "jdtls-lsp@claude-plugins-official"),
          runner.lastArgv());
      assertEquals(Map.of("HOME", claudeMount.toString()), runner.lastEnv());
    }

    @Test
    void aBogusPluginIdIsARequestError() {
      AgentPluginService plugins =
          new AgentPluginService(
              new Runner(), claudeMount.toString(), workspaceRoot, defaults(AgentType.CLAUDE, null));

      assertThrows(InvalidCommandRequestException.class, () -> plugins.install("../../etc/passwd"));
      assertThrows(InvalidCommandRequestException.class, () -> plugins.install(null));
    }

    @Test
    void pluginsAreRefusedWhenTheDefaultHarnessIsNotClaude() {
      AgentPluginService plugins =
          new AgentPluginService(
              new Runner(), claudeMount.toString(), workspaceRoot, defaults(AgentType.KIMI, null));

      assertThrows(InvalidCommandRequestException.class, plugins::listInstalled);
    }
  }

  @Nested
  class Refinement {

    private final WorkspaceContext workspace =
        new WorkspaceContext() {
          @Override
          public String repoId() {
            return "repo-1";
          }

          @Override
          public String workspaceId() {
            return "ws-1";
          }

          @Override
          public String branch() {
            return "feature/x";
          }

          @Override
          public String commitHash() {
            return "abc1234";
          }
        };

    private PromptRefinementService service(Runner runner, AgentType type, String model) {
      return new PromptRefinementService(
          runner, workspace, defaults(type, model), claudeMount.toString(), workspaceRoot);
    }

    @Test
    void theArgvIsAPlainLocalShellWithNoContainerPrefix() {
      Runner runner = new Runner().returning(new ProcessRunner.Result(0, " refined \n", "", false));

      String prompt = service(runner, AgentType.CLAUDE, null).refine("uh do the thing", "ship it");

      assertEquals("refined", prompt, "stdout is the payload, stripped");
      assertEquals(3, runner.lastArgv().size());
      assertEquals("bash", runner.lastArgv().get(0));
      assertEquals("-lc", runner.lastArgv().get(1));
      assertTrue(runner.lastArgv().get(2).startsWith("claude -p "), runner.lastArgv().get(2));
      assertTrue(runner.lastArgv().get(2).contains("--model 'haiku'"), "the cheap default model");
      assertEquals(workspaceRoot, runner.cwds.get(0), "it runs in the checkout, not a home dir");
      assertEquals(claudeMount.toString(), runner.lastEnv().get("HOME"));
    }

    @Test
    void theBranchComesFromTheWorkspaceContextAndThePreambleFromTheRequest() {
      Runner runner = new Runner().returning(new ProcessRunner.Result(0, "ok", "", false));

      service(runner, AgentType.CLAUDE, null).refine("transcript", "make it fast");

      String script = runner.lastArgv().get(2);
      assertTrue(script.contains("feature/x"), "the branch is ambient, off the workspace context");
      assertTrue(script.contains("make it fast"), "the preamble has no container source, so it rides the request");
      assertTrue(script.contains("transcript"));
    }

    @Test
    void anAbsentPreambleRendersAsNone() {
      Runner runner = new Runner().returning(new ProcessRunner.Result(0, "ok", "", false));

      service(runner, AgentType.CLAUDE, null).refine("transcript", null);

      assertTrue(runner.lastArgv().get(2).contains("(none)"));
    }

    @Test
    void kimiTakesNoModelOverlayAndNoHomeOverlay() {
      Runner runner = new Runner().returning(new ProcessRunner.Result(0, "ok", "", false));

      service(runner, AgentType.KIMI, null).refine("transcript", null);

      assertFalse(runner.lastEnv().containsKey("HOME"), "Kimi's data root is set container-wide");
      assertFalse(runner.lastArgv().get(2).contains(" -m "), "no model override without one configured");
    }

    @Test
    void aConfiguredModelOverridesTheDefault() {
      Runner runner = new Runner().returning(new ProcessRunner.Result(0, "ok", "", false));

      service(runner, AgentType.CLAUDE, "sonnet").refine("transcript", null);

      assertTrue(runner.lastArgv().get(2).contains("--model 'sonnet'"));
    }

    @Test
    void aBlankTranscriptIsARequestError() {
      assertThrows(
          InvalidCommandRequestException.class,
          () -> service(new Runner(), AgentType.CLAUDE, null).refine("  ", null));
      assertThrows(
          InvalidCommandRequestException.class,
          () -> service(new Runner(), AgentType.CLAUDE, null).refine(null, null));
    }

    @Test
    void aTimeoutAFailureAndEmptyOutputAllFailLoudly() {
      assertThrows(
          IllegalStateException.class,
          () ->
              service(new Runner().returning(new ProcessRunner.Result(0, "", "", true)), AgentType.CLAUDE, null)
                  .refine("t", null));
      assertThrows(
          IllegalStateException.class,
          () ->
              service(
                      new Runner().returning(new ProcessRunner.Result(2, "", "boom", false)),
                      AgentType.CLAUDE,
                      null)
                  .refine("t", null));
      assertThrows(
          IllegalStateException.class,
          () ->
              service(new Runner().returning(new ProcessRunner.Result(0, "   ", "", false)), AgentType.CLAUDE, null)
                  .refine("t", null));
    }
  }
}
