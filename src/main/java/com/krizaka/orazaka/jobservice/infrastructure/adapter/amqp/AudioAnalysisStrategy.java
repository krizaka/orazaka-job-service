package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.assets.infrastructure.config.AssetEncryptionProperties;
import com.krizaka.orazaka.core.application.processing.AudioPreProcessor;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Executes audio transcription/analysis jobs by delegating to the {@link AudioPreProcessor}. */
@Component
public final class AudioAnalysisStrategy extends MediaJobExecutor {

  /** The catalogue's default transcription model when a job names none (seeded is_default). */
  private static final String FALLBACK_MODEL = "whisper-base";

  private final AudioPreProcessor audioPreProcessor;
  private final ModelResolver modelResolver;

  public AudioAnalysisStrategy(
      AudioPreProcessor audioPreProcessor,
      ModelResolver modelResolver,
      EncryptedAssetService encryptedAssetService,
      AssetEncryptionProperties encryption) {
    super(encryptedAssetService, encryption);
    this.audioPreProcessor = audioPreProcessor;
    this.modelResolver = modelResolver;
  }

  @Override
  public String handlerKey() {
    return "audio.analyze";
  }

  @Override
  public JobExecutionResult execute(JobCommand message, JobExecutionContext context)
      throws JobExecutionException {
    byte[] audioBytes = extractFileBytes(message, "Audio");
    // Resolved here rather than left null: the pricebook is keyed (capability, model) and prices
    // whisper-base and whisper-tiny-en apart, so an unnamed engine would fall to AUDIO's capability
    // default — which does not exist, because AUDIO bills per model (KILOCHAR for synthesis,
    // AUDIO_MINUTE for transcription) and a capability-wide unit would have to pick one.
    String model = modelResolver.resolve(message.requestedModel(), "audio", FALLBACK_MODEL);
    var payload = audioPreProcessor.process(audioBytes, model);

    String responseContent = payload.transcript();
    if (responseContent == null) {
      responseContent = "";
    }

    Map<String, Object> result = new HashMap<>();
    result.put("analysis", responseContent);
    // AUDIO_MINUTE: what transcription costs is how long the source is, which is why the pricebook
    // prices whisper that way and why nothing could settle before the provider's duration reached
    // here (audit #22). A provider that reports none leaves this unmeasured, and an unmeasured job
    // is released rather than billed at a guess.
    if (payload.durationSeconds() == null) {
      return new JobExecutionResult(result, Map.of(), model);
    }
    return JobExecutionResult.measured(
        result, Map.of("audioSeconds", payload.durationSeconds()), model);
  }
}
