package com.orazaka.jobservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceTokenProviderTest {

  private static final String SECRET = "orazaka-test-secret-at-least-32-characters!";

  @Test
  @DisplayName("Claims roles:[SERVICE] — the raw authority every /internal/v1 matcher expects")
  void claimsTheServiceRole() {
    String token = new ServiceTokenProvider(SECRET, "orazaka-job-service").token();

    String payload =
        new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

    // Not "SCOPE_SERVICE", not "ROLE_SERVICE": the converters set an empty authority prefix, so a
    // prefixed claim fails closed against a correct matcher (ADR-035).
    assertThat(payload).contains("\"roles\":[\"SERVICE\"]");
    assertThat(payload).contains("\"sub\":\"orazaka-job-service\"");
    assertThat(token.split("\\.")).hasSize(3);
  }

  @Test
  @DisplayName("Refuses a secret too short to sign HS256 with")
  void refusesAWeakSecret() {
    assertThatThrownBy(() -> new ServiceTokenProvider("short", "orazaka-job-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
