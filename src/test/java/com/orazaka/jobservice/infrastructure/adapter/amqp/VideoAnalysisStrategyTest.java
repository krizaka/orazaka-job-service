package com.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.orazaka.core.application.processing.ProcessedVideoPayload;
import com.orazaka.core.application.processing.VideoPreProcessor;
import com.orazaka.jobs.domain.exception.JobExecutionException;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.model.JobExecutionContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisStrategyTest {

  @TempDir Path tempDir;

  private final com.orazaka.assets.application.service.EncryptedAssetService assets = testAssets();

  @Mock private VideoPreProcessor videoPreProcessor;

  private VideoAnalysisStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new VideoAnalysisStrategy(videoPreProcessor, assets, testEncryption());
  }

  @Test
  void handlerKey_isVideoAnalyze() {
    assertEquals("video.analyze", strategy.handlerKey());
  }

  @Test
  void execute_missingFilePath_throwsException() {
    JobCommand message =
        new JobCommand("job-1", "user-1", "orazaka.core.media.video.analysis", Map.of());
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    assertThrows(JobExecutionException.class, () -> strategy.execute(message, context));
  }

  @Test
  void execute_fileNotFound_throwsException() {
    JobCommand message =
        new JobCommand(
            "job-1",
            "user-1",
            "orazaka.core.media.video.analysis",
            Map.of("filePath", "missing.mp4"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    assertThrows(JobExecutionException.class, () -> strategy.execute(message, context));
  }

  @Test
  void execute_successfulAnalysis_returnsTranscriptAndKeyframeCount() throws Exception {
    Path videoFile = tempDir.resolve("test.mp4");
    assets.write(videoFile, new byte[] {1, 2, 3});

    JobCommand message =
        new JobCommand(
            "job-1",
            "user-1",
            "orazaka.core.media.video.analysis",
            Map.of("filePath", videoFile.toAbsolutePath().toString(), "model", "gemini-video"));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    ProcessedVideoPayload payload =
        new ProcessedVideoPayload(
            "Video transcription test output", List.of(new byte[] {1}, new byte[] {2}));
    when(videoPreProcessor.process(any(byte[].class), eq("gemini-video"))).thenReturn(payload);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertEquals("Video transcription test output", result.get("transcript"));
    assertEquals(2, result.get("keyframeCount"));
  }

  @Test
  void execute_reportsTheSourceMinutesUnderItsOwnEngineName() throws Exception {
    Path videoFile = tempDir.resolve("measured.mp4");
    assets.write(videoFile, new byte[] {1, 2, 3});
    JobCommand message =
        new JobCommand(
            "job-1",
            "user-1",
            "orazaka.core.media.video.analysis",
            Map.of("filePath", videoFile.toAbsolutePath().toString()));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());
    when(videoPreProcessor.process(any(byte[].class), eq(null)))
        .thenReturn(
            new ProcessedVideoPayload(
                "transcript", List.of(new byte[] {1}), new java.math.BigDecimal("90.5")));

    var executed = strategy.execute(message, context);

    // Not OUTPUT_SECOND: an analysis produces no video. The engine name keeps it off VIDEO's
    // diffusion rate, as composition does with `orazaka-compose` (ADR-041, ADR-066).
    assertEquals(new java.math.BigDecimal("90.5"), executed.consumption().get("audioSeconds"));
    assertEquals("orazaka-video-analysis", executed.model());
  }

  @Test
  void execute_withoutAProviderDuration_settlesUnmeasured() throws Exception {
    Path videoFile = tempDir.resolve("unmeasured.mp4");
    assets.write(videoFile, new byte[] {1, 2, 3});
    JobCommand message =
        new JobCommand(
            "job-1",
            "user-1",
            "orazaka.core.media.video.analysis",
            Map.of("filePath", videoFile.toAbsolutePath().toString()));
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());
    when(videoPreProcessor.process(any(byte[].class), eq(null)))
        .thenReturn(new ProcessedVideoPayload("transcript", List.of(new byte[] {1})));

    var executed = strategy.execute(message, context);

    assertTrue(executed.consumption().isEmpty());
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
