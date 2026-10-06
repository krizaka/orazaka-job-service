package com.orazaka.jobservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Job Orchestration service (Phase 5): the async COMMAND executor. Consumes {@code
 * orazaka.jobs.interactive}/{@code orazaka.jobs.batch}, runs the cognitive pipeline in-process
 * through the bundled {@code core}/{@code business}/{@code interceptors} libraries, writes the
 * {@code orazaka_jobs} lifecycle, and emits {@code job.{id}.done|error} for the SSE relay.
 *
 * <p>Broad {@code com.orazaka} scan (like the former router) so the bundled library beans register;
 * the router's own controllers are not on this classpath, so no HTTP surface leaks in. Test-only
 * {@code *TestApplication} bootstrap classes are excluded (they would re-register JPA
 * repositories).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    basePackages = "com.orazaka",
    excludeFilters = {
      @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
      @ComponentScan.Filter(
          type = FilterType.CUSTOM,
          classes = AutoConfigurationExcludeFilter.class),
      @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.orazaka\\..*TestApplication")
    })
@ConfigurationPropertiesScan("com.orazaka")
// The worker-registry sweeper is what stops a dead worker reading as HEALTHY for ever
// (ADR-038); without scheduling it would be a bean that never runs.
@EnableScheduling
public class JobServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(JobServiceApplication.class, args);
  }
}
