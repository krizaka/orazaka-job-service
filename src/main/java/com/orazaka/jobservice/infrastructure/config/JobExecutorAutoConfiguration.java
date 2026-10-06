package com.orazaka.jobservice.infrastructure.config;

import com.orazaka.jobs.domain.port.JobExecutor;
import com.orazaka.jobservice.application.service.ExecutorCoherenceService;
import com.orazaka.persistence.domain.ports.inbound.CapabilityManager;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The job plane's executor SPI seam.
 *
 * <p>Registered through {@code META-INF/spring/…AutoConfiguration.imports}, exactly as {@code
 * orazaka-interceptors} registers its pipeline [ERR-122]. That file is the extension point: a jar
 * dropped on the classpath carrying its own imports file contributes a {@link JobExecutor} with no
 * edit to this class, this service, or this repository — which is the acceptance criterion that
 * un-sealed the SPI (ADR-038).
 *
 * <p><b>Why the six in-repo executors are not declared here.</b> Two of them depend on {@code
 * ModelResolver}, which is package-private in the adapter package. Declaring them from this
 * configuration would mean widening an internal type to public purely to satisfy a wiring class —
 * the encapsulation leak [ERR-110] exists to prevent. They stay component-scanned; Spring collects
 * every {@code JobExecutor} bean into one list regardless of how it arrived, so an out-of-tree
 * executor and an in-repo one are indistinguishable to {@code JobListener}. §4.1 of the phase
 * workflow is explicit that the criterion is out-of-tree discovery, "not the mechanism".
 *
 * <p>Deliberately not a {@code ServiceLoader} and not a classpath scan: Spring already owns the
 * lifecycle and the injection these executors need, and a second discovery mechanism beside the one
 * the codebase already uses is one more thing to learn and to get wrong.
 */
@AutoConfiguration
public class JobExecutorAutoConfiguration {

  /**
   * Checks at startup that every enabled capability has an executor to run it.
   *
   * <p>The runtime half of the two-discriminant guard (ADR-038): {@code routing_key} decides which
   * process receives a job, {@code handler_key} decides which code path runs it, and a capability
   * can be misconfigured in either. The build-time rule catches the seeded rows; this catches the
   * rows an operator added, and a jar that failed to load.
   *
   * @param executors every registered executor, in-repo and out-of-tree alike
   * @param capabilityManager the capability registry
   * @return the startup coherence check
   */
  @Bean
  ExecutorCoherenceService executorCoherenceService(
      List<JobExecutor> executors, CapabilityManager capabilityManager) {
    return new ExecutorCoherenceService(executors, capabilityManager);
  }
}
