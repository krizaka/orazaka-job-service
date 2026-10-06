package com.orazaka.jobservice.infrastructure.adapter.amqp;

import com.orazaka.jobs.domain.model.JobCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Test-only simulation hook that injects an artificial delay when a job prompt starts with
 * "TIMEOUT". Only activated when {@code orazaka.jobs.enable-test-simulations=true}.
 *
 * <p>This class isolates {@link Thread#sleep} from production code to satisfy SonarQube DoS
 * security hotspot rules (squid:S2925).
 *
 * @see JobSimulationHook
 */
@Component
@ConditionalOnProperty(name = "orazaka.jobs.enable-test-simulations", havingValue = "true")
class TimeoutSimulationHook implements JobSimulationHook {

  private static final Logger logger = LoggerFactory.getLogger(TimeoutSimulationHook.class);

  @Override
  public void beforeExecution(JobCommand message, int timeoutSeconds) {
    String prompt = message.prompt();
    if (prompt != null && prompt.startsWith("TIMEOUT")) {
      try {
        logger.info("Simulating hanging task timeout for job: {}", message.jobId());
        Thread.sleep((timeoutSeconds + 10) * 1000L); // NOSONAR — intentional test simulation
      } catch (InterruptedException ie) {
        logger.info("Simulated timeout sleep interrupted.");
        Thread.currentThread().interrupt();
      }
    }
  }
}
