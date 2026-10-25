package com.krizaka.orazaka.jobservice.infrastructure.adapter.checks;

import com.krizaka.orazaka.core.domain.ports.outbound.CheckKeyStore;
import com.krizaka.orazaka.core.infrastructure.checks.MockConnector;
import java.util.Optional;

/**
 * The provider keys of the checks while they run on the mock connector (orazaka-packs#138): the
 * mock's provider gets a fixed key that is not a secret (the mock calls nothing), every other
 * provider has none, so a real connector is refused {@code NO_KEY} until the sealed keys are wired.
 * No actor has a key of their own yet.
 */
public final class MockProviderKeyStore implements CheckKeyStore {

  /** The mock's provider name, read from the connector rather than retyped. */
  private static final String MOCK_PROVIDER = new MockConnector().provider();

  /** Not a secret: the mock connector reaches no network. */
  private static final String MOCK_KEY = "mock-key-not-a-secret";

  @Override
  public Optional<String> userKey(String userId, String provider) {
    return Optional.empty();
  }

  @Override
  public Optional<String> platformKey(String provider) {
    return MOCK_PROVIDER.equals(provider) ? Optional.of(MOCK_KEY) : Optional.empty();
  }
}
