package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
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
      assertEquals(
          "Basic "
              + Base64.getEncoder()
                  .encodeToString("workspace-1:one-time-secret".getBytes(StandardCharsets.UTF_8)),
          authorization.get());
      assertTrue(body.get().contains("grant_type=client_credentials"), body.get());
      assertTrue(body.get().contains("audience=dev-qits-workspaces"), body.get());
    } finally {
      idp.stop(0);
    }
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
}
