package com.krizaka.orazaka.jobservice.infrastructure.config;

import com.krizaka.orazaka.jobservice.infrastructure.adapter.research.GroundedResearchStrategy;
import com.krizaka.orazaka.tools.application.service.GroundedResearchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the executor of the handler {@code research.grounded} (ADR-087 slice 5). It is always
 * registered, so the capability seeded in {@code 30-jobs-config.sql} has its executor and the
 * coherence rules hold; it can only answer while the engine's service exists, which is while {@code
 * orazaka.law.grounded.enabled=true} (and then still only while the settings {@code
 * research.enabled}, {@code law.corpus.enabled} and the {@code law-corpus} source are on).
 */
@Configuration
public class GroundedResearchConfiguration {

  @Bean
  public GroundedResearchStrategy groundedResearchStrategy(
      ObjectProvider<GroundedResearchService> engine) {
    return new GroundedResearchStrategy(engine::getIfAvailable);
  }
}
