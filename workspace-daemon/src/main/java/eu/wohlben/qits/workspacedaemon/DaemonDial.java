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
 * <p><b>{@code wss} is TLS.</b> A runner-placed workspace reaches qits only through the public edge
 * ({@code wss://workspaces.qits.<domain>/…}), which serves a publicly trusted certificate; a {@code
 * ws} url is the plain in-network dial a DIRECT workspace has always made. The port defaults to the
 * scheme's own, 443 or 80, when the url names none. SNI needs nothing here: with {@code ssl} on,
 * Vert.x hands the connect host to the TLS engine as the peer host and the JDK engine sends it as
 * the server name. The certificate is checked against the JVM's (or the native image's) default
 * trust store and against that host ({@link #clientOptions}).
 */
final class DaemonDial {

  private DaemonDial() {}

  /** A client for either dial: host verification on, the default trust store. */
  static WebSocketClientOptions clientOptions() {
    return new WebSocketClientOptions().setVerifyHost(true);
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
