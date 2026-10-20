package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobservice.catalog.ModelOptionsService;
import com.krizaka.orazaka.persistence.domain.model.CatalogModelDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelResolverTest {

  @Mock private CatalogModelManager catalogModelManager;
  private ModelResolver resolver;

  @BeforeEach
  void setUp() {
    resolver =
        new ModelResolver(
            new ModelOptionsService(catalogModelManager, provider -> true, category -> true));
  }

  @Test
  void resolve_explicitRequest_winsOverCatalogAndFallback() throws Exception {
    assertEquals("my-model", resolver.resolve("my-model", "video", "fallback"));
  }

  @Test
  void resolve_blankRequest_usesCatalogDefault() throws Exception {
    CatalogModelDto catalog =
        new CatalogModelDto(1, "catalog-model", "catalog-model", "video", null, true);
    when(catalogModelManager.getModelsByCategory("video")).thenReturn(List.of(catalog));
    assertEquals("catalog-model", resolver.resolve("", "video", "fallback"));
  }

  @Test
  void resolve_noRequestNoCatalog_usesFallback() throws Exception {
    when(catalogModelManager.getDefaultModelByCategory("video")).thenReturn(Optional.empty());
    assertEquals("fallback", resolver.resolve(null, "video", "fallback"));
  }

  @Test
  void resolve_unknownModel_isAJobFailureNamingTheChoices() {
    CatalogModelDto catalog =
        new CatalogModelDto(1, "catalog-model", "catalog-model", "video", null, true);
    when(catalogModelManager.getModelsByCategory("video")).thenReturn(List.of(catalog));
    JobExecutionException refused =
        assertThrows(
            JobExecutionException.class, () -> resolver.resolve("typo", "video", "fallback"));
    assertEquals("Unknown video model 'typo'. Choose one of: catalog-model.", refused.getMessage());
  }
}
