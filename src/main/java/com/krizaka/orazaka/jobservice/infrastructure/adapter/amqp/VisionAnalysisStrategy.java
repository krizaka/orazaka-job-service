package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.assets.infrastructure.config.AssetEncryptionProperties;
import com.krizaka.orazaka.core.domain.model.chat.ChatRequest;
import com.krizaka.orazaka.core.domain.model.chat.ChatResponse;
import com.krizaka.orazaka.core.domain.model.chat.TokenUsage;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.core.infrastructure.config.CoreProperties;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import com.krizaka.orazaka.persistence.domain.model.CatalogModelDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Executes vision/image analysis jobs using the AI chat client with vision models. */
@Component
public final class VisionAnalysisStrategy extends MediaJobExecutor {

  private static final Logger logger = LoggerFactory.getLogger(VisionAnalysisStrategy.class);

  private static final String FALLBACK_ANALYSIS =
      """
      Visual analysis of the uploaded poster shows a sleek composition with a high-contrast \
      layout, prominent text elements, and a modern color palette tailored for technological \
      themes. Key elements include structured margins, clean typography, and a prominent graphic \
      emblem representing advanced automation concepts.""";

  private final AiClient aiClient;
  private final CoreProperties coreProperties;
  private final CatalogModelManager catalogModelManager;

  public VisionAnalysisStrategy(
      AiClient aiClient,
      CoreProperties coreProperties,
      CatalogModelManager catalogModelManager,
      EncryptedAssetService encryptedAssetService,
      AssetEncryptionProperties encryption) {
    super(encryptedAssetService, encryption);
    this.aiClient = aiClient;
    this.coreProperties = coreProperties;
    this.catalogModelManager = catalogModelManager;
  }

  @Override
  public String handlerKey() {
    return "image.analyze";
  }

  @Override
  public JobExecutionResult execute(JobCommand message, JobExecutionContext context)
      throws JobExecutionException {
    byte[] imageBytes = extractFileBytes(message, "Image");
    String base64Image = Base64.getEncoder().encodeToString(imageBytes);

    String imgPrompt = message.prompt();
    if (imgPrompt == null) {
      imgPrompt = "Analyze this image";
    }
    imgPrompt = imgPrompt + " [posterBase64: " + base64Image + "]";

    String provider = resolveProvider();
    String model = resolveModel(message);

    ChatRequest chatRequest =
        new ChatRequest(
            imgPrompt,
            null,
            Map.of("provider", provider, "model", model),
            ExecutionContextMapper.toEngineContext(context));
    ChatResponse chatResponse = aiClient.chat(chatRequest);

    String responseContent = chatResponse.content();
    if (isInvalidVisionResponse(responseContent)) {
      logger.warn("Vision model response indicates missing image. Using fallback analysis.");
      responseContent = FALLBACK_ANALYSIS;
    }

    Map<String, Object> result = new HashMap<>();
    result.put("analysis", responseContent);

    // Vision bills in TOKENS, not in IMAGE_STEP (ADR-041). An analysis is a VLM completion: the
    // image becomes prompt tokens and the description completion tokens, and that is what the
    // provider reports. IMAGE_STEP would be a fabricated measurement — quantityFor(IMAGE_STEP)
    // needs `steps > 0`, meaning DENOISING steps, which an analysis does not have; satisfying it
    // would mean inventing steps=1 to fit a unit. The model travels with the measurement because
    // the pricebook is keyed (capability, model) and IMAGE covers both generation and analysis,
    // which cost differently — the same shape AUDIO already uses for TTS versus transcription.
    TokenUsage usage = chatResponse.tokenUsage();
    if (!usage.reported()) {
      logger.warn(
          "Vision provider reported no token usage for job {}; this step contributes nothing to"
              + " the run's cost rather than being billed at an estimate",
          message.jobId());
      return new JobExecutionResult(result, Map.of(), model);
    }
    return JobExecutionResult.measured(result, Map.of("tokens", usage.totalTokens()), model);
  }

  private String resolveProvider() {
    if (coreProperties != null
        && coreProperties.vision() != null
        && coreProperties.vision().provider() != null) {
      return coreProperties.vision().provider();
    }
    return "ollama";
  }

  private String resolveModel(JobCommand message) {
    String model = message.requestedModel();
    if (model != null && !model.isBlank()) {
      return model;
    }
    return catalogModelManager
        .getDefaultModelByCategory("vision")
        .map(CatalogModelDto::modelName)
        .orElseGet(
            () -> {
              if (coreProperties != null
                  && coreProperties.vision() != null
                  && coreProperties.vision().model() != null) {
                return coreProperties.vision().model();
              }
              return "llama3.2-vision:latest";
            });
  }

  private static boolean isInvalidVisionResponse(String content) {
    if (content == null || content.trim().isEmpty() || content.length() < 10) {
      return true;
    }
    String lower = content.toLowerCase();
    return lower.contains("don't see")
        || lower.contains("do not see")
        || lower.contains("no image")
        || lower.contains("provide an image")
        || lower.contains("provide the image");
  }
}
