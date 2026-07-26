package eu.wohlben.qits.workspacedaemon.files;

/**
 * The one failure type this module raises, carrying the HTTP status the eventual transport should
 * answer with. There is no {@code eu.wohlben.qits.domain.error} hierarchy in the daemon — and there
 * must not be, because that package drags JAX-RS in — so the classification travels as data on a
 * plain {@link RuntimeException} and whoever bridges the control socket back to the host maps it:
 *
 * <ul>
 *   <li>{@link Kind#INVALID_PATH} → <b>400</b>. The request named something the browser refuses to
 *       resolve: a lexically unsafe path, a symlink, the {@code .git} directory, or a path that
 *       resolves outside the workspace root. Never leak <em>why</em> beyond the path itself; the
 *       distinction between "escapes the root" and "is a symlink" is not the caller's business.
 *   <li>{@link Kind#NOT_FOUND} → <b>404</b>. The path is well-formed and inside the root but has no
 *       entry of the requested kind. Note that a directory requested as a file is a 404, not a 400
 *       — it matches the host's behaviour, where "not a file" and "no file" are indistinguishable
 *       to the browser UI.
 *   <li>{@link Kind#TOO_LARGE} → <b>413</b>. Reserved for a transport that cannot carry the
 *       response. The <em>browsing policy</em> never raises it: a file over the viewer's size cap
 *       degrades to {@code binary = true} with no content (see {@link WorkspaceFileBrowser}), which
 *       is what the host did and what the UI already renders. It exists so a control-socket frame
 *       limit has a code to fail with instead of inventing a second error channel.
 *   <li>{@link Kind#INTERNAL} → <b>500</b>. A git invocation or a filesystem call failed for a
 *       reason that is not the caller's fault.
 * </ul>
 */
public class WorkspaceFilesException extends RuntimeException {

  /** The failure classification, each pinned to the HTTP status the transport should answer. */
  public enum Kind {
    INVALID_PATH(400),
    NOT_FOUND(404),
    TOO_LARGE(413),
    INTERNAL(500);

    private final int status;

    Kind(int status) {
      this.status = status;
    }

    /** The HTTP status this kind maps to. */
    public int status() {
      return status;
    }
  }

  private final Kind kind;

  public WorkspaceFilesException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public WorkspaceFilesException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /** Shorthand for the transport, so it never has to switch on {@link #kind()} itself. */
  public int status() {
    return kind.status();
  }

  static WorkspaceFilesException invalidPath(String message) {
    return new WorkspaceFilesException(Kind.INVALID_PATH, message);
  }

  static WorkspaceFilesException notFound(String message) {
    return new WorkspaceFilesException(Kind.NOT_FOUND, message);
  }

  static WorkspaceFilesException internal(String message) {
    return new WorkspaceFilesException(Kind.INTERNAL, message);
  }

  static WorkspaceFilesException internal(String message, Throwable cause) {
    return new WorkspaceFilesException(Kind.INTERNAL, message, cause);
  }
}
