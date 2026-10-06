package com.orazaka.jobservice.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Wiring of the identity-service internal API consumed by the router's user directory ({@code
 * orazaka.router.identity-directory}).
 *
 * @param baseUrl the identity service base URL
 * @param serviceSecret the shared HS256 secret used to mint the {@code SERVICE} token every {@code
 *     /internal/v1} call must carry. It was present in {@code application.yml} from the start and
 *     had no field here to bind to, so the directory called identity anonymously and every lookup
 *     came back 401 — required, not defaulted, so that omission cannot recur silently
 * @param cacheTtl how long user/profile/tier lookups are cached (staleness is already bounded by
 *     the session-JWT TTL)
 */
@ConfigurationProperties(prefix = "orazaka.job-service.identity-directory")
public record IdentityDirectoryProperties(
    String baseUrl, String serviceSecret, @DefaultValue("PT60S") Duration cacheTtl) {

  public IdentityDirectoryProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("identity-directory base-url is required");
    }
    if (serviceSecret == null || serviceSecret.isBlank()) {
      throw new IllegalArgumentException("identity-directory service-secret is required");
    }
    if (cacheTtl == null || cacheTtl.isNegative()) {
      throw new IllegalArgumentException("identity-directory cache-ttl must be positive");
    }
  }
}
