package eu.wohlben.qits.workspacedaemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Materializes the agent-configuration document the host hands this container, at boot, before
 * anything else starts.
 *
 * <p><b>Why the daemon writes a file the host could have mounted.</b> The epic specifies a mounted
 * JSON document, and this estate's container wire cannot express one: qits-containers' {@code
 * ContainerSpec} admits named volumes and the docker socket and <em>no host path</em>, deliberately,
 * and qits-workspaces holds no docker socket to populate a volume with. So the bytes ride in the
 * environment as {@code QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION}, the path they belong at rides
 * beside them as {@code QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH}, and this writes one to the
 * other so the library reads a file exactly as specified. The path is <em>told</em>, never derived —
 * the same arrangement the API base path and the claude mount have.
 *
 * <p><b>Both or neither, and the asymmetry is the whole point.</b>
 *
 * <ul>
 *   <li><b>Neither</b> is quiet and supported: a container created before this shipped, or a
 *       deployment with no configuration source wired. Every surface then renders the harness
 *       library's shipped constants, which is exactly what it rendered before the epic existed.
 *   <li><b>Both</b> writes the file and hands the path on.
 *   <li><b>Either alone</b> fails the daemon at boot. A path naming a file nothing wrote reads as
 *       "no configuration" to {@code AgentConfigurationDocument.readFrom} — indistinguishable from
 *       the supported absent case — over a container that was meant to have one. That is the
 *       green-while-dead shape this estate keeps removing: the sessions launch, look entirely
 *       normal, and are steered by constants nobody chose. Bytes with no path are the same mistake
 *       from the other side.
 * </ul>
 *
 * <p>A <b>malformed</b> document is not this class's business: it writes bytes and the library
 * parses them, throwing {@code InvalidAgentConfigurationException} naming the offending key. That
 * exception is deliberately not caught anywhere on the boot path — it must reach an operator reading
 * the container's first log lines rather than the first launch of the one surface that was wrong.
 */
final class AgentConfigurationFile {

  private AgentConfigurationFile() {}

  /**
   * Writes {@code document} to {@code path} and answers the path the library should read.
   *
   * @return the path, or empty when the container was created without a document
   * @throws IllegalStateException if exactly one of the two arrived, or the write fails
   */
  static Optional<String> materialize(Optional<String> document, Optional<String> path) {
    String bytes = document.map(String::strip).filter(value -> !value.isEmpty()).orElse(null);
    String target = path.map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
    if (bytes == null && target == null) {
      return Optional.empty();
    }
    if (bytes == null) {
      throw new IllegalStateException(
          "QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH names "
              + target
              + " but QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION is empty. The two are one"
              + " arrangement: a path with no document would read as 'this container has no agent"
              + " configuration', which is a supported state this container is not in. Send both or"
              + " neither.");
    }
    if (target == null) {
      throw new IllegalStateException(
          "QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION carries a document but"
              + " QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH says nowhere to put it. Send both"
              + " or neither.");
    }
    Path file;
    try {
      file = Path.of(target);
    } catch (InvalidPathException e) {
      throw new IllegalStateException(
          "QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH is not a path: " + target, e);
    }
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, bytes, StandardCharsets.UTF_8);
    } catch (IOException e) {
      // Loud, for the reason the partial case is loud: a container that cannot write its own
      // configuration would otherwise fall back to the shipped constants invisibly.
      throw new IllegalStateException(
          "Could not write the agent configuration document to " + file + ": " + e.getMessage(), e);
    }
    return Optional.of(file.toString());
  }
}
