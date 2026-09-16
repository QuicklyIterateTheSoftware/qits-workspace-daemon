package eu.wohlben.qits.workspacedaemon.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * <b>What this jar was released with</b>: the {@code qits/workspace} image tag and the {@code
 * qits-workspace-daemon} binary version of the release that published this artifact.
 *
 * <p>It exists so a consumer's <em>pom</em> decides which workspace image it starts, instead of a
 * configuration entry rewritten underneath it on every image release. qits-workspaces used to read
 * {@code qits.workspace.image-version} — written by qits-configuration's release listener the
 * moment an image was pushed — so a new daemon reached a real workspace without the pair ever
 * having been built, let alone tested, together. Now the version travels as the version of the
 * artifact that also carries the protocol both sides speak: bumping one is bumping the other, the
 * maintenance train moves it like any internal library, and the consumer's own gate is where a
 * daemon that broke the wire fails.
 *
 * <p><b>The value is this module's {@code ${project.version}}, resolved at build time</b> into
 * {@code workspace-image.properties} beside this class rather than written down as a literal. The
 * release flow stamps the pom with the version it is about to tag, and the same build produces the
 * image and pushes it under that tag — so "the version of this jar", "the image tag" and "the
 * daemon binary's version" are one string by construction and cannot drift apart by an edit
 * somebody forgot. A build from a working tree therefore names the <em>previous</em> release, which
 * is honest: nothing has been published for the tree in hand.
 *
 * <p>Framework-free like the rest of this module — a {@code Properties} load off the classpath, no
 * config system, no injection — because the daemon native image and the consuming service have no
 * framework in common.
 */
public final class WorkspaceImage {

  /** The resource the build filters {@code ${project.version}} into, beside this class. */
  private static final String RESOURCE = "workspace-image.properties";

  /**
   * The image repository the workspace container is started from, unqualified. Unqualified for the
   * reason {@code .config/qits/ci-event-release.yml} gives for the {@code artifacts:} declaration:
   * the registry is one service under several addresses ({@code $QITS_BUILD_REGISTRY} to the
   * builder, {@code $QITS_REGISTRY} to the host daemon), and an OCI reference cannot carry a path
   * prefix — so the reader supplies its own host.
   */
  public static final String REPOSITORY = "qits/workspace";

  /**
   * The daemon's artifact name in qits-artifacts' {@code daemons} store — the coordinate the
   * release pipeline PUTs the bare binary under, and the one a consumer's pin test downloads to
   * prove the pair. Named here rather than at the call site because it is the same string the
   * pipeline writes.
   */
  public static final String DAEMON_NAME = "qits-workspace-daemon";

  /**
   * The released version: the {@code qits/workspace} tag, and the {@code qits-workspace-daemon}
   * binary version, of the release that published this jar.
   */
  public static final String VERSION = readVersion();

  private static String readVersion() {
    Properties p = new Properties();
    try (InputStream in = WorkspaceImage.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        // Not a recoverable state and not worth a fallback: a jar of this module with the resource
        // missing is a broken build, and a consumer that silently started "" or "latest" is the
        // exact failure this class exists to remove.
        throw new IllegalStateException(
            RESOURCE + " is not on the classpath beside " + WorkspaceImage.class.getName());
      }
      p.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + RESOURCE, e);
    }
    String version = p.getProperty("version", "");
    if (version.isBlank() || version.startsWith("$")) {
      // `$` catches the one mistake that would otherwise ship: resource filtering switched off,
      // leaving the literal `${project.version}` to be used as an image tag.
      throw new IllegalStateException(RESOURCE + " carries no resolved version: '" + version + "'");
    }
    return version;
  }

  private WorkspaceImage() {}
}
