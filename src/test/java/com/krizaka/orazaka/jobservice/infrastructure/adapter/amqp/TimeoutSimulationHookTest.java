package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;

import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimeoutSimulationHookTest {

  private final TimeoutSimulationHook hook = new TimeoutSimulationHook();

  @Test
  void beforeExecution_noPrompt_doesNothing() {
    var message = new JobCommand("job-123", "user-1", "feature", Map.of());
    assertDoesNotThrow(() -> hook.beforeExecution(message, 10));
  }

  @Test
  void beforeExecution_promptDoesNotStartWithTimeout_doesNothing() {
    var message = new JobCommand("job-123", "user-1", "feature", Map.of("prompt", "HELLO"));
    assertDoesNotThrow(() -> hook.beforeExecution(message, 10));
  }

  @Test
  void beforeExecution_textDoesNotStartWithTimeout_doesNothing() {
    var message = new JobCommand("job-123", "user-1", "feature", Map.of("text", "HELLO"));
    assertDoesNotThrow(() -> hook.beforeExecution(message, 10));
  }

  @Test
  void beforeExecution_promptStartsWithTimeout_sleepsZero_whenTimeoutMinusTen() {
    var message = new JobCommand("job-123", "user-1", "feature", Map.of("prompt", "TIMEOUT NOW"));
    assertDoesNotThrow(() -> hook.beforeExecution(message, -10));
  }

  @Test
  void beforeExecution_textStartsWithTimeout_sleepsZero_whenTimeoutMinusTen() {
    var message = new JobCommand("job-123", "user-1", "feature", Map.of("text", "TIMEOUT NOW"));
    assertDoesNotThrow(() -> hook.beforeExecution(message, -10));
  }

  @Test
  void beforeExecution_interrupted_handlesCleanally() throws InterruptedException {
    var message = new JobCommand("job-123", "user-1", "feature", Map.of("prompt", "TIMEOUT NOW"));

    Thread t = new Thread(() -> hook.beforeExecution(message, 5));
    t.start();

    // Wait for the thread to enter TIMED_WAITING state without Thread.sleep
    while (t.getState() != Thread.State.TIMED_WAITING && t.isAlive()) {
      Thread.yield();
    }

    t.interrupt();
    t.join(2000);

    assertFalse(t.isAlive());
  }

  @Test
  void timeoutSeconds_simulationShortensATimeoutPrompt_only() {
    var timeout = new JobCommand("job-1", "user-1", "feature", Map.of("prompt", "TIMEOUT NOW"));
    var normal = new JobCommand("job-2", "user-1", "feature", Map.of("prompt", "hello"));
    org.junit.jupiter.api.Assertions.assertEquals(
        TimeoutSimulationHook.SIMULATED_TIMEOUT_SECONDS, hook.timeoutSeconds(timeout, 300));
    org.junit.jupiter.api.Assertions.assertEquals(300, hook.timeoutSeconds(normal, 300));
  }

  @Test
  void timeoutSeconds_productionNeverLetsAPromptChooseItsTimeout() {
    // The external audit's finding (krizaka-blog#132): without the simulations switch, « TIMEOUT… »
    // keeps the configured timeout.
    var timeout = new JobCommand("job-1", "user-1", "feature", Map.of("prompt", "TIMEOUT NOW"));
    org.junit.jupiter.api.Assertions.assertEquals(
        300, JobSimulationHook.NOOP.timeoutSeconds(timeout, 300));
  }
}
