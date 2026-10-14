package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentPromptTemplate;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.WorkLinkProjects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What this container knows about itself, for a configured initial prompt's placeholders.
 *
 * <p>An unresolvable name is left <b>literal</b> by the library, which is what makes deriving the
 * epic and ticket from the branch acceptable: an ad-hoc workspace on {@code main} renders a prompt
 * an agent can see has a hole in it, rather than one that reads as if a fact were known. A wrong
 * guess would be worse than an absence.
 */
class DaemonAgentDefaultsTest {

  @Test
  void everyFactTheHostInjectedIsAnswered() {
    Map<String, String> facts =
        DaemonAgentDefaults.ambientFactsOf("p-1", "qits-stt", "repo-id", "ws-1", "main");

    assertEquals("p-1", facts.get("project"));
    assertEquals("qits-stt", facts.get("repository"));
    assertEquals("ws-1", facts.get("workspace"));
  }

  @Test
  void aRepositoryWithNoProjectScopedNameFallsBackToItsId() {
    Map<String, String> facts =
        DaemonAgentDefaults.ambientFactsOf("p-1", "", "repo-id", "ws-1", "main");

    assertEquals("repo-id", facts.get("repository"));
  }

  @Test
  void theEpicAndTicketAreReadOffTheBranchBecauseNothingElseCarriesThem() {
    // qits-projects' "Start implementation" stands a workspace on epic/<slug>, and its ticket
    // dispatch cuts ticket/<slug>. The container's environment names a workspace, a repository and
    // a project and stops there, so the branch is the only place this is said.
    assertEquals(
        "agent-configuration-system",
        DaemonAgentDefaults.ambientFactsOf(
                "p-1", "qits-stt", "repo-id", "ws-1", "epic/agent-configuration-system")
            .get("epic"));
    assertEquals(
        "the-bolt-should-go-yellow",
        DaemonAgentDefaults.ambientFactsOf(
                "p-1", "qits-stt", "repo-id", "ws-1", "ticket/the-bolt-should-go-yellow")
            .get("ticket"));
  }

  @Test
  void aBranchThatIsNeitherLeavesBothUnanswered() {
    Map<String, String> facts =
        DaemonAgentDefaults.ambientFactsOf("p-1", "qits-stt", "repo-id", "ws-1", "main");

    assertFalse(facts.containsKey("epic"));
    assertFalse(facts.containsKey("ticket"));
    // Which is what the placeholder then renders as: literal, so the hole is visible.
    assertEquals(
        "Continue {{ticket}} in qits-stt",
        AgentPromptTemplate.render("Continue {{ticket}} in {{repository}}", facts));
  }

  @Test
  void aBlankFactIsAbsentRatherThanAnEmptyValue() {
    // The library treats a blank fact as unresolvable anyway; never recording one keeps the two
    // sides from disagreeing about what "known" means.
    Map<String, String> facts = DaemonAgentDefaults.ambientFactsOf("", " ", "", "", "epic/");

    assertEquals(Map.of(), facts);
  }

  @Test
  void theFactNamesAreTheOnesTheEditorShowsBesideTheField() {
    Map<String, String> facts =
        DaemonAgentDefaults.ambientFactsOf(
            "p-1", "qits-stt", "repo-id", "ws-1", "epic/agent-configuration-system");

    // branch and commit are the library's own, off CheckoutContext; everything else here is this
    // daemon's. A template written against a name nobody answers renders as a placeholder, which is
    // why the two lists have to agree.
    for (String name : facts.keySet()) {
      assertEquals(
          true, AgentPromptTemplate.NAMES.contains(name), name + " is not a documented fact");
    }
  }

  private static DaemonAgentDefaults defaultsWithEntityId(String entityId) {
    return new DaemonAgentDefaults(
        () -> null,
        Optional.empty(),
        true,
        Optional.empty(),
        AgentSurfaceConfigurations.shipped(),
        Map.of(),
        entityId);
  }

  @Test
  void theInjectedEntityIdIsAnsweredTrimmed() {
    assertEquals(Optional.of("qits-614"), defaultsWithEntityId("  qits-614  ").entityId());
  }

  @Test
  void aBlankEntityIdIsAbsentRatherThanAnEmptyValue() {
    assertTrue(defaultsWithEntityId("   ").entityId().isEmpty());
  }

  @Test
  void aNullEntityIdIsAbsent() {
    assertTrue(defaultsWithEntityId(null).entityId().isEmpty());
  }

  private static DaemonAgentDefaults defaultsWithEntityBlocked(boolean entityBlocked) {
    return new DaemonAgentDefaults(
        () -> null,
        Optional.empty(),
        true,
        Optional.empty(),
        AgentSurfaceConfigurations.shipped(),
        Map.of(),
        "qits-614",
        entityBlocked);
  }

