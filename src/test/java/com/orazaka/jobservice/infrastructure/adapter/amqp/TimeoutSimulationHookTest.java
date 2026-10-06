package com.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;

import com.orazaka.jobs.domain.model.JobCommand;
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
}
