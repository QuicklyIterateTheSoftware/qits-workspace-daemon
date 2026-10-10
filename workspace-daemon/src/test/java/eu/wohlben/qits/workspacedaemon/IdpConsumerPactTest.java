package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of this daemon's one REST call</b> (ticket qits-1149): {@link
 * ControlSocket#authorization()} exchanging the commissioned client pair for the control socket's
 * bearer at qits-idp's token endpoint — {@code client_secret_post}, the pair and the audience in the
 * form body, no {@code Basic} header. It reads {@code access_token} and nothing else.
 *
 * <p>The request (method, path, form body) comes from qits-idp's recording under {@value #STATE};
 * the token is opaque, so the pact binds it by type only. The committed pact is {@code
 * pacts/qits-workspace-daemon_qits-idp-service.json}.
 */
class IdpConsumerPactTest {

  static final String STATE = "a commissioned client";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final GoldenInteraction ISSUE_TOKEN =
      GoldenInteraction.of(Trigger.schedule("ControlSocket.connect"), STATE, "issueToken")
          .consumes("access_token");

  static final ConsumerPact PACT = ConsumerPact.of("qits-workspace-daemon", IDP, ISSUE_TOKEN);

  @Test
  void theCommissionedPairIsExchangedForTheDialHomeBearer() {
    PACT.run(
        ISSUE_TOKEN,
        (url, recorded) -> {
          Map<String, String> params = recorded.params();
          ControlSocket socket = new ControlSocket();
          socket.commissionedClientId = Optional.of(params.get("clientId"));
          socket.commissionedClientSecret = Optional.of(params.get("clientSecret"));
          socket.authAudience = Optional.of(params.get("audience"));
          socket.authTokenUrl = Optional.of(url + recorded.path());
          Optional<String> bearer = socket.authorization().get();
          assertTrue(
              bearer.isPresent() && bearer.get().startsWith("Bearer "),
              "the minted token is the dial-home bearer: " + bearer);
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.assertEveryInteractionCarriesBothReferences();
    PACT.compareOrWritePactFile();
  }
}
