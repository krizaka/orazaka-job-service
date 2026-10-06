package com.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.orazaka.core.application.processing.AudioPreProcessor;
import com.orazaka.core.application.processing.ProcessedAudioPayload;
import com.orazaka.jobs.domain.exception.JobExecutionException;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.model.JobExecutionContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AudioAnalysisStrategyTest {

  @TempDir Path tempDir;

  private final com.orazaka.assets.application.service.EncryptedAssetService assets = testAssets();

  @Mock private AudioPreProcessor audioPreProcessor;

  @Mock private ModelResolver modelResolver;

  private AudioAnalysisStrategy strategy;

  @BeforeEach
  void setUp() {
    // Mirrors the real resolver: an explicit model wins, otherwise the catalogue's default.
    org.mockito.Mockito.lenient()
        .when(modelResolver.resolve(any(), any(), any()))
        .thenAnswer(
            call -> {
              String requested = call.getArgument(0);
              return (requested == null || requested.isBlank()) ? "whisper-base" : requested;
            });
    strategy =
        new AudioAnalysisStrategy(audioPreProcessor, modelResolver, assets, testEncryption());
  }

  @Test
  void handlerKey_isAudioAnalyze() {
    assertEquals("audio.analyze", strategy.handlerKey());
  }

  @Test
  void execute_missingFilePath_throwsException() {
    JobCommand message = new JobCommand("job-1", "user-1", "audio", Map.of());
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    assertThrows(JobExecutionException.class, () -> strategy.execute(message, context));
  }

  @Test
  void execute_fileNotFound_throwsException() {
    JobCommand message =
        new JobCommand("job-1", "user-1", "audio", Map.of("filePath", "missing.wav"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    assertThrows(JobExecutionException.class, () -> strategy.execute(message, context));
  }

  @Test
  void execute_successfulAnalysis_returnsTranscript() throws Exception {
    Path audioFile = tempDir.resolve("test.wav");
    assets.write(audioFile, new byte[] {1, 2, 3});

    JobCommand message =
        new JobCommand(
            "job-1",
            "user-1",
            "audio",
            Map.of("filePath", audioFile.toAbsolutePath().toString(), "model", "whisper"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ProcessedAudioPayload payload = new ProcessedAudioPayload("Transcription test output");
    when(audioPreProcessor.process(any(byte[].class), eq("whisper"))).thenReturn(payload);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertEquals("Transcription test output", result.get("analysis"));
  }

  @Test
  void execute_reportsTheSourceMinutesAndTheEngine() throws Exception {
    Path audioFile = tempDir.resolve("measured.wav");
    assets.write(audioFile, new byte[] {1, 2, 3});
    JobCommand message =
        new JobCommand(
            "job-1", "user-1", "audio", Map.of("filePath", audioFile.toAbsolutePath().toString()));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());
    when(audioPreProcessor.process(any(byte[].class), eq("whisper-base")))
        .thenReturn(new ProcessedAudioPayload("bonjour", new java.math.BigDecimal("132.48")));

    var executed = strategy.execute(message, context);

    // AUDIO_MINUTE prices the source, and the engine travels because AUDIO holds two units.
    assertEquals(new java.math.BigDecimal("132.48"), executed.consumption().get("audioSeconds"));
    assertEquals("whisper-base", executed.model());
  }

  @Test
  void execute_withoutAProviderDuration_settlesUnmeasured() throws Exception {
    Path audioFile = tempDir.resolve("unmeasured.wav");
    assets.write(audioFile, new byte[] {1, 2, 3});
    JobCommand message =
        new JobCommand(
            "job-1", "user-1", "audio", Map.of("filePath", audioFile.toAbsolutePath().toString()));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());
    when(audioPreProcessor.process(any(byte[].class), eq("whisper-base")))
        .thenReturn(new ProcessedAudioPayload("bonjour"));

    var executed = strategy.execute(message, context);

    // Released, never billed at a guess: the error runs in the direction that costs the platform.
    assertTrue(executed.consumption().isEmpty());
    assertEquals("whisper-base", executed.model());
  }

  @Test
  void execute_nullTranscriptInPayload_returnsEmptyString() throws Exception {
    Path audioFile = tempDir.resolve("test.wav");
    assets.write(audioFile, new byte[] {1, 2, 3});

    JobCommand message =
        new JobCommand(
            "job-1", "user-1", "audio", Map.of("filePath", audioFile.toAbsolutePath().toString()));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ProcessedAudioPayload payload = new ProcessedAudioPayload(null);
    when(audioPreProcessor.process(any(byte[].class), any())).thenReturn(payload);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertEquals("", result.get("analysis"));
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
