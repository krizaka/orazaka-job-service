package com.orazaka.jobservice.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session-JWT validation wiring ({@code orazaka.identity.jwt}) — the billing service's contract
 * copy of the shared HS256 secret the identity service signs with. Every host validates locally, so
 * authorising a credit hold costs no per-request identity hop on the hot path.
 *
 * @param secret the HMAC-SHA256 signing secret (≥ 32 bytes)
 */
@ConfigurationProperties(prefix = "orazaka.identity.jwt")
public record SessionJwtProperties(String secret) {

  /** Compact canonical constructor rejecting a key too short for HS256 (ERR-106). */
  public SessionJwtProperties {
    if (secret == null || secret.length() < 32) {
      throw new IllegalArgumentException(
          "orazaka.identity.jwt.secret must be at least 32 characters (256-bit HS256)");
    }
  }
}
