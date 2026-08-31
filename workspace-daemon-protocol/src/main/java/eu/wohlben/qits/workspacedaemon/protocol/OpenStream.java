package eu.wohlben.qits.workspacedaemon.protocol;

/**
 * qits → {@code workspace-daemon}: dial back to {@code path} and pipe that connection to the
 * daemon's own loopback HTTP API. The whole of the reverse tunnel, on the wire.
 *
 * <p><b>Why the direction flips.</b> The daemon's HTTP API used to be an inbound listener on {@code
 * 0.0.0.0:13338}, reachable by DNS name from every other container on {@code qits-net} — including
 * other workspaces, each running a coding agent over someone else's untrusted checkout — defended
 * by one shared secret that every one of those agents can read out of its own environment. Rather
 * than harden the secret, the listener stops existing: {@code WorkspaceApi} binds {@code 127.0.0.1},
 * qits asks for a stream over the control socket the daemon already holds open, and the daemon
 * dials <em>out</em> to serve it. There is then no port on {@code qits-net} for a peer workspace to
 * reach at all.
 *
 * <p><b>Why the tunnel carries bytes and not requests.</b> One message, two fields, and adding a
 * daemon endpoint after this costs nothing on the wire — the protocol grows with the transport, not
 * with the endpoint count. An HTTP-envelope framing would need a response envelope, body frames and
 * a stream id, and would still have to special-case the WebSocket upgrades that must themselves
 * traverse the tunnel ({@code /terminal/commands/{id}}, {@code /chat/commands/{id}}). A byte pipe
 * carries an upgrade the same way it carries a GET, because it does not know the difference.
 *
 * @param nonce the credential, and the only thing that names the stream. Host-minted, single-use,
 *     short-lived, and bound to the workspace it was sent to — it is <em>not</em> the shared API
 *     token by another name. The control socket identifies its caller by a path parameter, so
 *     anything on {@code qits-net} can already claim to be any workspace's daemon
 *     (migration-plan.md §9 item 22); a dial-back that named its own workspace would reproduce that
 *     in a second place, so this one names nothing and proves everything.
 * @param path where to dial it, relative to the authority of the daemon's own configured
 *     control-socket url. Carried rather than derived so the endpoint literal lives in one repo,
 *     and so {@code ControlSocket}'s standing property — it dials the url it was handed and parses
 *     no path out of it — survives. <b>The daemon must refuse a path that is not host-relative</b>:
 *     an absolute URL here would be exactly the SSRF primitive that "the host never learns an
 *     address from a container" forbids, only pointed the other way.
 * @param target which of the daemon's loopback listeners the stream is piped to — a {@link
 *     StreamTarget} name, never a port. Optional on the wire: absent decodes to {@link
 *     StreamTarget#API}, and {@code API} is not encoded, so an old host and a new daemon (and
 *     the reverse) keep agreeing about every stream that existed before a second listener did. The
 *     daemon resolves the name against its own allow-list; that is what keeps the "never learn an
 *     address from a container" rule intact while still reaching a second port.
 */
public record OpenStream(String nonce, String path, StreamTarget target) implements DaemonMessage {

  /** Normalizes an absent target to {@link StreamTarget#API}, so no switch ever sees a null. */
  public OpenStream {
    target = target == null ? StreamTarget.API : target;
  }

  /** The pre-target form: a stream to {@link StreamTarget#API}. */
  public OpenStream(String nonce, String path) {
    this(nonce, path, StreamTarget.API);
  }
}
