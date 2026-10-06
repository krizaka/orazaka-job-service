package com.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orazaka.assets.application.service.EncryptedAssetService;
import com.orazaka.core.application.processing.ProcessedVideoPayload;
import com.orazaka.core.application.processing.VideoPreProcessor;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.model.JobExecutionContext;
import com.orazaka.jobs.domain.model.JobExecutionResult;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The video-analysis half of the divergence contract (ADR-066).
 *
 * <p>This path is where a wrong unit costs the most: VIDEO's capability rate is 900 credits per
 * output second, the price of inventing pixels, and an analysis invents none — it reads a file.
 * Billing it in OUTPUT_SECOND would have meant reporting seconds the work never produced, so the
 * quantity is the source duration and the rate is a row of its own, under the engine name this
 * executor reports.
 *
 * <p>Both halves are asserted, because either alone passes for the wrong reason: the quantity
 * follows the provider's measurement of the source, and the engine name is what takes it off the
 * diffusion rate.
 */
class VideoAnalysisBillingDivergenceContract {

  @TempDir Path uploads;

  private JobExecutionResult analyse(ProcessedVideoPayload payload) throws Exception {
    VideoPreProcessor processor = mock(VideoPreProcessor.class);
    EncryptedAssetService assets = JobListenerTest.testAssets();
    Path source = uploads.resolve("clip.mp4");
    assets.write(source, new byte[8_192]);
    when(processor.process(any(byte[].class), any())).thenReturn(payload);

    return new VideoAnalysisStrategy(processor, assets, VideoAnalysisStrategyTest.testEncryption())
        .execute(
            new JobCommand(
                "job-video-analysis",
                "user-1",
                "orazaka.core.media.video.analysis",
                "default",
                Map.of("filePath", source.toAbsolutePath().toString()),
                com.orazaka.jobs.domain.model.DataClass.STANDARD),
            new JobExecutionContext("user-1", "c", Map.of(), Set.of()));
  }

  @Test
  @DisplayName("an analysis bills source minutes under its own engine, never output seconds")
  void anAnalysisBillsTheSourceAndNotAnOutput() throws Exception {
    JobExecutionResult executed =
        analyse(
            new ProcessedVideoPayload(
                "bonjour", List.of(new byte[] {1}, new byte[] {2}), new BigDecimal("612.5")));

    assertThat(executed.consumption().get("audioSeconds")).isEqualTo(new BigDecimal("612.5"));
    assertThat(executed.consumption())
        .as("no output was produced, so no output second may be reported")
        .doesNotContainKeys("durationSeconds", "frames", "fps");
    assertThat(executed.model())
        .as("the engine name is what keeps this off VIDEO's diffusion rate")
        .isEqualTo("orazaka-video-analysis");
  }

  @Test
  @DisplayName("keyframes extracted are not a billable quantity")
  void keyframesAreNotBilled() throws Exception {
    JobExecutionResult executed =
        analyse(
            new ProcessedVideoPayload(
                "bonjour",
                List.of(new byte[] {1}, new byte[] {2}, new byte[] {3}),
                new BigDecimal("60")));

    // Three keyframes are in the OUTPUT, which the actor reads; the bill follows the source, and a
    // count that varies with a configured interval is a quantity the platform sets for itself.
    assertThat(executed.output()).containsEntry("keyframeCount", 3);
    assertThat(executed.consumption()).containsOnlyKeys("audioSeconds");
  }

  @Test
  @DisplayName("a video whose duration nobody measured bills nothing")
  void anUnmeasuredAnalysisIsReleased() throws Exception {
    JobExecutionResult executed =
        analyse(new ProcessedVideoPayload("bonjour", List.of(new byte[] {1})));

    assertThat(executed.consumption()).isEmpty();
    assertThat(executed.model()).isEqualTo("orazaka-video-analysis");
  }
}
