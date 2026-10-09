package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.persistence.domain.model.CatalogModelDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Resolves the AI model for a job: explicit request → catalog default for the category → fallback.
 *
 * <p>Replaces the former static {@code JobStrategyHelper.resolveModel(..., CatalogModelManager)}:
 * the catalog dependency is injected, never passed as a method parameter [ERR-127].
 */
@Component
class ModelResolver {

  private final CatalogModelManager catalogModelManager;

  ModelResolver(CatalogModelManager catalogModelManager) {
    this.catalogModelManager =
        Objects.requireNonNull(catalogModelManager, "CatalogModelManager cannot be null");
  }

  /**
   * @param requestedModel explicit model from the job message (may be null or blank)
   * @param category model category (e.g. "image", "video", "speech")
   * @param fallback model used when neither the request nor the catalog provides one
   * @return the resolved model name
   */
  String resolve(String requestedModel, String category, String fallback) {
    if (requestedModel != null && !requestedModel.isBlank()) {
      return requestedModel;
    }
    return catalogModelManager
        .getDefaultModelByCategory(category)
        .map(CatalogModelDto::modelName)
        .orElse(fallback);
  }
}
