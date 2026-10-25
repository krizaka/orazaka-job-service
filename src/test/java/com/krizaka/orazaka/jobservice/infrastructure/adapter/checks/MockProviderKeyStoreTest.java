package com.krizaka.orazaka.jobservice.infrastructure.adapter.checks;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.core.infrastructure.checks.MockConnector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Only the mock's provider has a key until the sealed keys are wired. */
class MockProviderKeyStoreTest {

  private final MockProviderKeyStore keys = new MockProviderKeyStore();

  @Test
  @DisplayName("the mock's provider has a fixed platform key")
  void theMockHasAKey() {
    assertThat(keys.platformKey(new MockConnector().provider())).isPresent();
  }

  @Test
  @DisplayName("any other provider has none: NO_KEY")
  void anyOtherHasNone() {
    assertThat(keys.platformKey("soquij")).isEmpty();
    assertThat(keys.platformKey("certn")).isEmpty();
  }

  @Test
  @DisplayName("no actor has a key of their own")
  void noUserKey() {
    assertThat(keys.userKey("manager-1", new MockConnector().provider())).isEmpty();
  }
}
