package com.orazaka.jobservice.application.service;

import com.orazaka.identity.domain.model.RateLimitInfo;
import com.orazaka.identity.domain.model.User;
import com.orazaka.identity.domain.model.UserProfile;
import java.util.Optional;

/**
 * The router's outbound view of the identity context after the Phase 2 cutover: principals,
 * profiles, BYOK credentials and rate-limit tiers are resolved over the identity service's internal
 * API instead of in-process ports. Implementations cache aggressively — the JWT TTL already bounds
 * staleness of everything identity-owned.
 */
public interface UserDirectoryService {

  /**
   * Resolves the full user snapshot by id.
   *
   * @param userId the opaque ActorId
   * @return the hydrated {@link User}
   * @throws RuntimeException when the user is unknown or the directory is unreachable
   */
  User getUser(String userId);

  /**
   * Resolves the user's profile for pipeline context assembly.
   *
   * @param userId the opaque ActorId
   * @return the profile, or {@code null} when none exists
   */
  UserProfile getProfile(String userId);

  /** Resolves the user's decrypted BYOK provider key. */
  Optional<String> getDecryptedApiKey(String userId, String providerName);

  /** Resolves the bucket parameters of a rate-limit tier. */
  Optional<RateLimitInfo> getRateLimit(String tierKey);

  /** Resolves the DB-flagged default tier key. */
  Optional<String> getDefaultTierKey();
}
