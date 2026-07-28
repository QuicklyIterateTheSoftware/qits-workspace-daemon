package eu.wohlben.qits.workspacedaemon;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import java.net.URI;
import org.jboss.logging.Logger;

/**
 * The daemon's half of the reverse tunnel: on an {@code OpenStream}, dial back to qits and pipe
 * that WebSocket to a fresh TCP connection to {@link WorkspaceApi} on loopback.
 *
 * <p><b>What this replaces.</b> {@code WorkspaceApi} used to bind {@code 0.0.0.0} and be reachable
 * by DNS name from every other container on {@code qits-net} — every one of which runs a coding
 * agent over someone else's untrusted checkout, with an environment it can read the shared API
 * token straight out of. Now it binds {@code 127.0.0.1} and qits reaches it by asking for a stream
 * over the control socket the daemon already holds open. The port is not a boundary that got
 * stronger; it stopped existing.
 *
 * <p><b>Why a byte pipe.</b> Everything on the wire is one {@code OpenStream}, and adding a daemon
 * endpoint costs nothing after that. The alternative — an HTTP envelope with a response frame and
 * body frames — would have to special-case the two WebSocket upgrades that must themselves traverse
 * the tunnel ({@code /terminal/commands/{id}}, {@code /chat/commands/{id}}). A byte pipe carries an
 * upgrade the same way it carries a GET, because it does not know the difference.
 *
 * <p>Framework-free like {@link HookWebhook} — {@link ControlSocket} constructs it and hands it
 * everything, because a capability class here cannot read configuration. It uses {@code NetClient}
 * and {@code WebSocketClient} from {@code vertx-core}, both already in the native image; nothing new
 * is on the classpath and nothing needs registering with the image builder.
 *
 * <p><b>Nothing here may take the container down.</b> A tunnel that fails to dial, or dies
 * mid-stream, is one failed browser request. Every failure path closes what it opened and logs at
 * DEBUG; the daemon's "never exit on failure" invariant is the reason this class throws nothing out.
 */
final class DaemonStreamTunnel {

  private static final Logger LOG = Logger.getLogger(DaemonStreamTunnel.class);

  /**
   * Concurrent tunnels this daemon will hold open. Its own client, not {@link ControlSocket}'s:
   * {@code WebSocketClientOptions} defaults to 50 connections, and sharing would both cap tunnels at
   * 49 and put the control socket itself in the same pool as the traffic it schedules — a burst of
   * file reads must never be able to queue the heartbeat behind it.
   */
  private static final int MAX_TUNNELS = 256;

  private final Vertx vertx;

  /** The control-socket url, the only address this daemon was ever told. See {@link #dialUrl}. */
  private final String controlSocketUrl;

  /** The loopback port {@link WorkspaceApi} binds. */
  private final int apiPort;

  private volatile WebSocketClient client;
  private volatile NetClient netClient;

  DaemonStreamTunnel(Vertx vertx, String controlSocketUrl, int apiPort) {
    this.vertx = vertx;
    this.controlSocketUrl = controlSocketUrl;
    this.apiPort = apiPort;
  }

  void start() {
    client =
        vertx.createWebSocketClient(new WebSocketClientOptions().setMaxConnections(MAX_TUNNELS));
    netClient = vertx.createNetClient();
  }

