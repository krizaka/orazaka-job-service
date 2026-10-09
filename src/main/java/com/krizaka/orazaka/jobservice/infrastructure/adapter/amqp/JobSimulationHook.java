package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.jobs.domain.model.JobCommand;

/**
 * Hook interface for injecting test simulation behaviour into the job execution lifecycle.
 *
 * <p>The default (production) implementation is a no-op. A test-profile implementation can inject
 * artificial delays or failures for integration testing purposes.
 *
 * @see JobListener
 */
interface JobSimulationHook {

  /**
   * Invoked before strategy dispatch, allowing test simulations to inject delays.
   *
   * @param message The incoming job message.
   * @param timeoutSeconds The effective timeout for this job.
   */
  void beforeExecution(JobCommand message, int timeoutSeconds);

  /** No-op implementation for production use. */
  JobSimulationHook NOOP = (message, timeoutSeconds) -> {};
}
