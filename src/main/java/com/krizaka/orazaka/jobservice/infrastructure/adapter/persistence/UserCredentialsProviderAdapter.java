package com.krizaka.orazaka.jobservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.core.domain.ports.outbound.UserCredentialsProvider;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The core {@link UserCredentialsProvider} port for the job executor: it releases no provider key.
 *
 * <p>The identity service hands a key out only against a grant issued on the user's own session and
 * bound to one run (GHSA-3pf7-rgf2-4x3m). The executor consumes a job from the queue long after the
 * request ended, so it holds no user session and cannot ask for a grant itself, and a service
 * identity can no longer read a user's key. Until the job message carries a grant minted at submit
 * time (krizaka-internal#2359), the engine gets no key here and falls back to the platform model,
 * with a warning.
 */
@Service
class UserCredentialsProviderAdapter implements UserCredentialsProvider {

  private static final Logger logger =
      LoggerFactory.getLogger(UserCredentialsProviderAdapter.class);

  @Override
  public Optional<String> getDecryptedApiKey(String userId, String providerName) {
    if (userId != null && providerName != null) {
      logger.warn(
          "No run-bound grant for user {} and provider {}: the job runs on the platform model.",
          userId,
          providerName);
    }
    return Optional.empty();
  }
}
