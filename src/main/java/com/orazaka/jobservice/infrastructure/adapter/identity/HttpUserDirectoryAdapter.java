package com.orazaka.jobservice.infrastructure.adapter.identity;

import com.krizaka.security.token.ServiceTokenProvider;
import com.orazaka.identity.domain.model.RateLimitInfo;
import com.orazaka.identity.domain.model.User;
import com.orazaka.identity.domain.model.UserProfile;
import com.orazaka.jobservice.application.service.UserDirectoryService;
import com.orazaka.jobservice.infrastructure.config.IdentityDirectoryProperties;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.HttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP adapter over the identity service's internal API — the router's only identity access after
 * the Phase 2 cutover. User/profile/tier lookups carry a short in-memory TTL cache so the hot path
 * stays hop-free in practice; the session-JWT TTL already bounds identity staleness.
 */
@Component
class HttpUserDirectoryAdapter implements UserDirectoryService {

  private final RestClient identityClient;
  private final long cacheTtlMillis;
  private final ConcurrentHashMap<String, CachedEntry<User>> userCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CachedEntry<Optional<UserProfile>>> profileCache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CachedEntry<Optional<RateLimitInfo>>> tierCache =
      new ConcurrentHashMap<>();

  /** Cached value with TTL expiry (millis since epoch). */
  private record CachedEntry<T>(T value, long expiresAt) {}

  /** Wire snapshot of the internal user endpoint (contract copy — no shared jar). */
  record UserSnapshot(
      String id,
      String username,
      String email,
      boolean enabled,
      List<String> authorities,
      Map<String, Object> preferences,
      List<String> activeInterceptions,
      String rateLimitTier) {}

  HttpUserDirectoryAdapter(
      RestClient.Builder restClientBuilder, IdentityDirectoryProperties properties) {
    Objects.requireNonNull(properties, "IdentityDirectoryProperties cannot be null");
    ServiceTokenProvider tokens =
        new ServiceTokenProvider(properties.serviceSecret(), "orazaka-job-service");
    this.identityClient =
        restClientBuilder
            .baseUrl(properties.baseUrl())
            // Attached on the builder, not per call: identity's /internal/v1 surface demands the
            // SERVICE authority, and this client previously carried no Authorization header at
            // all — so every user lookup 401'd and every job that needed its actor's context
            // failed at the first step. Initialising it here is what stops the next method added
            // to this adapter from forgetting it again.
            .requestInitializer(request -> request.getHeaders().setBearerAuth(tokens.token()))
            .build();
    this.cacheTtlMillis = properties.cacheTtl().toMillis();
  }

  @Override
  public User getUser(String userId) {
    Objects.requireNonNull(userId, "userId cannot be null");
    return cached(
        userCache,
        userId,
        () -> {
          UserSnapshot snapshot =
              identityClient
                  .get()
                  .uri("/internal/v1/users/{id}", userId)
                  .retrieve()
                  .body(UserSnapshot.class);
          Objects.requireNonNull(snapshot, "Identity directory returned no body");
          return toUser(snapshot);
        });
  }

  @Override
  public UserProfile getProfile(String userId) {
    Objects.requireNonNull(userId, "userId cannot be null");
    return cached(
            profileCache,
            userId,
            () ->
                Optional.ofNullable(
                    identityClient
                        .get()
                        .uri("/internal/v1/users/{id}/profile", userId)
                        .exchange(HttpUserDirectoryAdapter::profileOrNull)))
        .orElse(null);
  }

  private static UserProfile profileOrNull(
      HttpRequest request, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
      throws IOException {
    return response.getStatusCode().is2xxSuccessful() ? response.bodyTo(UserProfile.class) : null;
  }

  @Override
  public Optional<String> getDecryptedApiKey(String userId, String providerName) {
    CredentialSnapshot snapshot =
        identityClient
            .get()
            .uri("/internal/v1/users/{id}/credentials/{provider}", userId, providerName)
            .exchange(
                (request, response) ->
                    response.getStatusCode().is2xxSuccessful()
                        ? response.bodyTo(CredentialSnapshot.class)
                        : null);
    return Optional.ofNullable(snapshot).map(CredentialSnapshot::apiKey);
  }

  @Override
  public Optional<RateLimitInfo> getRateLimit(String tierKey) {
    Objects.requireNonNull(tierKey, "tierKey cannot be null");
    return cached(tierCache, tierKey, () -> fetchTier("/internal/v1/tiers/{key}", tierKey));
  }

  @Override
  public Optional<String> getDefaultTierKey() {
    return cached(tierCache, "__default__", () -> fetchTier("/internal/v1/tiers/default", null))
        .map(RateLimitInfo::tierKey);
  }

  private Optional<RateLimitInfo> fetchTier(String uri, String key) {
    RateLimitInfo info =
        (key != null ? identityClient.get().uri(uri, key) : identityClient.get().uri(uri))
            .exchange(
                (request, response) ->
                    response.getStatusCode().is2xxSuccessful()
                        ? response.bodyTo(RateLimitInfo.class)
                        : null);
    return Optional.ofNullable(info);
  }

  private static User toUser(UserSnapshot snapshot) {
    return new User(
        UUID.fromString(snapshot.id()),
        snapshot.username(),
        snapshot.email(),
        snapshot.enabled(),
        Set.copyOf(snapshot.authorities()),
        snapshot.preferences(),
        snapshot.activeInterceptions(),
        snapshot.rateLimitTier());
  }

  private <T> T cached(
      ConcurrentHashMap<String, CachedEntry<T>> cache, String key, Supplier<T> loader) {
    long now = System.currentTimeMillis();
    CachedEntry<T> entry = cache.get(key);
    if (entry != null && entry.expiresAt() > now) {
      return entry.value();
    }
    T value = loader.get();
    cache.put(key, new CachedEntry<>(value, now + cacheTtlMillis));
    return value;
  }

  /** Wire snapshot of the internal credential endpoint. */
  record CredentialSnapshot(String apiKey) {}
}
