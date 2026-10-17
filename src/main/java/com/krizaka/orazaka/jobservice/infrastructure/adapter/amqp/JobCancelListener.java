package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Hears {@code job.cancel.<jobId>} on the jobs exchange and stops the job (orazaka-web-client#85).
 *
 * <p>The queue is exclusive and auto-delete: every executor instance hears every cancellation, and
 * nothing accumulates while one is down. Only the routing key is read — the id is the key's tail.
 */
@Component
public class JobCancelListener {

  static final String CANCEL_PREFIX = "job.cancel.";

  private static final Logger logger = LoggerFactory.getLogger(JobCancelListener.class);

  private final RunningJobs runningJobs;

  public JobCancelListener(RunningJobs runningJobs) {
    this.runningJobs = Objects.requireNonNull(runningJobs, "RunningJobs cannot be null");
  }

  /**
   * Stops the job a cancel message names.
   *
   * @param message the cancel message; its routing key carries the job id
   */
  @RabbitListener(
      bindings =
          @QueueBinding(
              value = @Queue(exclusive = "true", autoDelete = "true"),
              exchange =
                  @Exchange(
                      name = "orazaka.jobs",
                      type = ExchangeTypes.TOPIC,
                      durable = "true",
                      ignoreDeclarationExceptions = "true"),
              key = CANCEL_PREFIX + "*"))
  public void onCancel(Message message) {
    String jobId = jobIdOf(message.getMessageProperties().getReceivedRoutingKey());
    if (jobId == null) {
      logger.debug("Ignoring a cancel message with no job id");
      return;
    }
    boolean interrupted = runningJobs.cancel(jobId);
    logger.info("Cancel received for job {} (running here: {})", jobId, interrupted);
  }

  static String jobIdOf(String routingKey) {
    if (routingKey == null || !routingKey.startsWith(CANCEL_PREFIX)) {
      return null;
    }
    String id = routingKey.substring(CANCEL_PREFIX.length()).trim();
    return id.isEmpty() ? null : id;
  }
}
