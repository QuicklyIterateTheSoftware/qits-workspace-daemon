package eu.wohlben.qits.workspacedaemon;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import java.util.List;
import java.util.Map;
import kotlin.Pair;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against this daemon</b> (ticket qits-1149), the way
 * qits-workspaces-service and qits-edge-service do — plain JUnit here, because nothing in this
 * repository boots Quarkus in a test.
 *
 * <p>The pacts come off the test classpath ({@link ClasspathPactLoader}): a consumer publishes
 * {@code pacts/<consumer>_qits-workspace-daemon.json} in a jar, and this repository pins that jar
 * as a test dependency. <b>No consumer pins one yet</b>, so {@code @IgnoreNoPactsToVerify} lets an
 * empty classpath pass. When the first is pinned, drop the annotation and set {@link
 * ClasspathPactLoader#REQUIRED} to true.
 *
 * <p>Each interaction gets a fresh daemon ({@link ProviderStates}) on an ephemeral port, and every
 * request carries the daemon token, as qits-workspaces' proxy sends it: a consumer's pact does not
 * carry the token, because the token is a per-container secret, not part of the contract.
 */
@EnabledOnOs(OS.LINUX)
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@IgnoreNoPactsToVerify
class ConsumerPactVerificationTest {

  /** The provider as a consumer pact names it: the repository name. */
  static final String PROVIDER = "qits-workspace-daemon";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  private ProviderStates daemon;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // no pact to verify: @IgnoreNoPactsToVerify's single empty run
    }
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!ProviderStates.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-workspace-daemon does not answer for — it answers for "
                + ProviderStates.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    daemon = ProviderStates.start();
    context.setTarget(new DaemonTarget(daemon.port()));
  }

  @AfterEach
  void stop() {
    if (daemon != null) {
      daemon.close();
      daemon = null;
    }
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    if (context != null) {
      context.verifyInteraction();
    }
  }

  @State(ProviderStates.SIGNED_IN)
  Map<String, String> signedIn() {
    return daemon.setUp(ProviderStates.SIGNED_IN);
  }

  @State(ProviderStates.AGENT_RUNNING)
  Map<String, String> anAgentRunning() {
    return daemon.setUp(ProviderStates.AGENT_RUNNING);
  }

  /** The daemon's loopback port, with the daemon token on every request. */
  static final class DaemonTarget extends HttpTestTarget {

    DaemonTarget(int port) {
      super("127.0.0.1", port);
    }

    @Override
    public Pair<Object, Object> prepareRequest(
        Pact pact, Interaction interaction, Map<String, Object> context) {
      Pair<Object, Object> prepared = super.prepareRequest(pact, interaction, context);
      ((HttpRequest) prepared.getFirst())
          .setHeader("Authorization", "Bearer " + ProviderStates.TOKEN);
      return prepared;
    }
  }
}
