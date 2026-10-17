package com.krizaka.orazaka.jobservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.jobservice.catalog.ModelOption;
import com.krizaka.orazaka.jobservice.catalog.ModelOptionsService;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine surface of the model catalogue: which models may a form offer for a category?
 *
 * <p>The job service owns {@code orazaka_models}, so it answers for it; the Studio service builds
 * its forms from this rather than reading a table it does not own (SEAM-001/002). {@code
 * /internal/v1}: service-to-service, gated on the {@code SERVICE} authority by the security
 * baseline (ADR-035).
 */
@RestController
@RequestMapping("/internal/v1/models")
class ModelOptionsController {

  private final ModelOptionsService modelOptions;

  ModelOptionsController(ModelOptionsService modelOptions) {
    this.modelOptions = Objects.requireNonNull(modelOptions, "ModelOptionsService cannot be null");
  }

  /**
   * The published models of one category, the ones that answer first, the default marked.
   *
   * @param category the model category ({@code image}, {@code video}, {@code speech}, …)
   * @return the options; an empty list for a category with no published model
   */
  @GetMapping("/options")
  List<ModelOption> options(@RequestParam String category) {
    return modelOptions.options(category);
  }
}
