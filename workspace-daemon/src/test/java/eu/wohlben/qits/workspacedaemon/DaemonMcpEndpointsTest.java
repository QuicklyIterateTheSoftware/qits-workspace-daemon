package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.commands.InvalidCommandRequestException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The MCP address derivation, which had no test until it started producing wrong paths. A string
 * built from another string is exactly the kind of thing that rots invisibly: every one of these
 * urls fails as a 404 an agent reports as a missing tool, not as an error anybody sees.
 */
class DaemonMcpEndpointsTest {

  private static final String PROJECT = "11111111-1111-1111-1111-111111111111";
  private static final String DIAL_HOME = "ws://qits:8080/workspaces/daemon/ws-1";

  private static DaemonMcpEndpoints endpoints(
      Optional<String> actions, Optional<String> repository, Optional<String> observability) {
    return new DaemonMcpEndpoints(DIAL_HOME, PROJECT, actions, repository, observability);
  }

  private static DaemonMcpEndpoints derived() {
    return endpoints(Optional.empty(), Optional.empty(), Optional.empty());
  }

  @Test
  void theRepositoryServerIsAddressedUnderTheProjectsSegment() {
    assertEquals(
        "http://qits:8080/projects/mcp",
        derived().mcpUrl("repository"),
        "qits-projects keeps the server name and serves it under its own segment");
  }

  @Test
  void theObservabilityServerIsAddressedUnderItsOwnSegmentAndItsOwnName() {
    assertEquals(
        "http://qits:8080/observability/mcp",
        derived().mcpUrl("observability"),
        "renamed from `repository`, because two servers by that name meant only one was"
            + " addressable");
  }

  @Test
  void theActionsServerHasNoDerivableAddressAndSaysSo() {
    InvalidCommandRequestException e =
        assertThrows(InvalidCommandRequestException.class, () -> derived().mcpUrl("actions"));

    assertTrue(e.getMessage().contains("qits.actions-mcp.url"), e.getMessage());
    assertTrue(
        e.getMessage().contains("monolith-only"),
        "the reason there is no segment belongs in the message");
  }

  @Test
  void anUnknownServerNameIsRefusedRatherThanTurnedIntoAPath() {
    assertThrows(InvalidCommandRequestException.class, () -> derived().mcpUrl("repositories"));
  }

  @Test
  void anExplicitUrlWinsForEveryServerIncludingActions() {
    DaemonMcpEndpoints configured =
        endpoints(
            Optional.of("http://elsewhere/actions/mcp"),
            Optional.of("http://qits-projects:8080/projects/mcp"),
            Optional.of("http://qits-observability:8080/observability/mcp"));

    assertEquals("http://elsewhere/actions/mcp", configured.mcpUrl("actions"));
    assertEquals("http://qits-projects:8080/projects/mcp", configured.mcpUrl("repository"));
    assertEquals(
        "http://qits-observability:8080/observability/mcp", configured.mcpUrl("observability"));
  }

  @Test
  void aBlankOverrideIsNoOverride() {
    assertEquals(
        "http://qits:8080/projects/mcp",
        endpoints(Optional.empty(), Optional.of("   "), Optional.empty()).mcpUrl("repository"));
  }

  @Test
  void httpBaseKeepsOnlyTheAuthority() {
    assertEquals(
        "http://qits:8080",
        DaemonMcpEndpoints.httpBaseOf("ws://qits:8080/workspaces/daemon/ws-1"),
        "the dial-home path addresses qits-workspaces' socket and says nothing about MCP");
    assertEquals("https://host", DaemonMcpEndpoints.httpBaseOf("wss://host/workspaces/daemon/x"));
    assertEquals(
        "https://host:443", DaemonMcpEndpoints.httpBaseOf("wss://host:443/workspaces/daemon/x"));
  }

  @Test
  void aMissingOrMalformedDialHomeUrlIsRefusedAtConstruction() {
    assertThrows(IllegalStateException.class, () -> DaemonMcpEndpoints.httpBaseOf(null));
    assertThrows(IllegalStateException.class, () -> DaemonMcpEndpoints.httpBaseOf(""));
    assertThrows(IllegalStateException.class, () -> DaemonMcpEndpoints.httpBaseOf("/no-authority"));
  }

  @Test
  void theProjectIdIsPassedThroughUntouched() {
    assertEquals(PROJECT, derived().projectId());
  }
}
