package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.core.domain.model.image.ImageDirection;
import com.krizaka.orazaka.core.domain.model.image.ImageRequest;
import com.krizaka.orazaka.core.domain.model.image.ImageResponse;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobservice.catalog.ModelResolution;
import com.krizaka.orazaka.jobservice.infrastructure.support.MediaFileStore;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImageGenerationStrategyTest {

  @TempDir Path tempDir;

  @Mock private AiClient aiClient;
  @Mock private ModelResolver modelResolver;

  private ImageGenerationStrategy strategy;
  private MediaFileStore store;
  private com.krizaka.orazaka.assets.application.service.EncryptedAssetService assets;

  @BeforeEach
  void setUp() throws Exception {
    assets = testAssets();
    store =
        new MediaFileStore(
            assets,
            new com.krizaka.orazaka.assets.infrastructure.storage.NoDurableAssetStore(),
            new com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties(
                "local", null, null, "storage.bunnycdn.com", null, "default"));
    strategy =
        new ImageGenerationStrategy(
            aiClient,
            modelResolver,
            store,
            assets,
            VisionAnalysisStrategyTest.testEncryption(),
            tempDir.toString());
    lenient()
        .when(modelResolver.resolution(any(), eq("image"), any()))
        .thenReturn(ModelResolution.of("stable-diffusion-xl"));
  }

  @Test
  void execute_whenTheDefaultWasReplaced_reportsTheNoticeWithTheImage() throws Exception {
    when(modelResolver.resolution(any(), eq("image"), any()))
        .thenReturn(
            new ModelResolution(
                "stable-diffusion-3.5-large", "FLUX is not available right now; SD 3.5 was used."));
    when(aiClient.image(any(ImageRequest.class)))
        .thenReturn(new ImageResponse(new byte[] {1, 2, 3, 4}, null, "png"));
    JobCommand message =
        new JobCommand("job-9", "user-1", "image.generation", "", Map.of("prompt", "A kitten"));
    JobExecutionContext context =
        new JobExecutionContext("u", "c", java.util.Map.of(), java.util.Set.of());

    Map<String, Object> result = strategy.execute(message, context).output();

    assertEquals("stable-diffusion-3.5-large", result.get("model"));
    assertEquals("FLUX is not available right now; SD 3.5 was used.", result.get("modelNotice"));
  }

  @Test
  void handlerKey_isImageGenerate() {
    assertEquals("image.generate", strategy.handlerKey());
  }

  @Test
  void execute_withImageData_savesToFile() throws Exception {
    String jobId = "job-1";
    String userId = "user-1";

    Map<String, Object> payload = Map.of("prompt", "A cute kitten");
    JobCommand message = new JobCommand(jobId, userId, "image.generation", "", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    byte[] imgData = new byte[] {1, 2, 3, 4};
    ImageResponse response = new ImageResponse(imgData, null, "png");
    when(aiClient.image(any(ImageRequest.class))).thenReturn(response);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertTrue(result.get("url").toString().contains("/api/v1/assets/job-1/image.png"));
    assertEquals("png", result.get("format"));
  }

  @Test
  void execute_withDataUrl_decodesAndSavesToFile() throws Exception {
    String jobId = "job-1";
    String userId = "user-1";

    Map<String, Object> payload = Map.of("prompt", "A cute kitten");
    JobCommand message = new JobCommand(jobId, userId, "image.generation", "", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    // "data:image/png;base64,AQID" decodes to [1, 2, 3]
    ImageResponse response = new ImageResponse(null, "data:image/png;base64,AQID", "png");
    when(aiClient.image(any(ImageRequest.class))).thenReturn(response);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertTrue(result.get("url").toString().contains("/api/v1/assets/job-1/image.png"));
    assertEquals("png", result.get("format"));
  }

  @Test
  void execute_withHttpUrl_returnsUrlDirectly() throws Exception {
    String jobId = "job-1";
    String userId = "user-1";

    Map<String, Object> payload = Map.of("prompt", "A cute kitten");
    JobCommand message = new JobCommand(jobId, userId, "image.generation", "", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ImageResponse response = new ImageResponse(null, "https://example.com/generated.png", "png");
    when(aiClient.image(any(ImageRequest.class))).thenReturn(response);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertEquals("https://example.com/generated.png", result.get("url"));
    assertEquals("png", result.get("format"));
  }

  @Test
  void execute_withInvalidDataUrl_doesNotCrash() throws Exception {
    String jobId = "job-1";
    String userId = "user-1";

    Map<String, Object> payload = Map.of("prompt", "A cute kitten");
    JobCommand message = new JobCommand(jobId, userId, "image.generation", "", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ImageResponse response = new ImageResponse(null, "data:image/png;base64,!!!invalid!!!", "png");
    when(aiClient.image(any(ImageRequest.class))).thenReturn(response);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertEquals("data:image/png;base64,!!!invalid!!!", result.get("url"));
  }

  @Test
  void execute_carriesSizeSeedAndNegativePrompt_andReportsHowTheImageWasMade() throws Exception {
    Map<String, Object> payload =
        Map.of(
            "prompt", "A tea glass on a wooden table",
            "size", "1344x768",
            "seed", "4242",
            "negativePrompt", "text, logo");
    JobCommand message = new JobCommand("job-2", "user-1", "image.generation", "", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());
    when(aiClient.image(any(ImageRequest.class)))
        .thenReturn(new ImageResponse(new byte[] {1, 2, 3}, null, "png"));

    Map<String, Object> result = strategy.execute(message, context).output();

    ArgumentCaptor<ImageRequest> sent = ArgumentCaptor.forClass(ImageRequest.class);
    verify(aiClient).image(sent.capture());
    assertEquals(1344, sent.getValue().width());
    assertEquals(768, sent.getValue().height());
    assertEquals(new ImageDirection("text, logo", 4242L, null), sent.getValue().direction());
    assertEquals("stable-diffusion-xl", result.get("model"));
    assertEquals("4242", result.get("seed"));
    assertTrue(result.get("generationMs") instanceof Long);
  }

  @Test
  void execute_withoutASeed_choosesOne_andReportsIt() throws Exception {
    JobCommand message =
        new JobCommand("job-3", "user-1", "image.generation", "", Map.of("prompt", "A kitten"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());
    when(aiClient.image(any(ImageRequest.class)))
        .thenReturn(new ImageResponse(new byte[] {1, 2, 3}, null, "png"));

    Map<String, Object> result = strategy.execute(message, context).output();

    ArgumentCaptor<ImageRequest> sent = ArgumentCaptor.forClass(ImageRequest.class);
    verify(aiClient).image(sent.capture());
    Long seed = sent.getValue().direction().seed();
    assertNotNull(seed, "a job that chose no seed could never be made again");
    assertEquals(String.valueOf(seed), result.get("seed"));
  }

  @Test
  void execute_withAMalformedSize_failsTheJob() {
    JobCommand message =
        new JobCommand(
            "job-4",
            "user-1",
            "image.generation",
            "",
            Map.of("prompt", "A kitten", "size", "huge"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    assertThrows(
        com.krizaka.orazaka.jobs.domain.exception.JobExecutionException.class,
        () -> strategy.execute(message, context));
    verifyNoInteractions(aiClient);
  }

  /** A real store over a throwaway keyring: tests exercise the format, never a stub of it. */
  static com.krizaka.orazaka.assets.application.service.EncryptedAssetService testAssets() {
    try {
      java.nio.file.Path keyFile =
          java.nio.file.Files.createTempDirectory("orz-keys").resolve("master.key");
      com.krizaka.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider.addKey(
          keyFile, "test");
      return new com.krizaka.orazaka.assets.application.service.EncryptedAssetService(
          new com.krizaka.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider(keyFile),
          4096);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void execute_withReferences_sendsThePicturesAndRecordsThemInTheRecipe() throws Exception {
    String owl = "11111111-1111-4111-8111-111111111111";
    java.nio.file.Path mine = java.nio.file.Files.createDirectories(tempDir.resolve("user-1"));
    // An upload is sealed at rest like every stored file (ADR-054).
    assets.write(
        mine.resolve(owl + ".png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0});
    when(aiClient.image(any(ImageRequest.class)))
        .thenReturn(
            new ImageResponse(
                new byte[] {1, 2, 3, 4}, null, "png", null, "weights recorded, not applied"));
    JobCommand message =
        new JobCommand(
            "job-7",
            "user-1",
            "image.generation",
            "",
            Map.of("prompt", "the same owl", "references", owl, "referenceWeight", "0.5"));
    JobExecutionContext context =
        new JobExecutionContext("u", "c", java.util.Map.of(), java.util.Set.of());

    Map<String, Object> result = strategy.execute(message, context).output();

    ArgumentCaptor<ImageRequest> sent = ArgumentCaptor.forClass(ImageRequest.class);
    org.mockito.Mockito.verify(aiClient).image(sent.capture());
    assertEquals(1, sent.getValue().references().size());
    assertEquals(0.5, sent.getValue().references().getFirst().weight());
    assertEquals(java.util.List.of(owl), result.get("references"));
    assertEquals(0.5, result.get("referenceWeight"));
    assertEquals("weights recorded, not applied", result.get("referenceNotice"));
  }

  @Test
  void execute_withAReferenceThatIsNotTheActors_failsByName() {
    JobCommand message =
        new JobCommand(
            "job-8",
            "user-1",
            "image.generation",
            "",
            Map.of("prompt", "an owl", "references", "33333333-3333-4333-8333-333333333333"));
    JobExecutionContext context =
        new JobExecutionContext("u", "c", java.util.Map.of(), java.util.Set.of());

    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.krizaka.orazaka.jobs.domain.exception.JobExecutionException.class,
            () -> strategy.execute(message, context));
    assertTrue(failure.getMessage().contains("is not among your files"));
  }

  /**
   * [krizaka-internal#2090] The output of an earlier image job is sealed at rest (ADR-054): read
   * raw, its first bytes are ciphertext and the reference was refused as {@code
   * application/octet-stream}. It is read through the store, so its own PNG bytes reach the model.
   */
  @Test
  void execute_withTheOutputOfAnEarlierJobAsReference_sendsItsDecryptedPicture() throws Exception {
    String earlierJob = "44444444-4444-4444-8444-444444444444";
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};
    store.save(tempDir.toString(), "user-1", earlierJob, png, "image.png");
    when(aiClient.image(any(ImageRequest.class)))
        .thenReturn(new ImageResponse(new byte[] {1, 2, 3, 4}, null, "png"));
    JobCommand message =
        new JobCommand(
            "job-10",
            "user-1",
            "image.generation",
            "",
            Map.of("prompt", "the same owl, at the window", "references", earlierJob));
    JobExecutionContext context =
        new JobExecutionContext("u", "c", java.util.Map.of(), java.util.Set.of());

    strategy.execute(message, context);

    ArgumentCaptor<ImageRequest> sent = ArgumentCaptor.forClass(ImageRequest.class);
    org.mockito.Mockito.verify(aiClient).image(sent.capture());
    var reference = sent.getValue().references().getFirst();
    assertEquals("image/png", reference.mediaType());
    org.junit.jupiter.api.Assertions.assertArrayEquals(png, reference.image());
  }

  /**
   * [krizaka-internal#2090] A redeploy wipes the disk, not the durable store: the output of an
   * earlier job is brought back from it, as {@code MediaFileStore.read} does for a download. Read
   * from the disk only, every reference failed as « not among your files » after a deploy.
   */
  @Test
  void execute_withAReferenceGoneFromTheDiskAfterARedeploy_restoresItFromTheDurableStore()
      throws Exception {
    java.util.Map<String, byte[]> durable = new java.util.HashMap<>();
    var bucket =
        new com.krizaka.orazaka.assets.domain.port.DurableAssetStore() {
          @Override
          public boolean enabled() {
            return true;
          }

          @Override
          public void put(String key, java.nio.file.Path file) throws java.io.IOException {
            durable.put(key, java.nio.file.Files.readAllBytes(file));
          }

          @Override
          public boolean fetch(String key, java.nio.file.Path file) throws java.io.IOException {
            byte[] bytes = durable.get(key);
            if (bytes == null) {
              return false;
            }
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, bytes);
            return true;
          }

          @Override
          public void delete(String key) {
            durable.remove(key);
          }
        };
    MediaFileStore durableStore =
        new MediaFileStore(
            assets,
            bucket,
            new com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties(
                "local", null, null, "storage.bunnycdn.com", null, "default"));
    ImageGenerationStrategy afterDeploy =
        new ImageGenerationStrategy(
            aiClient,
            modelResolver,
            durableStore,
            assets,
            VisionAnalysisStrategyTest.testEncryption(),
            tempDir.toString());
    String earlierJob = "55555555-5555-4555-8555-555555555555";
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};
    durableStore.save(tempDir.toString(), "user-1", earlierJob, png, "image.png");
    // The redeploy: the container's disk is new.
    org.springframework.util.FileSystemUtils.deleteRecursively(tempDir.resolve("user-1"));
    when(aiClient.image(any(ImageRequest.class)))
        .thenReturn(new ImageResponse(new byte[] {1, 2, 3, 4}, null, "png"));
    JobCommand message =
        new JobCommand(
            "job-11",
            "user-1",
            "image.generation",
            "",
            Map.of("prompt", "the same owl, after the deploy", "references", earlierJob));
    JobExecutionContext context =
        new JobExecutionContext("u", "c", java.util.Map.of(), java.util.Set.of());

    afterDeploy.execute(message, context);

    ArgumentCaptor<ImageRequest> sent = ArgumentCaptor.forClass(ImageRequest.class);
    org.mockito.Mockito.verify(aiClient).image(sent.capture());
    var reference = sent.getValue().references().getFirst();
    assertEquals("image/png", reference.mediaType());
    org.junit.jupiter.api.Assertions.assertArrayEquals(png, reference.image());
  }
}
