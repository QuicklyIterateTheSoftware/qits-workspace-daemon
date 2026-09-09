package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.agents.InvalidAgentConfigurationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Materializing the agent-configuration document at boot.
 *
 * <p>The three cases are the estate's absent-versus-broken rule, and each one is a decision rather
 * than a consequence: <b>neither</b> variable is a container created before this shipped and runs on
 * the harness library's shipped constants; <b>one</b> of the two is loud, because a path naming a
 * file nothing wrote reads as the supported absent case over a container that was meant to have a
 * configuration; a <b>malformed</b> document is loud too, and the library's own exception is let
 * through so the message names the offending key.
 */
class AgentConfigurationFileTest {

  @TempDir Path dir;

  @Test
  void neitherVariableIsTheSupportedAbsentCase() {
    assertEquals(
        Optional.empty(), AgentConfigurationFile.materialize(Optional.empty(), Optional.empty()));
  }

  @Test
  void aBlankPairCountsAsAbsentRatherThanAsHalfAnArrangement() {
    assertEquals(
        Optional.empty(), AgentConfigurationFile.materialize(Optional.of("  "), Optional.of(" ")));
  }

  @Test
  void bothVariablesWriteTheDocumentWhereTheHostSaid() throws Exception {
    Path target = dir.resolve("nested/agent-configuration.json");
    String document = "{\"version\":2,\"surfaces\":[]}";

    Optional<String> written =
        AgentConfigurationFile.materialize(Optional.of(document), Optional.of(target.toString()));

    assertEquals(Optional.of(target.toString()), written);
    assertEquals(document, Files.readString(target), "the bytes are written verbatim");
  }

  @Test
  void aPathWithNoDocumentFailsAtBootRatherThanReadingAsNoConfiguration() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentConfigurationFile.materialize(
                    Optional.empty(), Optional.of(dir.resolve("nothing.json").toString())));

    assertTrue(refused.getMessage().contains("nothing.json"), refused.getMessage());
    assertTrue(refused.getMessage().contains("Send both or neither"), refused.getMessage());
  }

  @Test
  void aDocumentWithNoPathFailsTheSameWay() {
    assertThrows(
        IllegalStateException.class,
        () -> AgentConfigurationFile.materialize(Optional.of("{}"), Optional.empty()));
  }

  @Test
  void theWrittenDocumentIsWhatTheLibraryThenReads() {
    // The whole arrangement end to end: the host's bytes become a file, and the file becomes the
    // per-surface configuration a launch resolves against.
    String document =
        """
        {"version":2,"generatedAt":"2026-09-09T10:00:00Z","surfaces":[
          {"configuration":{
             "surface":"epic.chat","harness":"KIMI","model":"","effort":"",
             "remoteControl":true,"permissionMode":"SKIP_PERMISSIONS","activityTracking":true,
             "systemPrompt":"","initialPrompt":"","mcpServers":[]},
           "externalMcpServers":[]}]}""";
    Path target = dir.resolve("agent-configuration.json");

    String path =
        AgentConfigurationFile.materialize(Optional.of(document), Optional.of(target.toString()))
            .orElseThrow();
    AgentSurfaceConfigurations configurations = AgentSurfaceConfigurations.readFrom(path);

    assertTrue(configurations.documentPresent());
    assertEquals(
        AgentType.KIMI,
        configurations.resolve(AgentSurface.EPIC_CHAT, AgentType.CLAUDE, true).harness());
    // A surface the document does not mention still launches, on the shipped default.
    assertEquals(
        AgentType.CLAUDE,
        configurations.resolve(AgentSurface.WORKSPACE_AGENT, AgentType.CLAUDE, true).harness());
  }

  @Test
  void aMalformedDocumentFailsAtBootNamingTheFile() {
    Path target = dir.resolve("agent-configuration.json");

    String path =
        AgentConfigurationFile.materialize(Optional.of("not json at all"), Optional.of(target.toString()))
            .orElseThrow();

    // Written without complaint — this class does not parse — and refused by the library the moment
    // the daemon reads it, which is still boot.
    InvalidAgentConfigurationException refused =
        assertThrows(
            InvalidAgentConfigurationException.class,
            () -> AgentSurfaceConfigurations.readFrom(path));
    assertTrue(refused.getMessage().contains(target.toString()), refused.getMessage());
  }
}
