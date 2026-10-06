package com.orazaka.jobservice.infrastructure.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mints the machine-to-machine token that authenticates a call to {@code /internal/v1/**}.
 *
 * <p>The claim is {@code roles: ["SERVICE"]}. Every service's {@code JwtAuthenticationConverter} is
 * configured {@code setAuthoritiesClaimName("roles")} with an empty authority prefix, so the
 * authority seen by {@code hasAuthority(...)} is the raw string {@code SERVICE} — not {@code
 * SCOPE_SERVICE}, not {@code ROLE_SERVICE}. Getting that wrong fails closed against a correct
 * token, which invites "fixing" the matcher instead of the claim (ADR-035).
 *
 * <p>Signed with the shared HS256 identity secret, because that is the trust root every validator
 * already has. It follows that any process holding the secret can mint {@code SERVICE} — the
 * boundary is the secret, not the caller. That is a real limit and the reason mTLS is deferred
 * rather than dismissed; ADR-035 records it.
 *
 * <p>Hand-rolled over {@code javax.crypto} rather than pulling a JOSE stack into a thin Tier-2
 * client: HS256 signing is an HMAC and a base64url join, while *verification* — the part where
 * subtlety lives — stays with Nimbus on the server side. Wave 3's {@code orazaka-service-kit} is
 * where this class and its twins become one.
 *
 * <p><b>Why this host was the one without a copy.</b> {@code HttpUserDirectoryAdapter} built its
 * {@code RestClient} with no {@code requestInitializer} at all, so every call it made to identity's
 * {@code /internal/v1/users/{id}} went out anonymous and came back 401 — while {@code
 * application.yml} carried a {@code service-secret} that {@code IdentityDirectoryProperties} had no
 * field for and nothing read. The effect was that any asynchronous job needing its actor's context
 * failed at the first step, which is how a Studio run surfaced it. Found by running the phase-D
 * acceptance criterion; unrelated to packs, and older than them.
 */
public final class ServiceTokenProvider {

  private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
  private static final String HMAC_SHA256 = "HmacSHA256";

  /** Short-lived on purpose: a leaked service token should expire before it is useful. */
  private static final Duration LIFETIME = Duration.ofMinutes(5);

  private final byte[] secret;
  private final String subject;

  public ServiceTokenProvider(String secret, String subject) {
    Objects.requireNonNull(secret, "the shared identity secret is required to call /internal/v1");
    if (secret.length() < 32) {
      throw new IllegalArgumentException("the HS256 secret must be at least 32 characters");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.subject = Objects.requireNonNull(subject, "subject required");
  }

  /**
   * A freshly signed token.
   *
   * <p>Minted per call rather than cached: the lifetime is minutes, an HMAC costs microseconds, and
   * a cache would need invalidation logic whose only purpose is to save that.
   *
   * @return the compact JWS
   */
  public String token() {
    Instant now = Instant.now();
    String payload =
        "{\"sub\":\"%s\",\"roles\":[\"SERVICE\"],\"iat\":%d,\"exp\":%d}"
            .formatted(subject, now.getEpochSecond(), now.plus(LIFETIME).getEpochSecond());

    String signingInput =
        encode(HEADER.getBytes(StandardCharsets.UTF_8))
            + "."
            + encode(payload.getBytes(StandardCharsets.UTF_8));
    return signingInput + "." + encode(sign(signingInput));
  }

  private byte[] sign(String signingInput) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(secret, HMAC_SHA256));
      return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot sign the service token", e);
    }
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
