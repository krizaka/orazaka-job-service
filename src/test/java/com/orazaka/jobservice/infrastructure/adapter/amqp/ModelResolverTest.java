package com.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.orazaka.persistence.domain.model.CatalogModelDto;
import com.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
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
    resolver = new ModelResolver(catalogModelManager);
  }

  @Test
  void resolve_explicitRequest_winsOverCatalogAndFallback() {
    assertEquals("my-model", resolver.resolve("my-model", "video", "fallback"));
  }

  @Test
  void resolve_blankRequest_usesCatalogDefault() {
    when(catalogModelManager.getDefaultModelByCategory("video"))
        .thenReturn(
            Optional.of(
                new CatalogModelDto(1, "catalog-model", "catalog-model", "video", null, true)));
    assertEquals("catalog-model", resolver.resolve("", "video", "fallback"));
  }

  @Test
  void resolve_noRequestNoCatalog_usesFallback() {
    when(catalogModelManager.getDefaultModelByCategory("video")).thenReturn(Optional.empty());
    assertEquals("fallback", resolver.resolve(null, "video", "fallback"));
  }
}
