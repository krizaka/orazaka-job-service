package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.core.application.processing.AudioPreProcessor;
import com.krizaka.orazaka.core.application.processing.ProcessedAudioPayload;
import com.krizaka.orazaka.core.domain.model.audio.AudioRequest;
import com.krizaka.orazaka.core.domain.model.audio.AudioResponse;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import com.krizaka.orazaka.jobservice.infrastructure.support.MediaFileStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * The audio half of the divergence contract: what is billed follows the <b>act</b>, never the
 * request (ADR-066, extending ADR-062's image contract and ADR-063's video one).
 *
 * <p>The image contract serves a 768×512 image for a 512×512 request and checks the bill follows
 * the image; the composition contract composes a 9 s slideshow over a 2 s voiceover and checks the
 * bill follows the file. Audio has no artefact a test can decode without a model, so its oracle is
 * <b>what crossed the boundary</b>: the request the engine received, and the response the
 * transcription provider returned. Both are captured here, and neither is the executor's own claim
 * about them.
 *
 * <p>These two capabilities measured <b>nothing</b> until this run — every hold released, every
 * synthesis and every transcription served free (audit #22). A contract that asserts the quantity
 * is what makes the new measurement more than a line of code.
 */
class AudioBillingDivergenceContract {

  @TempDir Path uploads;

  @Test
  @DisplayName("synthesis bills the text the ENGINE received, not another field of the request")
  void synthesisBillsWhatWasSpoken() throws Exception {
    AiClient aiClient = mock(AiClient.class);
    ModelResolver models = mock(ModelResolver.class);
    when(models.resolve(any(), anyString(), anyString())).thenReturn("piper-en-medium-ryan");
    when(aiClient.audio(any(AudioRequest.class)))
        .thenReturn(new AudioResponse(new byte[4096], "mp3"));
    SpeechSynthesisStrategy strategy =
        new SpeechSynthesisStrategy(
            aiClient, models, new MediaFileStore(JobListenerTest.testAssets()), uploads.toString());

    // The divergence: two texts in one payload. `requirePrompt` sends `prompt`; a meter reading
    // the request's other field would bill 120 characters for 27 characters of speech.
    String spoken = "Votre rendez-vous est demain";
    JobCommand message =
        new JobCommand(
            "job-tts",
            "user-1",
            "orazaka.core.media.speech",
            "default",
            Map.of("prompt", spoken, "text", "x".repeat(120)),
            com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD);

    JobExecutionResult executed =
        strategy.execute(message, new JobExecutionContext("user-1", "c", Map.of(), Set.of()));

    ArgumentCaptor<AudioRequest> sent = ArgumentCaptor.forClass(AudioRequest.class);
    verify(aiClient).audio(sent.capture());
    assertThat(executed.consumption().get("characters"))
        .as("billed characters, against what the engine was asked to say")
        .isEqualTo((long) sent.getValue().prompt().length())
        .isEqualTo((long) spoken.length());
    // And not the artefact: 4096 bytes of mp3 have nothing to do with a KILOCHAR bill.
    assertThat(executed.consumption()).doesNotContainKeys("audioSeconds", "durationSeconds");
  }

  @Test
  @DisplayName("transcription bills the duration the PROVIDER reported, not the file it was sent")
  void transcriptionBillsWhatWasTranscribed() throws Exception {
    AudioPreProcessor transcriber = mock(AudioPreProcessor.class);
    ModelResolver models = mock(ModelResolver.class);
    when(models.resolve(any(), anyString(), anyString())).thenReturn("whisper-base");
    EncryptedAssetService assets = JobListenerTest.testAssets();
    Path source = uploads.resolve("long.wav");
    assets.write(source, new byte[64_000]);

    // The divergence: a large file the provider only transcribed 30 seconds of. What was done is
    // 30 seconds; a meter reading the file — its bytes, or its own probe — would bill more than
    // the work.
    when(transcriber.process(any(byte[].class), anyString()))
        .thenReturn(new ProcessedAudioPayload("bonjour", new BigDecimal("30.0")));
    AudioAnalysisStrategy strategy =
        new AudioAnalysisStrategy(
            transcriber, models, assets, AudioAnalysisStrategyTest.testEncryption());

    JobExecutionResult executed =
        strategy.execute(
            new JobCommand(
                "job-stt",
                "user-1",
                "orazaka.core.media.audio.analysis",
                "default",
                Map.of("filePath", source.toAbsolutePath().toString()),
                com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD),
            new JobExecutionContext("user-1", "c", Map.of(), Set.of()));

    assertThat(executed.consumption().get("audioSeconds"))
        .as("billed seconds, against the provider's own answer")
        .isEqualTo(new BigDecimal("30.0"));
    assertThat(executed.model()).isEqualTo("whisper-base");
  }

  @Test
  @DisplayName("a provider that reports no duration bills nothing at all")
  void anUnmeasuredTranscriptionIsReleased() throws Exception {
    AudioPreProcessor transcriber = mock(AudioPreProcessor.class);
    ModelResolver models = mock(ModelResolver.class);
    lenient().when(models.resolve(any(), anyString(), anyString())).thenReturn("whisper-base");
    EncryptedAssetService assets = JobListenerTest.testAssets();
    Path source = uploads.resolve("silent.wav");
    assets.write(source, new byte[128]);
    when(transcriber.process(any(byte[].class), anyString()))
        .thenReturn(new ProcessedAudioPayload("bonjour"));

    JobExecutionResult executed =
        new AudioAnalysisStrategy(
                transcriber, models, assets, AudioAnalysisStrategyTest.testEncryption())
            .execute(
                new JobCommand(
                    "job-stt-2",
                    "user-1",
                    "orazaka.core.media.audio.analysis",
                    "default",
                    Map.of("filePath", source.toAbsolutePath().toString()),
                    com.krizaka.orazaka.jobs.domain.model.DataClass.STANDARD),
                new JobExecutionContext("user-1", "c", Map.of(), Set.of()));

    // Released, never estimated: the error runs in the direction that costs the platform.
    assertThat(executed.consumption()).isEmpty();
  }
}
