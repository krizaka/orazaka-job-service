package com.krizaka.orazaka.jobservice.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.persistence.domain.model.CatalogModelDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dev-like seed of krizaka/orazaka#203: a local default whose engine is off, a hosted model.
 */
class ModelOptionsServiceTest {

  private static final CatalogModelDto FLUX =
      new CatalogModelDto(
          1,
          "flux-2-klein-4b",
          "FLUX.2 [klein] 4B",
          "image",
          null,
          true,
          "localai-image",
          4,
          null,
          null,
          "Local default.",
          "local",
          "available",
          62,
          null);
  private static final CatalogModelDto SD35 =
      new CatalogModelDto(
          2,
          "stable-diffusion-3.5-large",
          "Stable Diffusion 3.5 Large",
          "image",
          null,
          false,
          "do-inference-images",
          null,
          null,
          null,
          "Hosted.",
          "provider",
          "available",
          8,
          8);
  private static final CatalogModelDto PLANNED =
      new CatalogModelDto(
          3,
          "planned-model",
          "Planned",
          "image",
          null,
          false,
          "localai-image",
          null,
          null,
          null,
          null,
          "local",
          "planned",
          null,
          null);

  private final CatalogModelManager catalog = mock(CatalogModelManager.class);

  private ModelOptionsService service(Set<String> up) {
    when(catalog.getModelsByCategory("image")).thenReturn(List.of(FLUX, SD35, PLANNED));
    return new ModelOptionsService(catalog, up::contains);
  }

  @Test
  @DisplayName("#203: a default whose engine is off resolves to an available model, with a notice")
  void defaultDownFallsBackToAnAvailableModel() {
    ModelResolution resolved = service(Set.of("do-inference-images")).resolve(null, "image", "x");

    assertThat(resolved.model()).isEqualTo("stable-diffusion-3.5-large");
    assertThat(resolved.notice()).contains("FLUX.2 [klein] 4B").contains("Stable Diffusion 3.5");
  }

  @Test
  @DisplayName("The default is used, without a notice, when its engine answers")
  void defaultUpIsUsed() {
    ModelResolution resolved =
        service(Set.of("localai-image", "do-inference-images")).resolve("", "image", "x");

    assertThat(resolved).isEqualTo(ModelResolution.of("flux-2-klein-4b"));
  }

  @Test
  @DisplayName("Nothing answering is refused by name, not left to fail inside an engine")
  void nothingAvailableIsRefusedWithAClearMessage() {
    ModelOptionsService none = service(Set.of());

    assertThatThrownBy(() -> none.resolve(null, "image", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No image model is available")
        .hasMessageContaining("FLUX.2 [klein] 4B");
  }

  @Test
  @DisplayName("A typed model that the category does not offer is refused with the list")
  void unknownTypedModelIsRefused() {
    ModelOptionsService svc = service(Set.of("localai-image"));

    assertThatThrownBy(() -> svc.resolve("stable-diffusion-9", "image", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown image model 'stable-diffusion-9'")
        .hasMessageContaining("flux-2-klein-4b");
  }

  @Test
  @DisplayName("A planned (unpublished) model cannot be named")
  void plannedModelIsNotOffered() {
    ModelOptionsService svc = service(Set.of("localai-image"));

    assertThatThrownBy(() -> svc.resolve("planned-model", "image", "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("A named model keeps its catalogue spelling")
  void namedModelIsMatchedCaseInsensitively() {
    assertThat(service(Set.of()).resolve("STABLE-DIFFUSION-3.5-LARGE", "image", "x").model())
        .isEqualTo("stable-diffusion-3.5-large");
  }

  @Test
  @DisplayName("A category the catalogue knows nothing about keeps the caller's fallback")
  void emptyCategoryUsesTheFallback() {
    when(catalog.getModelsByCategory("audio")).thenReturn(List.of());
    ModelOptionsService svc = new ModelOptionsService(catalog, name -> true);

    assertThat(svc.resolve(null, "audio", "whisper-base").model()).isEqualTo("whisper-base");
    assertThat(svc.resolve("my-model", "audio", "whisper-base").model()).isEqualTo("my-model");
  }

  @Test
  @DisplayName("Options list published models only, available first, default on the one that runs")
  void optionsCarryWhatAPickerShows() {
    List<ModelOption> options = service(Set.of("do-inference-images")).options("image");

    assertThat(options)
        .extracting(ModelOption::value)
        .containsExactly("stable-diffusion-3.5-large", "flux-2-klein-4b");
    ModelOption hosted = options.get(0);
    assertThat(hosted.runsOn()).isEqualTo("cloud");
    assertThat(hosted.speed()).isEqualTo("fast");
    assertThat(hosted.typicalSeconds()).isEqualTo(8);
    assertThat(hosted.priceCents()).isEqualTo(8);
    assertThat(hosted.available()).isTrue();
    assertThat(hosted.isDefault()).as("the default that will run").isTrue();
    ModelOption local = options.get(1);
    assertThat(local.runsOn()).isEqualTo("local");
    assertThat(local.speed()).isEqualTo("balanced");
    assertThat(local.priceCents()).isNull();
    assertThat(local.available()).isFalse();
    assertThat(local.unavailableReason()).contains("FLUX.2 [klein] 4B");
    assertThat(local.isDefault()).isFalse();
  }

  @Test
  @DisplayName("Speed classes follow the measured time")
  void speedClasses() {
    assertThat(ModelOption.speedOf(null)).isNull();
    assertThat(ModelOption.speedOf(20)).isEqualTo("fast");
    assertThat(ModelOption.speedOf(21)).isEqualTo("balanced");
    assertThat(ModelOption.speedOf(90)).isEqualTo("balanced");
    assertThat(ModelOption.speedOf(157)).isEqualTo("slow");
  }
}
