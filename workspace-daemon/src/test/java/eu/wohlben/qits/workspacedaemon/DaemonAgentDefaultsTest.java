package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.agents.AgentPromptTemplate;
import java.util.Map;
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
}
