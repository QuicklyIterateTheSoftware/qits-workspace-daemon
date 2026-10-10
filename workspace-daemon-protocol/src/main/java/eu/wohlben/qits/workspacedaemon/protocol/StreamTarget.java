package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * Which loopback listener an {@link OpenStream} is to be piped to.
 *
 * <p><b>A name, never an address.</b> The whole point of the reverse tunnel is that the host never
 * learns — and never states — an address inside the container: {@link OpenStream#path()} is refused
 * unless it is host-relative for exactly that reason. A second listener could not be reached by
 * putting its port on the wire without giving that rule up, so the host names <em>what</em> it
 * wants and the daemon alone knows where that is. The set is closed on purpose: this enum is the
 * daemon's allow-list, and a target it cannot name is a frame it drops rather than a port it dials.
 *
 * <p>{@link #API} is the default and is <b>not encoded</b> ({@link DaemonCodec}), so an older host
 * that never learned about targets keeps addressing a newer daemon exactly as it did.
 *
 * <p>{@code EDITOR} was the second value until capability 10 removed the web editor (qits-1152,
 * rethought in qits-1150). A host that still names it is refused like any unknown target.
 */
public enum StreamTarget {

  /** {@code WorkspaceApi} on {@code qits.workspace-daemon.api-port} — the original, the default. */
  API
}
