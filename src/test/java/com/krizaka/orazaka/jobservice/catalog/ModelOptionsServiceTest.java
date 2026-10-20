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
    return new ModelOptionsService(catalog, up::contains, (c, h) -> true);
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
    ModelOptionsService svc = new ModelOptionsService(catalog, name -> true, (c, h) -> true);

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
    ModelOption hosted = options.getFirst();
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

  // ── A model is runnable when its engine answers AND someone drains the queue its jobs wait in
  // ──────────
  // (dev, 2026-10-20: self-hosted GPU models were offered while no GPU worker was up, so a run
  // waited for « a machine »
  // for ever; the hosted Wan is made by the provider but still routed by the media worker.)

  private static final CatalogModelDto GPU_VIDEO =
      new CatalogModelDto(
          10,
          "wan2.2-t2v-a14b",
          "Wan 2.2 T2V A14B (720p, GPU)",
          "video",
          null,
          false,
          "localai-video",
          40,
          16,
          null,
          "GPU.",
          "local",
          "available",
          null,
          null);
  private static final CatalogModelDto HOSTED_VIDEO =
      new CatalogModelDto(
          11,
          "wan2-2-t2v-a14b",
          "Wan 2.2 T2V A14B (serverless)",
          "video",
          null,
          true,
          "do-inference",
          0,
          16,
          null,
          "Hosted.",
          "provider",
          "available",
          null,
          60);

  private ModelOptionsService video(
      Set<String> up, java.util.function.BiPredicate<String, Boolean> drained) {
    when(catalog.getModelsByCategory("video")).thenReturn(List.of(GPU_VIDEO, HOSTED_VIDEO));
    return new ModelOptionsService(catalog, up::contains, drained::test);
  }

  @Test
  @DisplayName(
      "No worker drains the video queue: every video model is greyed out, each with its own reason code")
  void noWorkerMeansNoRunnableVideoModel() {
    List<ModelOption> options =
        video(Set.of("localai-video", "do-inference"), (c, h) -> false).options("video");

    assertThat(options).allMatch(option -> !option.available());
    assertThat(options)
        .extracting(ModelOption::value, ModelOption::unavailableCode)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("wan2.2-t2v-a14b", "gpu_offline"),
            org.assertj.core.groups.Tuple.tuple("wan2-2-t2v-a14b", "worker_offline"));
    assertThat(options).allMatch(option -> option.unavailableReason() != null);
  }

  @Test
  @DisplayName(
      "A worker is up but the GPU's engine is not: the GPU model is greyed out, the hosted one runs and is the default")
  void gpuEngineOffHostedRunsAndIsTheDefault() {
    List<ModelOption> options = video(Set.of("do-inference"), (c, h) -> true).options("video");

    ModelOption hosted = options.getFirst();
    assertThat(hosted.value()).isEqualTo("wan2-2-t2v-a14b");
    assertThat(hosted.available()).isTrue();
    assertThat(hosted.unavailableCode()).isNull();
    assertThat(hosted.isDefault()).isTrue();
    ModelOption gpu = options.get(1);
    assertThat(gpu.available()).isFalse();
    assertThat(gpu.unavailableCode()).isEqualTo("gpu_offline");
  }

  @Test
  @DisplayName(
      "A hosted model whose provider does not answer says so, not that a worker is missing")
  void hostedProviderDown() {
    ModelOption hosted =
        video(Set.of("localai-video"), (c, h) -> true).options("video").stream()
            .filter(option -> "wan2-2-t2v-a14b".equals(option.value()))
            .findFirst()
            .orElseThrow();

    assertThat(hosted.unavailableCode()).isEqualTo("provider_down");
  }

  @Test
  @DisplayName(
      "Submitting a GPU model while no worker runs the queue is refused by name, not left to wait for a machine")
  void unrunnableNamedModelIsRefused() {
    ModelOptionsService none = video(Set.of("localai-video", "do-inference"), (c, h) -> false);

    assertThatThrownBy(() -> none.resolve(null, "video", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No video model is available");
  }

  @Test
  @DisplayName(
      "With the GPU worker up, both video models are runnable and the declared default keeps its place")
  void everythingUp() {
    List<ModelOption> options =
        video(Set.of("localai-video", "do-inference"), (c, h) -> true).options("video");

    assertThat(options).allMatch(ModelOption::available);
    assertThat(options.stream().filter(ModelOption::isDefault).map(ModelOption::value))
        .containsExactly("wan2-2-t2v-a14b");
  }

  @Test
  @DisplayName(
      "Hosted and self-hosted models wait in different queues: a GPU worker offline greys the GPU models, not the hosted Wan")
  void hostedAndGpuQueuesAreIndependent() {
    // a hosted-video worker is alive (the cheap CPU consumer), no GPU worker
    List<ModelOption> options =
        video(Set.of("localai-video", "do-inference"), (c, h) -> h).options("video");

    ModelOption hosted = options.getFirst();
    assertThat(hosted.value()).isEqualTo("wan2-2-t2v-a14b");
    assertThat(hosted.available()).isTrue();
    assertThat(hosted.isDefault()).isTrue();
    ModelOption gpu = options.get(1);
    assertThat(gpu.available()).isFalse();
    assertThat(gpu.unavailableCode()).isEqualTo("gpu_offline");
  }

  @Test
  @DisplayName(
      "A hosted model is published on the hosted queue: the routing key comes from the data, null for a model the job service runs itself")
  void queueOfAModel() {
    when(catalog.getAllModels()).thenReturn(List.of(GPU_VIDEO, HOSTED_VIDEO, FLUX));
    QueueConsumers consumers =
        new QueueConsumers() {
          @Override
          public boolean drained(String category, boolean hosted) {
            return true;
          }

          @Override
          public java.util.Optional<String> queueOf(String category, boolean hosted) {
            if (!"video".equals(category)) {
              return java.util.Optional.empty();
            }
            return java.util.Optional.of(
                hosted ? "job.hostedvideo.generate" : "job.video.generate");
          }
        };
    ModelOptionsService service = new ModelOptionsService(catalog, name -> true, consumers);

    assertThat(service.queueOf("wan2-2-t2v-a14b")).contains("job.hostedvideo.generate");
    assertThat(service.queueOf("WAN2.2-T2V-A14B")).contains("job.video.generate");
    assertThat(service.queueOf("flux-2-klein-4b")).isEmpty();
    assertThat(service.queueOf("nope")).isEmpty();
    assertThat(service.queueOf(null)).isEmpty();
  }
}
