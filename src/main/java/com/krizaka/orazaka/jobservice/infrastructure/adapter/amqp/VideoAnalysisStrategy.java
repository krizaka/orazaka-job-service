package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.assets.infrastructure.config.AssetEncryptionProperties;
import com.krizaka.orazaka.core.application.processing.VideoPreProcessor;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Executes video analysis jobs by delegating to the {@link VideoPreProcessor}. */
@Component
public final class VideoAnalysisStrategy extends MediaJobExecutor {

  private final VideoPreProcessor videoPreProcessor;

  /** The engine name this executor prices under; see the pricebook row of the same name. */
  private static final String ANALYSIS_ENGINE = "orazaka-video-analysis";

  public VideoAnalysisStrategy(
      VideoPreProcessor videoPreProcessor,
      EncryptedAssetService encryptedAssetService,
      AssetEncryptionProperties encryption) {
    super(encryptedAssetService, encryption);
    this.videoPreProcessor = videoPreProcessor;
  }

  @Override
  public String handlerKey() {
    return "video.analyze";
  }

  @Override
  public JobExecutionResult execute(JobCommand message, JobExecutionContext context)
      throws JobExecutionException {
    byte[] videoBytes = extractFileBytes(message, "Video");
    String model = message.requestedModel();
    var payload = videoPreProcessor.process(videoBytes, model);

    Map<String, Object> result = new HashMap<>();
    result.put("transcript", payload.audioTranscript());
    result.put("keyframeCount", payload.keyframes().size());
    // Both halves of this work — keyframe extraction and transcription of the audio track — scale
    // with how long the source is, so AUDIO_MINUTE over the source duration is the measurement,
    // and OUTPUT_SECOND would be a fabrication: an analysis produces no video at all. Reported
    // under this executor's own engine name, the way composition reports `orazaka-compose`, because
    // VIDEO's capability default is a diffusion rate that would price an analysis like a
    // generation (ADR-041, ADR-066).
    if (payload.durationSeconds() == null) {
      return new JobExecutionResult(result, Map.of(), ANALYSIS_ENGINE);
    }
    return JobExecutionResult.measured(
        result, Map.of("audioSeconds", payload.durationSeconds()), ANALYSIS_ENGINE);
  }
}
