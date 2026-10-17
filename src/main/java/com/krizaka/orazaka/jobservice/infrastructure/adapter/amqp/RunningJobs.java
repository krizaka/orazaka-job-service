package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The jobs this executor is running, and the ones its owner cancelled (orazaka-web-client#85).
 *
 * <p>A cancel can reach the executor <b>before</b> the job does (it waits in a lane behind others),
 * so a cancellation is remembered for {@link #TTL} and checked again when the message arrives. For
 * a job that is running, the thread executing it is interrupted: the blocking call it sits in (an
 * HTTP call to the image engine, a provider request) ends, the job's own handler sees the
 * cancellation and reports {@code CANCELLED}, never a failure.
 */
@Component
public class RunningJobs {

  /** How long a cancellation is remembered: longer than any job can wait in a lane. */
  public static final Duration TTL = Duration.ofHours(6);

  private static final Logger logger = LoggerFactory.getLogger(RunningJobs.class);

  private final Map<String, Thread> running = new ConcurrentHashMap<>();
  private final Map<String, Instant> cancelled = new ConcurrentHashMap<>();

  /**
   * Records that the calling thread is executing the job.
   *
   * @param jobId the job
   */
  public void bind(String jobId) {
    running.put(jobId, Thread.currentThread());
  }

  /**
   * Releases the calling thread from the job and clears a stray interrupt, so a pooled thread is
   * not handed to the next job already interrupted.
   *
   * @param jobId the job
   */
  public void unbind(String jobId) {
    running.remove(jobId);
    // Clears the flag on purpose: the interrupt was meant for this job and has been answered.
    Thread.interrupted();
  }

  /**
   * Marks the job cancelled and interrupts the thread running it, if it runs here.
   *
   * @param jobId the job
   * @return whether a running job was interrupted
   */
  public boolean cancel(String jobId) {
    return cancel(jobId, Instant.now());
  }

  boolean cancel(String jobId, Instant now) {
    cancelled.values().removeIf(at -> at.plus(TTL).isBefore(now));
    cancelled.put(jobId, now);
    Thread thread = running.get(jobId);
    if (thread == null) {
      return false;
    }
    logger.info("Cancelling job {}: interrupting its executor", jobId);
    thread.interrupt();
    return true;
  }

  /**
   * Whether the job's owner cancelled it.
   *
   * @param jobId the job
   * @return {@code true} once a cancel was received for it
   */
  public boolean isCancelled(String jobId) {
    return jobId != null && cancelled.containsKey(jobId);
  }
}
