package com.krizaka.orazaka.jobservice.catalog;

import java.util.Optional;

/**
 * Whether a live worker is draining the queue the jobs of a model wait in, and which queue that is.
 *
 * <p>A catalogue row says a model exists and its provider's endpoint says the engine answers, but a video job is
 * routed by a worker: a GPU worker for a self-hosted model, a cheap CPU worker for a model a provider hosts (it only
 * calls the provider's API). The two wait in different queues, so a GPU worker that is off never greys out the hosted
 * model, and a GPU job is never taken by a worker that cannot run it. The worker registry knows who drains what; this
 * port keeps it out of the model-choice logic.
 */
@FunctionalInterface
public interface QueueConsumers {

  /**
   * @param category the model category ({@code video}, {@code image}, …)
   * @param hosted whether the model is served by a provider ({@code true}) or runs on our own hardware
   * @return {@code false} only when the jobs of that kind are run by an external worker and none is alive;
   *     {@code true} when one is, when the job service itself runs them, or when nothing is known (the registry is
   *     advisory: it never switches something off by being silent about it)
   */
  boolean drained(String category, boolean hosted);

  /**
   * @param category the model category
   * @param hosted whether the model is served by a provider
   * @return the routing key the jobs of that kind are published with when an external worker runs them; empty when
   *     the job service runs them itself (or nothing is known)
   */
  default Optional<String> queueOf(String category, boolean hosted) {
    return Optional.empty();
  }
}
