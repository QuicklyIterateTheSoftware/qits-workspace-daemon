package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.ScopedMcp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** This daemon's half of the {@link eu.wohlben.qits.agents.AgentMcpServers} seam (qits-1174). */
class WorkspaceMcpServersTest {

  private static final String PLATFORM_URL = "http://qits-platform-access-mcp-service:8080/mcp";

  private static final String TOKEN = "tok-abc123";

  private final WorkspaceMcpServers servers =
      new WorkspaceMcpServers(Optional.of(PLATFORM_URL), Optional.empty());

  @Test
  void theOnlyPlatformServerIsQitsAtEveryScope() {
    for (AgentMcpScope scope : AgentMcpScope.values()) {
      List<ScopedMcp> attached = servers.serversFor(scope);
      assertEquals(1, attached.size(), scope.toString());
      assertEquals("qits", attached.get(0).key());
      assertEquals(PLATFORM_URL, attached.get(0).url(), "scoped by the bearer, not by the url");
    }
  }

  @Test
  void theRetiredServersAreNotServed() {
    for (String retired : List.of("repository", "observability", "actions")) {
      assertTrue(servers.serverFor(retired, AgentMcpScope.REPOSITORY, null).isEmpty(), retired);
    }
    assertTrue(servers.serverFor("qits", AgentMcpScope.REPOSITORY, null).isPresent());
  }

  @Test
  void withoutAnAddressNothingIsAttached() {
    for (Optional<String> url : List.of(Optional.<String>empty(), Optional.of(" "))) {
      assertEquals(
          List.of(),
          new WorkspaceMcpServers(url, Optional.empty()).serversFor(AgentMcpScope.REPOSITORY));
    }
  }

  @Test
  void everyLaunchGetsTheImagesBrowserWithEveryToolPreApproved() {
    assertEquals(List.of(WorkspaceMcpServers.BROWSER), servers.localServers());
    assertEquals("qits-browser-mcp", WorkspaceMcpServers.BROWSER.command());
    assertEquals(List.of("mcp__browser__*"), WorkspaceMcpServers.BROWSER.allowedTools());
  }

  @Test
  void withATokenTheQitsServerCarriesTheBearer() {
    WorkspaceMcpServers withToken =
        new WorkspaceMcpServers(Optional.of(PLATFORM_URL), Optional.of(TOKEN));
    Map<String, String> bearer = Map.of("Authorization", "Bearer " + TOKEN);

    assertEquals(bearer, withToken.platformHeaders());
    assertEquals(bearer, withToken.serversFor(AgentMcpScope.REPOSITORY).getFirst().headers());
  }

  @Test
  void withoutATokenNoServerCarriesHeaders() {
    for (Optional<String> token : List.of(Optional.<String>empty(), Optional.of(" "))) {
      WorkspaceMcpServers without = new WorkspaceMcpServers(Optional.of(PLATFORM_URL), token);
      assertEquals(Map.of(), without.platformHeaders());
      assertEquals(Map.of(), without.serversFor(AgentMcpScope.REPOSITORY).getFirst().headers());
    }
  }
}
