package com.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.orazaka.core.domain.model.chat.ChatRequest;
import com.orazaka.core.domain.model.chat.ChatResponse;
import com.orazaka.core.domain.model.chat.TokenUsage;
import com.orazaka.core.domain.ports.inbound.AiClient;
import com.orazaka.core.infrastructure.config.CoreProperties;
import com.orazaka.jobs.domain.exception.JobExecutionException;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.model.JobExecutionContext;
import com.orazaka.persistence.domain.model.CatalogModelDto;
import com.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VisionAnalysisStrategyTest {

  @TempDir Path tempDir;

  private final com.orazaka.assets.application.service.EncryptedAssetService assets = testAssets();

  @Mock private AiClient aiClient;
  @Mock private CatalogModelManager catalogModelManager;

  private CoreProperties coreProperties;
  private VisionAnalysisStrategy strategy;

  @BeforeEach
  void setUp() {
    coreProperties =
        new CoreProperties(
            "ollama",
            null,
            null,
            null,
            null,
            null,
            new CoreProperties.VisionConfig("openai", "gpt-4o-vision"),
            null);
    strategy =
        new VisionAnalysisStrategy(
            aiClient, coreProperties, catalogModelManager, assets, testEncryption());
  }

  @Test
  void handlerKey_isImageAnalyze() {
    assertEquals("image.analyze", strategy.handlerKey());
  }

  @Test
  void execute_missingFilePath_throwsException() {
    JobCommand message = new JobCommand("job-1", "user-1", "vision", Map.of());
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    JobExecutionException exception =
        assertThrows(JobExecutionException.class, () -> strategy.execute(message, context));
    assertEquals("Payload does not contain filePath field", exception.getMessage());
  }

  @Test
  void execute_fileNotFound_throwsException() {
    JobCommand message =
        new JobCommand("job-1", "user-1", "vision", Map.of("filePath", "non-existent-file.png"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    JobExecutionException exception =
        assertThrows(JobExecutionException.class, () -> strategy.execute(message, context));
    assertTrue(exception.getMessage().contains("Image file not found"));
  }

  @Test
  void execute_successfulExecution_returnsAnalysisResult() throws Exception {
    Path imageFile = tempDir.resolve("test-image.png");
    assets.write(imageFile, new byte[] {1, 2, 3});

    Map<String, Object> payload = new HashMap<>();
    payload.put("filePath", imageFile.toAbsolutePath().toString());
    payload.put("prompt", "Describe this image");
    JobCommand message = new JobCommand("job-1", "user-1", "vision", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ChatResponse chatResponse =
        new ChatResponse(
            "A beautiful poster", "conv-1", TokenUsage.reported(400, 3000, 3400), Map.of());
    when(aiClient.chat(any(ChatRequest.class))).thenReturn(chatResponse);
    when(catalogModelManager.getDefaultModelByCategory("vision")).thenReturn(Optional.empty());

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertEquals("A beautiful poster", result.get("analysis"));

    ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(aiClient).chat(captor.capture());
    ChatRequest capturedRequest = captor.getValue();
    assertTrue(capturedRequest.prompt().startsWith("Describe this image [posterBase64: "));
    assertEquals("openai", capturedRequest.settings().get("provider"));
    assertEquals("gpt-4o-vision", capturedRequest.settings().get("model"));
  }

  @Test
  void execute_invalidVisionResponse_usesFallback() throws Exception {
    Path imageFile = tempDir.resolve("test-image.png");
    assets.write(imageFile, new byte[] {1, 2, 3});

    Map<String, Object> payload = new HashMap<>();
    payload.put("filePath", imageFile.toAbsolutePath().toString());
    JobCommand message = new JobCommand("job-1", "user-1", "vision", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ChatResponse chatResponse =
        new ChatResponse(
            "I don't see any image here", "conv-1", TokenUsage.reported(400, 3000, 3400), Map.of());
    when(aiClient.chat(any(ChatRequest.class))).thenReturn(chatResponse);
    when(catalogModelManager.getDefaultModelByCategory("vision")).thenReturn(Optional.empty());

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertTrue(
        result.get("analysis").toString().contains("Visual analysis of the uploaded poster shows"));
  }

  @Test
  void execute_withModelOverride_usesOverride() throws Exception {
    Path imageFile = tempDir.resolve("test-image.png");
    assets.write(imageFile, new byte[] {1, 2, 3});

    Map<String, Object> payload = new HashMap<>();
    payload.put("filePath", imageFile.toAbsolutePath().toString());
    payload.put("model", "custom-vision-model");
    JobCommand message = new JobCommand("job-1", "user-1", "vision", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ChatResponse chatResponse =
        new ChatResponse(
            "A beautiful poster", "conv-1", TokenUsage.reported(400, 3000, 3400), Map.of());
    when(aiClient.chat(any(ChatRequest.class))).thenReturn(chatResponse);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(aiClient).chat(captor.capture());
    assertEquals("custom-vision-model", captor.getValue().settings().get("model"));
  }

  @Test
  void execute_withCatalogDefaultModel_usesCatalogModel() throws Exception {
    Path imageFile = tempDir.resolve("test-image.png");
    assets.write(imageFile, new byte[] {1, 2, 3});

    Map<String, Object> payload = new HashMap<>();
    payload.put("filePath", imageFile.toAbsolutePath().toString());
    JobCommand message = new JobCommand("job-1", "user-1", "vision", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ChatResponse chatResponse =
        new ChatResponse(
            "A beautiful poster", "conv-1", TokenUsage.reported(400, 3000, 3400), Map.of());
    when(aiClient.chat(any(ChatRequest.class))).thenReturn(chatResponse);
    CatalogModelDto catalogModel =
        new CatalogModelDto(
            1, "catalog-vision-model", "catalog-vision-model", "vision", null, true);
    when(catalogModelManager.getDefaultModelByCategory("vision"))
        .thenReturn(Optional.of(catalogModel));

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(aiClient).chat(captor.capture());
    assertEquals("catalog-vision-model", captor.getValue().settings().get("model"));
  }

  @Test
  void execute_withNullProperties_usesFallbackDefaults() throws Exception {
    Path imageFile = tempDir.resolve("test-image.png");
    assets.write(imageFile, new byte[] {1, 2, 3});

    Map<String, Object> payload = new HashMap<>();
    payload.put("filePath", imageFile.toAbsolutePath().toString());
    JobCommand message = new JobCommand("job-1", "user-1", "vision", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ChatResponse chatResponse =
        new ChatResponse(
            "A beautiful poster", "conv-1", TokenUsage.reported(400, 3000, 3400), Map.of());
    when(aiClient.chat(any(ChatRequest.class))).thenReturn(chatResponse);
    when(catalogModelManager.getDefaultModelByCategory("vision")).thenReturn(Optional.empty());

    VisionAnalysisStrategy fallbackStrategy =
        new VisionAnalysisStrategy(aiClient, null, catalogModelManager, assets, testEncryption());
    Map<String, Object> result = fallbackStrategy.execute(message, context).output();

    assertNotNull(result);
    ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
    verify(aiClient).chat(captor.capture());
    assertEquals("ollama", captor.getValue().settings().get("provider"));
    assertEquals("llama3.2-vision:latest", captor.getValue().settings().get("model"));
  }

  /** A real store over a throwaway keyring: tests exercise the format, never a stub of it. */
  static com.orazaka.assets.application.service.EncryptedAssetService testAssets() {
    try {
      java.nio.file.Path keyFile =
          java.nio.file.Files.createTempDirectory("orz-keys").resolve("master.key");
      com.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider.addKey(keyFile, "test");
      return new com.orazaka.assets.application.service.EncryptedAssetService(
          new com.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider(keyFile), 4096);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** The cutover tolerance a unit test runs under: off, like production after the migration. */
  static com.orazaka.assets.infrastructure.config.AssetEncryptionProperties testEncryption() {
    return new com.orazaka.assets.infrastructure.config.AssetEncryptionProperties(
        true, "unused", 4096, false);
  }
}
