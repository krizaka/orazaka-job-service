package com.krizaka.orazaka.jobservice.catalog;

/**
 * The model a step will really run on.
 *
 * @param model the {@code orazaka_models.model_name} to run
 * @param notice a sentence to show the person when the catalogue default could not be used and
 *     another model took its place; null when the model is the one asked for or the default
 */
public record ModelResolution(String model, String notice) {

  /** A resolution with nothing to tell. */
  public static ModelResolution of(String model) {
    return new ModelResolution(model, null);
  }
}
