package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ControlSocketAuthenticationTest {

  @Test
  void commissionedClientMintsTheBearerUsedForDialHome() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    idp.createContext(
        "/idp/token",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] answer = "{\"access_token\":\"machine-token\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    idp.start();
    try {
      ControlSocket socket = new ControlSocket();
      socket.commissionedClientId = Optional.of("workspace-1");
      socket.commissionedClientSecret = Optional.of("one-time-secret");
      socket.authTokenUrl =
          Optional.of("http://127.0.0.1:" + idp.getAddress().getPort() + "/idp/token");
      socket.authAudience = Optional.of("dev-qits-workspaces");

      assertEquals(Optional.of("Bearer machine-token"), socket.authorization().get());
      // client_secret_post: the pair travels in the form body, and no Basic header is sent — the
      // edge eats a Basic header rather than forwarding it.
      assertNull(authorization.get(), "no Authorization header on the mint");
      assertTrue(body.get().contains("grant_type=client_credentials"), body.get());
      assertTrue(body.get().contains("client_id=workspace-1"), body.get());
      assertTrue(body.get().contains("client_secret=one-time-secret"), body.get());
      assertTrue(body.get().contains("audience=dev-qits-workspaces"), body.get());
    } finally {
      idp.stop(0);
    }
  }

  @Test
  void theMintedPairIsUrlEncodedInTheFormBody() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer idp = tokenEndpoint(body, new AtomicInteger());
    try {
      ControlSocket socket = commissioned(idp);
      socket.commissionedClientSecret = Optional.of("s&cret=+/");

      socket.authorization().get();

      assertTrue(body.get().contains("client_secret=s%26cret%3D%2B%2F"), body.get());
    } finally {
      idp.stop(0);
    }
  }

  @Test
  void theWorkspaceTokenIsTheBearerAndNothingIsMinted() throws Exception {
    AtomicInteger mints = new AtomicInteger();
    HttpServer idp = tokenEndpoint(new AtomicReference<>(), mints);
    try {
      // The pair is configured too: the token must win outright, not merely when the pair is
      // absent.
      ControlSocket socket = commissioned(idp);
      socket.token = Optional.of("t");

      assertEquals(Optional.of("Bearer t"), socket.authorization().get());
      assertEquals(0, mints.get(), "the token endpoint must not be called");
    } finally {
      idp.stop(0);
    }
  }

  @Test
  void aBlankTokenIsNoToken() throws Exception {
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.of("  ");
    socket.commissionedClientId = Optional.empty();
    socket.commissionedClientSecret = Optional.empty();
    socket.authTokenUrl = Optional.empty();
    socket.authAudience = Optional.empty();

    assertEquals(Optional.empty(), socket.authorization().get());
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
    // (wss://<vhost>/workspaces/daemon/<id>) rather than the old in-network ws dial. The pair
    // fields are left empty to prove the token is the whole credential without any of them
    // configured.
    ControlSocket socket = new ControlSocket();
    socket.token = Optional.of("qits_tok_abc123");
    socket.commissionedClientId = Optional.empty();
    socket.commissionedClientSecret = Optional.empty();
    socket.authTokenUrl = Optional.empty();
    socket.authAudience = Optional.empty();

    Optional<String> authorization = socket.authorization().get();
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
  void noCommissionKeepsTheDeveloperSocketAnonymous() throws Exception {
    ControlSocket socket = new ControlSocket();
    socket.commissionedClientId = Optional.empty();
    socket.commissionedClientSecret = Optional.empty();
    socket.authTokenUrl = Optional.empty();
    socket.authAudience = Optional.empty();

    assertEquals(Optional.empty(), socket.authorization().get());
  }

  private static HttpServer tokenEndpoint(AtomicReference<String> body, AtomicInteger calls)
      throws Exception {
    HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    idp.createContext(
        "/idp/token",
        exchange -> {
          calls.incrementAndGet();
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] answer = "{\"access_token\":\"machine-token\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    idp.start();
    return idp;
  }

  private static ControlSocket commissioned(HttpServer idp) {
    ControlSocket socket = new ControlSocket();
    socket.commissionedClientId = Optional.of("workspace-1");
    socket.commissionedClientSecret = Optional.of("one-time-secret");
    socket.authTokenUrl =
        Optional.of("http://127.0.0.1:" + idp.getAddress().getPort() + "/idp/token");
    socket.authAudience = Optional.of("dev-qits-workspaces");
    return socket;
  }
}
