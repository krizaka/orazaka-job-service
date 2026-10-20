package com.krizaka.orazaka.jobservice.catalog;

/**
 * One model a person may pick for a step, with everything a picker shows beside its name.
 *
 * @param value the id sent back as the field's value ({@code orazaka_models.model_name})
 * @param label the name a person reads
 * @param provider the provider that serves it ({@code do-inference-images}, {@code localai-image})
 * @param runsOn {@code local} (the operator's hardware) or {@code cloud} (a hosted API)
 * @param speed {@code fast}, {@code balanced} or {@code slow}, derived from the measured time; null
 *     while unmeasured
 * @param typicalSeconds typical seconds for one run on this setup; null while unmeasured
 * @param priceCents what one run costs in US cents; null when it is free (own hardware)
 * @param hint the catalogue's one-sentence description
 * @param available whether the engine answers right now
 * @param unavailableReason why not, in a sentence a person can act on; null when available
 * @param unavailableCode why not, as a code a client words in the page's language ({@code
 *     gpu_offline}: a self-hosted model and no live GPU worker or engine; {@code worker_offline}: a
 *     hosted model but no worker routing its jobs; {@code provider_down}: the hosted provider does
 *     not answer); null when available
 * @param isDefault whether this is the option used when the person chooses nothing
 */
public record ModelOption(
    String value,
    String label,
    String provider,
    String runsOn,
    String speed,
    Integer typicalSeconds,
    Integer priceCents,
    String hint,
    boolean available,
    String unavailableReason,
    String unavailableCode,
    boolean isDefault) {

  /** At or under this many seconds a model reads as fast. */
  static final int FAST_SECONDS = 20;

  /** At or under this many seconds (and over {@link #FAST_SECONDS}) it reads as balanced. */
  static final int BALANCED_SECONDS = 90;

  /**
   * @param typicalSeconds the measured time, or null
   * @return its speed class, or null when nothing was measured
   */
  static String speedOf(Integer typicalSeconds) {
    if (typicalSeconds == null) {
      return null;
    }
    if (typicalSeconds <= FAST_SECONDS) {
      return "fast";
    }
    return typicalSeconds <= BALANCED_SECONDS ? "balanced" : "slow";
  }
}
