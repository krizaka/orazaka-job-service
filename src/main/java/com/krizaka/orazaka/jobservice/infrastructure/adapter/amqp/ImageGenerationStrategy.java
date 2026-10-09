package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.core.domain.model.image.ImageRequest;
import com.krizaka.orazaka.core.domain.model.image.ImageResponse;
import com.krizaka.orazaka.core.domain.model.image.ImageUsage;
import com.krizaka.orazaka.core.domain.ports.inbound.AiClient;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionContext;
import com.krizaka.orazaka.jobs.domain.model.JobExecutionResult;
import com.krizaka.orazaka.jobs.domain.port.JobExecutor;
import com.krizaka.orazaka.jobservice.infrastructure.support.MediaFileStore;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Executes image generation jobs via the AI client's image endpoint. */
@Component
public final class ImageGenerationStrategy implements JobExecutor {

  private static final Logger logger = LoggerFactory.getLogger(ImageGenerationStrategy.class);

  private final AiClient aiClient;
  private final ModelResolver modelResolver;
  private final MediaFileStore mediaFileStore;
  private final String uploadDir;

  public ImageGenerationStrategy(
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
    return "image.generate";
  }

  @Override
  public JobExecutionResult execute(JobCommand message, JobExecutionContext context)
      throws JobExecutionException {
    String prompt = message.requirePrompt();

    String model = modelResolver.resolve(message.model(), "image", "stable-diffusion-xl");
    ImageRequest imageRequest =
        new ImageRequest(
            prompt, null, null, model, Map.of(), ExecutionContextMapper.toEngineContext(context));
    ImageResponse imageResponse = aiClient.image(imageRequest);

    String translatedUrl = "";
    byte[] imgData = imageResponse.imageData();

    if (imgData == null && imageResponse.url() != null) {
      String url = imageResponse.url().trim();
      if (url.startsWith("data:")) {
        imgData = decodeDataUrl(url);
      } else if (url.startsWith("http://") || url.startsWith("https://")) {
        translatedUrl = url;
      }
    }

    if (imgData != null && imgData.length > 0) {
      translatedUrl =
          mediaFileStore.save(uploadDir, message.userId(), message.jobId(), imgData, "image.png");
    } else if (translatedUrl.isEmpty() && imageResponse.url() != null) {
      translatedUrl = imageResponse.url();
    }

    Map<String, Object> result = new HashMap<>();
    result.put("url", translatedUrl);
    result.put("format", imageResponse.format() != null ? imageResponse.format() : "png");

    // The generation's parameters, reported as MEASUREMENTS — never as a unit or a price. The
    // ledger derives IMAGE_STEP (images x steps x megapixels) from the pinned rate; this executor
    // only says what ran. Without it, image generation reported wall clock and nothing priceable,
    // so every showcase step of every Studio run settled at zero (ADR-047).
    ImageUsage usage = imageResponse.usage();
    if (!usage.reported()) {
      // Unreported is not zero: releasing the hold beats billing a guess, exactly as the chat
      // strategy decides for a provider that returns no usage block.
      logger.warn(
          "No generation parameters reported for job {}; this step contributes nothing to the"
              + " run's cost rather than being billed at an estimate",
          message.jobId());
      return JobExecutionResult.of(result);
    }
    return new JobExecutionResult(
        result,
        Map.of(
            "images", usage.images(),
            "steps", usage.steps(),
            "width", usage.width(),
            "height", usage.height()),
        model);
  }

  private static byte[] decodeDataUrl(String url) {
    try {
      String base64 = url.substring(url.indexOf(",") + 1);
      return Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      LoggerFactory.getLogger(ImageGenerationStrategy.class)
          .warn("Failed to decode inline data URL", e);
      return new byte[0];
    }
  }
}
