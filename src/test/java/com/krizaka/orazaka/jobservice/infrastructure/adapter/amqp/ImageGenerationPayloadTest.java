package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.core.domain.model.image.ImageDirection;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageGenerationPayloadTest {

  @Test
  @DisplayName("a Studio step's string inputs become a size, a seed, a negative prompt and steps")
  void studioStringsAreRead() {
    ImageGenerationPayload payload =
        ImageGenerationPayload.from(
            Map.of(
                "prompt", "a tea glass",
                "size", "1024x1280",
                "seed", "4242",
                "negativePrompt", " text, logo ",
                "steps", "8"));

    assertThat(payload.width()).isEqualTo(1024);
    assertThat(payload.height()).isEqualTo(1280);
    assertThat(payload.direction()).isEqualTo(new ImageDirection("text, logo", 4242L, 8));
  }

  @Test
  @DisplayName("optional inputs left empty by the template grammar are absent, not zero")
  void emptyInputsAreAbsent() {
    ImageGenerationPayload payload =
        ImageGenerationPayload.from(
            Map.of("prompt", "a tea glass", "size", "", "seed", "", "negativePrompt", ""));

    assertThat(payload.width()).isNull();
    assertThat(payload.height()).isNull();
    assertThat(payload.direction()).isEqualTo(ImageDirection.none());
  }

  @Test
  @DisplayName("a numeric seed from a JSON payload is read as is")
  void numericSeed() {
    assertThat(ImageGenerationPayload.from(Map.of("seed", 7)).direction().seed()).isEqualTo(7L);
  }

  @Test
  @DisplayName("a malformed size or seed is refused, never replaced by a default")
  void malformedValuesAreRefused() {
    assertThatThrownBy(() -> ImageGenerationPayload.from(Map.of("size", "big")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageGenerationPayload.from(Map.of("size", "1000x1000")))
        .as("off the 16-pixel latent grid")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageGenerationPayload.from(Map.of("size", "4096x4096")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageGenerationPayload.from(Map.of("seed", "lucky")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a seed is chosen only when the request named none")
  void seedIsChosenOnlyWhenAbsent() {
    assertThat(ImageGenerationPayload.from(Map.of()).withSeedIfAbsent(9).direction().seed())
        .isEqualTo(9L);
    assertThat(
            ImageGenerationPayload.from(Map.of("seed", "3")).withSeedIfAbsent(9).direction().seed())
        .isEqualTo(3L);
  }
}
