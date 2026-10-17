package com.krizaka.orazaka.jobservice.catalog;

/**
 * Whether the engine behind a model's provider answers right now.
 *
 * <p>A catalogue row says a model exists; only the provider's endpoint says it can run it today (a
 * local diffusion server that is switched off, a GPU host that is stopped between batches). The
 * port keeps the network out of the model-choice logic so that logic is a plain function of the
 * catalogue and of this answer.
 */
public interface ProviderReachability {

  /**
   * @param providerName the {@code ai_providers.name} of the model's provider
   * @return {@code true} when the provider's endpoint answered (any HTTP status), {@code false}
   *     when it is unknown, has no base URL, or did not answer in time
   */
  boolean reachable(String providerName);
}
