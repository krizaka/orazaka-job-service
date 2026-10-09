package com.krizaka.orazaka.jobservice.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Immutable configuration for asynchronous job execution, mapped from the {@code orazaka.jobs}
 * prefix in {@code application.yml}. Replaces the loose {@code @Value} reads of {@code
 * orazaka.jobs.execution-timeout} across the router (per the typed-properties rule).
 *
 * @param executionTimeout Maximum seconds an async job (or SSE stream) may run before timing out.
 */
@ConfigurationProperties(prefix = "orazaka.jobs")
public record JobsProperties(int executionTimeout) {
  public JobsProperties {
    if (executionTimeout <= 0) {
      throw new IllegalArgumentException("orazaka.jobs.execution-timeout must be positive");
    }
  }
}
