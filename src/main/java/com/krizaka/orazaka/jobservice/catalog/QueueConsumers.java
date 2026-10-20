package com.krizaka.orazaka.jobservice.catalog;

/**
 * Whether a live worker is draining the queue the jobs of a model category wait in.
 *
 * <p>A catalogue row says a model exists and its provider's endpoint says the engine answers, but a
 * video job is routed by a worker (the media worker, on a GPU or the Mac): with none up, the job
 * waits for « a machine » for ever (dev, 2026-10-20). The worker registry knows who drains what;
 * this port keeps it out of the model-choice logic.
 */
public interface QueueConsumers {

  /**
   * @param category the model category ({@code video}, {@code image}, …)
   * @return {@code false} only when the category's jobs are run by an external worker and none is
   *     alive; {@code true} when one is, when the job service itself runs the category, or when
   *     nothing is known (the registry is advisory: it never switches something off by being silent
   *     about it)
   */
  boolean drained(String category);
}
