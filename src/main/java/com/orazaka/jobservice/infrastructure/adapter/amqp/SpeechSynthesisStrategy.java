package com.orazaka.jobservice.infrastructure.adapter.amqp;

import com.orazaka.core.domain.model.audio.AudioRequest;
import com.orazaka.core.domain.model.audio.AudioResponse;
import com.orazaka.core.domain.ports.inbound.AiClient;
import com.orazaka.jobs.domain.exception.JobExecutionException;
import com.orazaka.jobs.domain.model.JobCommand;
import com.orazaka.jobs.domain.model.JobExecutionContext;
import com.orazaka.jobs.domain.model.JobExecutionResult;
import com.orazaka.jobs.domain.port.JobExecutor;
import com.orazaka.jobservice.infrastructure.support.MediaFileStore;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Executes text-to-speech synthesis jobs via the AI client's audio endpoint. */
@Component
public final class SpeechSynthesisStrategy implements JobExecutor {

  private final AiClient aiClient;
  private final ModelResolver modelResolver;
  private final MediaFileStore mediaFileStore;
  private final String uploadDir;

  public SpeechSynthesisStrategy(
      AiClient aiClient,
      ModelResolver modelResolver,
      MediaFileStore mediaFileStore,
      @Value("${spring.servlet.multipart.location:var/orazaka-uploads}") String uploadDir) {
    this.aiClient = aiClient;
    this.modelResolver = modelResolver;
    this.mediaFileStore = mediaFileStore;
    this.uploadDir = uploadDir;
  }

  @Override
  public String handlerKey() {
    return "speech.synthesize";
  }

  @Override
  public JobExecutionResult execute(JobCommand message, JobExecutionContext context)
      throws JobExecutionException {
    String prompt = message.requirePrompt();

    // Simulation trigger for testing DLQ
    if (prompt.startsWith("FAIL")) {
      throw new JobExecutionException("Simulated speech synthesis failure for testing DLQ");
    }
    String model = modelResolver.resolve(message.model(), "speech", "piper-en-medium-ryan");
    String voice = message.voice();

    AudioRequest audioRequest =
        new AudioRequest(
            prompt, voice, model, Map.of(), ExecutionContextMapper.toEngineContext(context));
    long startTime = System.currentTimeMillis();
    AudioResponse audioResponse = aiClient.audio(audioRequest);
    long durationMs = System.currentTimeMillis() - startTime;

    byte[] audioBytes = audioResponse.audioData();
    String audioUrl =
        mediaFileStore.save(uploadDir, message.userId(), message.jobId(), audioBytes, "speech.mp3");

    Map<String, Object> result = new HashMap<>();
    result.put("url", audioUrl);
    result.put("format", "mp3");
    result.put("durationMs", durationMs);
    // KILOCHAR, and the characters are the ones sent to the engine: synthesis cost scales with the
    // text, which is known before a sample is produced — that is why the pricebook prices piper and
    // tts-1 per thousand characters. Until this line, TTS measured nothing and every hold was
    // released (audit #22): the work was done and never billed. The model travels with it because
    // the pricebook is keyed (capability, model) and AUDIO covers synthesis and transcription,
    // which are not the same act (ADR-066).
    return JobExecutionResult.measured(result, Map.of("characters", (long) prompt.length()), model);
  }
}
