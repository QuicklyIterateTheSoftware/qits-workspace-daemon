package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.archrules.ProviderStateCoverageRules;
import org.junit.jupiter.api.Test;

/**
 * Every state recorded in golden-masters/index.json has its {@code @State} callback on {@link
 * ConsumerPactVerificationTest}, so a missing one fails here instead of in a consumer's pin
 * (qits-1754).
 */
class ProviderStateCoverageTest {

  @Test
  void everyRecordedStateHasItsCallback() {
    ProviderStateCoverageRules.assertCovered(ConsumerPactVerificationTest.class);
  }
}
