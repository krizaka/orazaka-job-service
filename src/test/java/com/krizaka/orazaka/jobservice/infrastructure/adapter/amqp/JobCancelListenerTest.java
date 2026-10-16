package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class JobCancelListenerTest {

  private static Message cancelOf(String routingKey) {
    MessageProperties properties = new MessageProperties();
    properties.setReceivedRoutingKey(routingKey);
    return new Message(new byte[0], properties);
  }

  @Test
  void aCancelMessageStopsTheJobNamedInItsRoutingKey() {
    RunningJobs running = new RunningJobs();

    new JobCancelListener(running).onCancel(cancelOf("job.cancel.abc-123"));

    assertTrue(running.isCancelled("abc-123"));
  }

  @Test
  void anyOtherKeyIsIgnored() {
    RunningJobs running = new RunningJobs();

    new JobCancelListener(running).onCancel(cancelOf("job.video.generate"));
    new JobCancelListener(running).onCancel(cancelOf("job.cancel."));

    assertFalse(running.isCancelled("generate"));
  }

  @Test
  void theIdIsTheWholeTail() {
    assertEquals("abc", JobCancelListener.jobIdOf("job.cancel.abc"));
    assertNull(JobCancelListener.jobIdOf("job.cancel."));
    assertNull(JobCancelListener.jobIdOf(null));
  }
}