  /**
   * Serve one {@code OpenStream}: dial {@code path} back to qits, connect to the loopback API, and
   * pipe the two until either end goes away.
   *
   * <p>Runs on the event loop, deliberately — both connects are non-blocking futures, so there is
   * nothing here worth a worker thread, and the pumps below are handler-driven.
   */
  void open(String nonce, String path) {
    WebSocketClient ws = client;
    NetClient net = netClient;
    if (ws == null || net == null) {
      LOG.debug("stream requested before the tunnel was started — ignored");
      return;
    }
    URI dial;
    try {
      dial = dialUrl(path);
    } catch (IllegalArgumentException refused) {
      // The path arrives from the control socket, which is unauthenticated (anything on qits-net
      // can claim to be any workspace's daemon), so it is untrusted input that becomes an address.
      // Refusing an absolute one here is what keeps this from being an SSRF primitive pointed at
      // everything on the network — the mirror image of the rule that the host never learns an
      // address from a container.
      LOG.warnf("refusing a stream dial-back path: %s", refused.getMessage());
      return;
    }
    int port = dial.getPort() == -1 ? 80 : dial.getPort();
    ws.connect(
            new WebSocketConnectOptions()
                .setHost(dial.getHost())
                .setPort(port)
                .setURI(dial.getRawPath()))
        .onFailure(
            t -> LOG.debugf("stream %s could not dial home: %s", nonce, String.valueOf(t)))
        .onSuccess(
            socket ->
                net.connect(apiPort, "127.0.0.1")
                    .onFailure(
                        t -> {
                          LOG.debugf("stream %s could not reach the local API: %s", nonce, t);
                          socket.close();
                        })
                    .onSuccess(local -> pipe(socket, local)));
  }

  /**
   * Where to dial, from the authority of the control-socket url and a host-relative path.
   *
   * <p>The authority is never taken from the message: it is the one address this container was
   * handed, and the message supplies only the path — which is what keeps {@link ControlSocket}'s
   * property (it dials the url it was given and parses no path out of it) true of this class too.
   * A path that is not host-relative is refused rather than normalised, because a normalisation that
   * quietly accepts {@code //evil/x} or {@code https://evil/} is the whole bug.
   */
  private URI dialUrl(String path) {
    if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
      throw new IllegalArgumentException("not host-relative: " + path);
    }
    URI base = URI.create(controlSocketUrl);
    if (base.getHost() == null) {
      throw new IllegalArgumentException("no authority in the control-socket url");
    }
    return URI.create(
        base.getScheme() + "://" + base.getAuthority() + path);
  }

  /**
   * Pump bytes both ways until either side ends.
   *
   * <p><b>{@code writeBinaryMessage}, never {@code write} or {@code pipeTo}.</b> A WebSocket's
   * {@code write(Buffer)} emits one binary frame of whatever length it was handed, and a {@code
   * NetSocket} read chunk sits at Netty's 65536 default — exactly the default maximum frame size. A
   * large file read or a {@code git diff} splattered into a terminal would then trip the peer's
   * frame limit and close the socket, which presents as "the terminal randomly dies" rather than as
   * a framing bug. {@code writeBinaryMessage} splits into BINARY + CONTINUATION frames.
   *
   * <p>And {@code handler}, never {@code binaryMessageHandler}: the latter aggregates whole messages
   * and enforces a maximum message size, which is a limit a byte stream has no business having.
   *
   * <p>Backpressure is the pause/drain pair on both directions. Without it a slow reader on either
   * end becomes an unbounded heap buffer on this one — the failure mode that made folding all of
   * this onto the control socket unaffordable in the first place, reintroduced per stream.
   */
  private static void pipe(WebSocket remote, NetSocket local) {
    remote.handler(
        buffer -> {
          local.write(buffer);
          if (local.writeQueueFull()) {
            remote.pause();
            local.drainHandler(v -> remote.resume());
          }
        });
    local.handler(
        buffer -> {
          remote.writeBinaryMessage(buffer);
          if (remote.writeQueueFull()) {
            local.pause();
            remote.drainHandler(v -> local.resume());
          }
        });
    remote.endHandler(v -> local.close());
    local.endHandler(v -> remote.close());
    remote.exceptionHandler(t -> local.close());
    local.exceptionHandler(t -> remote.close());
    remote.closeHandler(v -> local.close());
    local.closeHandler(v -> remote.close());
  }

  void close() {
    WebSocketClient c = client;
    if (c != null) {
      c.close();
    }
    NetClient n = netClient;
    if (n != null) {
      n.close();
    }
  }
}
