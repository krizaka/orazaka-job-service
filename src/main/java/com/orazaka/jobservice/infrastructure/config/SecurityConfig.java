package com.orazaka.jobservice.infrastructure.config;

import com.krizaka.security.web.SecurityBaseline;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless security for the job service.
 *
 * <p>The service was a pure AMQP executor with no HTTP business surface. It used to express that as
 * {@code anyRequest().permitAll()}, which is a different statement: "there is nothing to protect"
 * is a fact about this afternoon, while the filter chain outlives it. That afternoon has now
 * arrived — {@code /internal/v1/capabilities} serves the routing table (ADR-037) — and it landed
 * behind {@code hasAuthority("SERVICE")} because the chain had already been written to expect it.
 *
 * <p>The chain is the Krizaka security baseline every service shares: actuator health/info and
 * preflight open, everything else authenticated against the shared HS256 session secret, with the
 * {@code roles} claim mapped straight onto authorities.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  /**
   * The filter chain: the Krizaka security baseline, then a valid session JWT for everything else.
   *
   * <p>The baseline ({@link SecurityBaseline}) opens the CORS preflight, health, info and the error
   * page, and reserves {@code /internal/v1/**} for the {@code SERVICE} authority — authenticated,
   * not merely unrouted: the edge not routing {@code /internal/**} is topology, and one SSRF turns
   * topology into an anonymous call (ADR-035). The session decoder and the {@code roles}-claim
   * converter come from krizaka-security ({@code krizaka.security.jwt.secret}).
   *
   * @param http the builder
   * @param roles the {@code roles}-claim converter, with no authority prefix
   * @return the built chain
   * @throws Exception if the chain cannot be built
   */
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtAuthenticationConverter roles) throws Exception {
    return SecurityBaseline.apply(http, auth -> {})
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(roles)))
        .build();
  }
}
