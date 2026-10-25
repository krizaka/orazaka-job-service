package com.krizaka.orazaka.jobservice.infrastructure.config;

import com.krizaka.orazaka.core.domain.ports.outbound.CheckSubjectPepper;
import com.krizaka.orazaka.core.infrastructure.checks.MockConnector;
import com.krizaka.orazaka.jobservice.infrastructure.adapter.checks.ExternalCheckStrategy;
import com.krizaka.orazaka.jobservice.infrastructure.adapter.checks.MockProviderKeyStore;
import com.krizaka.orazaka.jobservice.infrastructure.adapter.persistence.ExternalCheckAuditStore;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The external checks' executor (orazaka-packs#138), on the mock connector until the pilot's go.
 *
 * <p>Its collaborators are built here rather than published as beans: the key store and the
 * connector are the mock's, and a port bean of the same type contributed later by the engine (the
 * sealed keys, a real connector) must replace them by a decision in this class, not collide with
 * them in the context.
 *
 * <p>The pepper is the one collaborator read from the engine: the {@link CheckSubjectPepper} port,
 * over the sealed setting {@code checks.subject-pepper}, when a bean provides it. It is looked up
 * at each check, so a pepper sealed after startup takes effect at once; none, and the engine
 * refuses every check.
 */
@Configuration
public class ExternalCheckConfiguration {

  /**
   * @param jdbcTemplate this service's database, for the audit
   * @param sealedPepper the engine's subject pepper, when one is provided
   * @return the executor of handler {@code check.external}
   */
  @Bean
  public ExternalCheckStrategy externalCheckStrategy(
      JdbcTemplate jdbcTemplate, ObjectProvider<CheckSubjectPepper> sealedPepper) {
    CheckSubjectPepper pepper =
        () ->
            Optional.ofNullable(sealedPepper.getIfAvailable()).flatMap(CheckSubjectPepper::pepper);
    return new ExternalCheckStrategy(
        new MockConnector(),
        new MockProviderKeyStore(),
        new ExternalCheckAuditStore(jdbcTemplate),
        pepper,
        Clock.systemUTC());
  }
}
