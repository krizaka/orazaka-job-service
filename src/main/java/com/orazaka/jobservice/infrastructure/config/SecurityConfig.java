package com.orazaka.jobservice.infrastructure.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
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
 * <p>The chain is the billing service's, verbatim: actuator health/info and preflight open,
 * everything else authenticated against the shared HS256 session secret, with the {@code roles}
 * claim mapped straight onto authorities.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SessionJwtProperties.class)
public class SecurityConfig {

  /**
   * Local HS256 decoder over the shared identity secret.
   *
   * @param properties the shared-secret wiring
   * @return a decoder that validates a session JWT without calling identity
   */
  @Bean
  JwtDecoder identityJwtDecoder(SessionJwtProperties properties) {
    return NimbusJwtDecoder.withSecretKey(
            new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }

  /**
   * Maps the identity JWT's {@code roles} claim straight onto authorities.
   *
   * @return the converter, with no authority prefix — identity already emits {@code ROLE_*}
   */
  @Bean
  JwtAuthenticationConverter jobJwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }

  /**
   * The filter chain: health and info open, everything else authenticated.
   *
   * @param http the builder
   * @param converter the roles-claim converter
   * @return the built chain
   * @throws Exception if the chain cannot be built
   */
  @Bean
  @SuppressWarnings(
      "java:S4502") // Justified: CSRF disabled for a stateless, token-authenticated API.
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info", "/error")
                    .permitAll()
                    // The capability registry's machine surface (ADR-037). "SERVICE", not merely
                    // authenticated: any signed-in user would otherwise be able to enumerate the
                    // job plane's routing table, and authentication is not authorisation.
                    // The authority is the raw claim value because the converter above sets
                    // setAuthoritiesClaimName("roles") with an empty prefix — a prefixed matcher
                    // fails closed against a correct token, and the tempting repair is to weaken
                    // the matcher (ADR-035).
                    .requestMatchers("/internal/v1/**")
                    .hasAuthority("SERVICE")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
    return http.build();
  }
}
