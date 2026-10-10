package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of this daemon's one REST call</b> (ticket qits-1149): {@link
 * ControlSocket#authorization()} exchanging the commissioned client pair for the control socket's
 * bearer at qits-idp's token endpoint — {@code client_secret_post}, the pair and the audience in the
 * form body, no {@code Basic} header. It reads {@code access_token} and nothing else.
 *
 * <p><b>The row waits on qits-idp.</b> qits-idp records {@code issueToken} only for a static
 * service client over {@code Basic}. This call needs it recorded under the state {@value #STATE}
 * with params {@code clientId}, {@code clientSecret} and {@code audience}, answered for a {@code
 * client_secret_post} form. When qits-idp publishes it: pin {@code
 * eu.wohlben.qits:qits-idp-golden-masters} test-scoped, enable the test, add a pact-file test that
 * commits {@code pacts/qits-workspace-daemon_qits-idp-service.json}, and add qits-idp-service under
 * {@code release.yml} {@code contracts.pacts}.
 *
 * <p>Plain JUnit and pact-jvm's programmatic runner, one mock server per pact, as qits-workspaces
 * runs its own.
 */
class IdpConsumerPactTest {

  static final String STATE = "a commissioned client";

  @Test
  @Disabled("needs provider state 'a commissioned client' for issueToken in qits-idp-service")
  void theCommissionedPairIsExchangedForTheDialHomeBearer() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.IDP, STATE);
    String form =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(params.get("clientId"), StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(params.get("clientSecret"), StandardCharsets.UTF_8)
            + "&audience="
            + URLEncoder.encode(params.get("audience"), StandardCharsets.UTF_8);
    V4Pact pact =
        GoldenMasters.interaction(
                new PactBuilder(
                    GoldenMasters.CONSUMER, GoldenMasters.IDP.repository(), PactSpecVersion.V4),
                GoldenMasters.IDP,
                STATE,
                "issueToken",
                GoldenMasters.Trigger.schedule("ControlSocket.connect"),
                form,
                "application/x-www-form-urlencoded",
                List.of("$.access_token"))
            .toPact();

    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            pact,
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              ControlSocket socket = new ControlSocket();
              socket.commissionedClientId = Optional.of(params.get("clientId"));
              socket.commissionedClientSecret = Optional.of(params.get("clientSecret"));
              socket.authAudience = Optional.of(params.get("audience"));
              socket.authTokenUrl =
                  Optional.of(
                      mockServer.getUrl()
                          + GoldenMasters.operation(GoldenMasters.IDP, STATE, "issueToken")
                              .getString("path"));
              Optional<String> bearer = socket.authorization().get();
              assertTrue(
                  bearer.isPresent() && bearer.get().startsWith("Bearer "),
                  "the minted token is the dial-home bearer: " + bearer);
              return null;
            });
    if (!(result instanceof PactVerificationResult.Ok)) {
      fail("qits-idp-service pact failed: " + result.getDescription() + " — " + result);
    }
  }
}
