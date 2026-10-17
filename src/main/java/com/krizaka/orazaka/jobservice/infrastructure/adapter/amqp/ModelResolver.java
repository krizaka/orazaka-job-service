package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobservice.catalog.ModelOptionsService;
import com.krizaka.orazaka.jobservice.catalog.ModelResolution;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Resolves the AI model for a job: explicit request → catalog default for the category → fallback.
 *
 * <p>The rules (a typed name must be a model the category offers; the default is used only when its
 * engine answers, else another available model with a notice) live in {@link ModelOptionsService},
 * the same service that feeds the Studio form's picker, so what is offered is what runs.
 */
@Component
class ModelResolver {

  private final ModelOptionsService modelOptions;

  ModelResolver(ModelOptionsService modelOptions) {
    this.modelOptions = Objects.requireNonNull(modelOptions, "ModelOptionsService cannot be null");
  }

  /**
   * @param requestedModel explicit model from the job message (may be null or blank)
   * @param category model category (e.g. "image", "video", "speech")
   * @param fallback model used when neither the request nor the catalog provides one
   * @return the resolved model name
   * @throws JobExecutionException when the request names an unknown model or none is available
   */
  String resolve(String requestedModel, String category, String fallback)
      throws JobExecutionException {
    return resolution(requestedModel, category, fallback).model();
  }

  /**
   * Like {@link #resolve} but keeps the notice that says the default was replaced.
   *
   * @throws JobExecutionException when the request names an unknown model or none is available
   */
  ModelResolution resolution(String requestedModel, String category, String fallback)
      throws JobExecutionException {
    try {
      return modelOptions.resolve(requestedModel, category, fallback);
    } catch (IllegalArgumentException refused) {
      throw new JobExecutionException(refused.getMessage(), refused);
    }
  }
}
