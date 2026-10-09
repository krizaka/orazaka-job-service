package com.orazaka.jobservice.infrastructure.adapter.persistence;

import com.krizaka.users.domain.port.UserDirectoryClient;
import com.orazaka.core.domain.ports.outbound.UserCredentialsProvider;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Implements the core {@link UserCredentialsProvider} outbound port by delegating to this service's
 * {@link UserDirectoryClient} — the cached HTTP view of the identity context. Unlike the shared
 * persistence-bridge adapters, BYOK credential resolution is not a DB concern here: the executor
 * reaches identity over its internal API, so this adapter stays service-local (its collaborator is
 * the per-service {@code UserDirectoryClient}).
 */
@Service
class UserCredentialsProviderAdapter implements UserCredentialsProvider {

  private final UserDirectoryClient userDirectoryService;

  UserCredentialsProviderAdapter(UserDirectoryClient userDirectoryService) {
    this.userDirectoryService =
        Objects.requireNonNull(userDirectoryService, "UserDirectoryClient must not be null");
  }

  @Override
  public Optional<String> getDecryptedApiKey(String userId, String providerName) {
    if (userId == null || providerName == null) {
      return Optional.empty();
    }
    return userDirectoryService.getDecryptedApiKey(userId, providerName);
  }
}
