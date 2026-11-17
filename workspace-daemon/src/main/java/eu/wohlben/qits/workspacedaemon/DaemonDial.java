package eu.wohlben.qits.workspacedaemon;

import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.util.Optional;

/**
 * How this daemon dials qits, shared by the two dials it makes: the control socket ({@link
 * ControlSocket}) and every tunnel dial-back ({@link DaemonStreamTunnel}). Both go to the same
 * authority — the dial-back's is taken from the control-socket url — so they must agree on the
 * scheme, the port and the credential, and having one place say so is what keeps them agreeing.
 *
 * <p><b>{@code wss} is TLS.</b> A workspace carrying a {@code QITS_TOKEN} — runner-placed, or
 * (since qits-1084) an admin or editor (DIRECT) workspace — reaches qits only through the public
 * edge ({@code wss://workspaces.qits.<domain>/…}), which serves a publicly trusted certificate; a
 * {@code ws} url is a plain dial, the clone-alone/local topology's, made with the same token when
 * there is one and anonymously when there is none. The port defaults to the scheme's own, 443 or
 * 80, when the url names none. SNI needs nothing here: with {@code ssl} on,
 * Vert.x hands the connect host to the TLS engine as the peer host and the JDK engine sends it as
 * the server name. The certificate is checked against the JVM's (or the native image's) default
 * trust store and against that host ({@link #clientOptions}).
 */
final class DaemonDial {

  private DaemonDial() {}

  /**
   * How long one dial may take to open its TCP connection before it counts as failed. Vert.x's own
   * default is 60 s, twice the 30 s qits-workspaces gives a new container to dial home: one dial
   * into a black hole — a SYN nobody answers while the edge does not yet route this container —
   * would spend the whole window on a single attempt and leave none for the retry that would have
   * worked (qits-2294). Five seconds is far above any healthy connect to the edge or to {@code
   * qits-net}, and still leaves room for several more dials inside the window.
   */
  static final int CONNECT_TIMEOUT_MS = 5_000;

  /**
   * A client for either dial: host verification on, the default trust store, and {@link
   * #CONNECT_TIMEOUT_MS}. The tunnel's dial-backs share the timeout on purpose — a dial-back that
   * hangs is a terminal or a file view that hangs with it.
   */
  static WebSocketClientOptions clientOptions() {
    return new WebSocketClientOptions().setVerifyHost(true).setConnectTimeout(CONNECT_TIMEOUT_MS);
  }

  /** Whether {@code uri} is to be dialled over TLS — its scheme is {@code wss}. */
  static boolean tls(URI uri) {
    return "wss".equalsIgnoreCase(uri.getScheme());
  }

  /** The url's own port, else the scheme's default: 443 for {@code wss}, 80 for {@code ws}. */
  static int port(URI uri) {
    return uri.getPort() != -1 ? uri.getPort() : tls(uri) ? 443 : 80;
  }

  /**
   * Connect options for {@code uri}, carrying {@code authorization} (a whole header value, e.g.
   * {@code Bearer …}) when one is present.
   */
  static WebSocketConnectOptions connectOptions(URI uri, Optional<String> authorization) {
    WebSocketConnectOptions options =
        new WebSocketConnectOptions()
            .setHost(uri.getHost())
            .setPort(port(uri))
            .setSsl(tls(uri))
            .setURI(uri.getRawPath());
    authorization.ifPresent(value -> options.addHeader("Authorization", value));
    return options;
  }
}
