package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.core.domain.model.image.ImageRequest;
import com.krizaka.orazaka.core.domain.model.image.ImageResponse;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobservice.infrastructure.support.MediaFileStore;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImageGenerationStrategyTest {

  @TempDir Path tempDir;

  @Mock private AiClient aiClient;
  @Mock private ModelResolver modelResolver;

  private ImageGenerationStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy =
        new ImageGenerationStrategy(
            aiClient, modelResolver, new MediaFileStore(testAssets()), tempDir.toString());
    lenient()
        .when(modelResolver.resolve(any(), eq("image"), any()))
        .thenReturn("stable-diffusion-xl");
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
}