  @Test
  void theInjectedBlockedSeedIsAnsweredAsIs() {
    assertTrue(defaultsWithEntityBlocked(true).entityBlocked());
    assertFalse(defaultsWithEntityBlocked(false).entityBlocked());
  }

  @Test
  void theDefaultSixAndSevenArgumentConstructorsLeaveBlockedFalse() {
    DaemonAgentDefaults sixArg =
        new DaemonAgentDefaults(
            () -> null,
            Optional.empty(),
            true,
            Optional.empty(),
            AgentSurfaceConfigurations.shipped(),
            Map.of());
    assertFalse(sixArg.entityBlocked());
    assertFalse(defaultsWithEntityId("qits-614").entityBlocked());
  }

  private static DaemonAgentDefaults defaultsWithTitleAndStatus(String title, String status) {
    return new DaemonAgentDefaults(
        () -> null,
        Optional.empty(),
        true,
        Optional.empty(),
        AgentSurfaceConfigurations.shipped(),
        Map.of(),
        "qits-617",
        false,
        title,
        status);
  }

  @Test
  void theInjectedTitleAndStatusAreAnsweredTrimmed() {
    DaemonAgentDefaults defaults =
        defaultsWithTitleAndStatus("  Session names carry status  ", " IMPLEMENTING ");
    assertEquals(Optional.of("Session names carry status"), defaults.entityTitle());
    assertEquals(Optional.of("IMPLEMENTING"), defaults.entityStatus());
  }

  @Test
  void aBlankOrNullTitleAndStatusAreAbsent() {
    assertTrue(defaultsWithTitleAndStatus("   ", "  ").entityTitle().isEmpty());
    assertTrue(defaultsWithTitleAndStatus("   ", "  ").entityStatus().isEmpty());
    assertTrue(defaultsWithTitleAndStatus(null, null).entityTitle().isEmpty());
    assertTrue(defaultsWithTitleAndStatus(null, null).entityStatus().isEmpty());
  }

  @Test
  void theShorterConstructorsLeaveTitleAndStatusAbsent() {
    assertTrue(defaultsWithEntityBlocked(true).entityTitle().isEmpty());
    assertTrue(defaultsWithEntityBlocked(true).entityStatus().isEmpty());
    assertTrue(defaultsWithEntityId("qits-614").entityTitle().isEmpty());
  }

  // --- platformDomain() / workLinkProjects() (qits-1204) ----------------------------------------

  private static DaemonAgentDefaults defaultsWithDomainAndWorkLinks(
      String entityId, String domain, WorkLinkProjects workLinkProjects) {
    return new DaemonAgentDefaults(
        () -> null,
        Optional.empty(),
        true,
        Optional.empty(),
        AgentSurfaceConfigurations.shipped(),
        Map.of(),
        entityId,
        false,
        null,
        null,
        domain,
        workLinkProjects);
  }

  @Test
  void theInjectedDomainIsAnsweredTrimmed() {
    assertEquals(
        Optional.of("qits.example.com"),
        defaultsWithDomainAndWorkLinks("qits-614", "  qits.example.com  ", null).platformDomain());
  }

  @Test
  void aBlankOrNullDomainIsAbsentRatherThanAnEmptyValue() {
    assertTrue(defaultsWithDomainAndWorkLinks("qits-614", "   ", null).platformDomain().isEmpty());
    assertTrue(defaultsWithDomainAndWorkLinks("qits-614", null, null).platformDomain().isEmpty());
  }

  @Test
  void theShorterConstructorsLeaveTheDomainAbsent() {
    assertTrue(defaultsWithEntityId("qits-614").platformDomain().isEmpty());
    assertTrue(defaultsWithEntityBlocked(true).platformDomain().isEmpty());
  }

  @Test
  void workLinkProjectsAnswersTheEntityDerivedSlugWithNoSharedFetcher() {
    // The pre-qits-1204 constructors pass no WorkLinkProjects at all, so the interface default —
    // the project of entityId() — is the whole answer.
    assertEquals(List.of("qits"), defaultsWithEntityId("qits-1204").workLinkProjects());
  }

  @Test
  void workLinkProjectsUnionsTheEntityDerivedSlugWithTheSharedFetchersCache() {
    // A blank domain keeps WorkLinkProjects from ever fetching (its own javadoc), so slugs() is
    // always empty — this exercises the union path (not just the null-fetcher path above) without
    // reaching for its test-only fetch/clock constructor, which is package-private to
    // eu.wohlben.qits.agents and not visible from here.
    WorkLinkProjects neverFetches = new WorkLinkProjects("", Optional::empty);
    assertEquals(
        List.of("qits"),
        defaultsWithDomainAndWorkLinks("qits-1204", null, neverFetches).workLinkProjects());
  }

  @Test
  void workLinkProjectsIsEmptyWithNeitherAnEntityNorAFetcher() {
    assertEquals(
        List.of(), defaultsWithDomainAndWorkLinks(null, null, null).workLinkProjects());
  }
}
