package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.core.domain.model.audio.AudioRequest;
import com.krizaka.orazaka.core.domain.model.audio.AudioResponse;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
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
class SpeechSynthesisStrategyTest {

  @TempDir Path tempDir;

  @Mock private AiClient aiClient;
  @Mock private ModelResolver modelResolver;

  private SpeechSynthesisStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy =
        new SpeechSynthesisStrategy(
            aiClient, modelResolver, new MediaFileStore(testAssets()), tempDir.toString());
    lenient()
        .when(modelResolver.resolve(any(), eq("speech"), any()))
        .thenReturn("piper-en-medium-ryan");
  }

  @Test
  void handlerKey_isSpeechSynthesize() {
    assertEquals("speech.synthesize", strategy.handlerKey());
  }

  @Test
  void execute_successfulSynthesis_savesToFile() throws Exception {
    String jobId = "job-1";
    String userId = "user-1";

    Map<String, Object> payload = Map.of("prompt", "Hello world", "voice", "ryan");
    JobCommand message = new JobCommand(jobId, userId, "speech.synthesis", "", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    byte[] audioBytes = new byte[] {5, 6, 7};
    AudioResponse response = new AudioResponse(audioBytes, "mp3");
    when(aiClient.audio(any(AudioRequest.class))).thenReturn(response);

    Map<String, Object> result = strategy.execute(message, context).output();

    assertNotNull(result);
    assertTrue(result.get("url").toString().contains("/api/v1/assets/job-1/speech.mp3"));
    assertEquals("mp3", result.get("format"));
    assertNotNull(result.get("durationMs"));
  }

  @Test
  void execute_reportsTheCharactersItSynthesised() throws Exception {
    // audit #22: TTS measured nothing, so its hold was released and the work was served free.
    Map<String, Object> payload = Map.of("prompt", "Bonjour tout le monde", "voice", "ryan");
    JobCommand message = new JobCommand("job-2", "user-1", "speech.synthesis", "", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());
    when(aiClient.audio(any(AudioRequest.class)))
        .thenReturn(new AudioResponse(new byte[] {1}, "mp3"));

    var executed = strategy.execute(message, context);

    assertEquals(21L, executed.consumption().get("characters"));
    assertEquals("piper-en-medium-ryan", executed.model());
  }

  @Test
  void execute_simulatedFailure_throwsJobExecutionException() {
    String jobId = "job-1";
    String userId = "user-1";

    Map<String, Object> payload = Map.of("prompt", "FAIL: this should fail");
    JobCommand message = new JobCommand(jobId, userId, "speech.synthesis", "", payload);
    JobExecutionContext context =
        new JobExecutionContext(
            "test-user", "test-conversation", java.util.Map.of(), java.util.Set.of());

    assertThrows(JobExecutionException.class, () -> strategy.execute(message, context));
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
