package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ControlSocketAuthenticationTest {

  @Test
  void theWorkspaceTokenIsTheBearer() {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.of(" t ");

    assertEquals(Optional.of("Bearer t"), socket.authorization());
  }

  @Test
  void aBlankTokenIsNoToken() throws Exception {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.of("  ");

    assertEquals(Optional.empty(), socket.authorization());
  }

  @Test
  void aWssUrlDialsTlsOnTheDefaultTlsPort() {
    WebSocketConnectOptions options =
        ControlSocket.dialOptions(
            URI.create("wss://workspaces.qits.example/workspaces/daemon/7"),
            Optional.of("Bearer t"));

    assertTrue(options.isSsl());
    assertEquals(443, options.getPort());
    assertEquals("workspaces.qits.example", options.getHost());
    assertEquals("/workspaces/daemon/7", options.getURI());
    assertEquals("Bearer t", options.getHeaders().get("Authorization"));
  }

  @Test
  void aWsUrlDialsPlainOnPort80() {
    WebSocketConnectOptions options =
        ControlSocket.dialOptions(URI.create("ws://qits/workspaces/daemon/7"), Optional.empty());

    assertFalse(options.isSsl());
    assertEquals(80, options.getPort());
    assertTrue(options.getHeaders() == null || options.getHeaders().get("Authorization") == null);
  }

  @Test
  void anExplicitPortWinsOverTheSchemeDefault() {
    assertEquals(
        8443,
        ControlSocket.dialOptions(URI.create("wss://h:8443/x"), Optional.empty()).getPort());
    assertEquals(
        8080, ControlSocket.dialOptions(URI.create("ws://h:8080/x"), Optional.empty()).getPort());
  }

  @Test
  void aDirectWorkspacesWssUrlCarriesTheWorkspaceTokenAsBearer() throws Exception {
    // Since qits-1084 an admin or editor (DIRECT) workspace carries a QITS_TOKEN exactly like a
    // runner-placed one, and its daemon url is the same public-edge shape
    // (wss://<vhost>/workspaces/daemon/<id>) rather than an in-network ws dial. The token is the
    // whole credential: nothing else is configured.
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.of("qits_tok_abc123");

    Optional<String> authorization = socket.authorization();
    assertEquals(Optional.of("Bearer qits_tok_abc123"), authorization);

    WebSocketConnectOptions options =
        ControlSocket.dialOptions(
            URI.create("wss://workspaces.qits.example.test/workspaces/daemon/1251"),
            authorization);

    assertTrue(options.isSsl());
    assertEquals(443, options.getPort());
    assertEquals("workspaces.qits.example.test", options.getHost());
    assertEquals("/workspaces/daemon/1251", options.getURI());
    assertEquals("Bearer qits_tok_abc123", options.getHeaders().get("Authorization"));
  }

  @Test
  void noTokenKeepsTheDeveloperSocketAnonymous() {
    // Deliberate, not a gap: the clone-alone/local topology dials with no credential, and so does
    // qits-workspaces' WorkspaceDaemonPinIT, which runs this daemon's jar with every QITS_ variable
    // removed against a stand-in host. Who may open a control socket is the host's decision.
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.empty();

    assertEquals(Optional.empty(), socket.authorization());
    WebSocketConnectOptions options =
        ControlSocket.dialOptions(
            URI.create("ws://qits/workspaces/daemon/7"), socket.authorization());
    assertTrue(options.getHeaders() == null || options.getHeaders().get("Authorization") == null);
  }
}
