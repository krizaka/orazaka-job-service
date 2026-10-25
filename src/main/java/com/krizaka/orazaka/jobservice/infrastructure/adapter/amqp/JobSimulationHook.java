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

  /**
   * The timeout a job runs under. Production keeps the configured one; only a test simulation may
   * shorten it (a prompt is user input and never chooses its own timeout).
   *
   * @param message The incoming job message.
   * @param configuredSeconds The configured execution timeout.
   * @return The effective timeout, in seconds.
   */
  default int timeoutSeconds(JobCommand message, int configuredSeconds) {
    return configuredSeconds;
  }

  /** No-op implementation for production use. */
  JobSimulationHook NOOP = (message, timeoutSeconds) -> {};
}
