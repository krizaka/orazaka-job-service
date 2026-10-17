package com.krizaka.orazaka.jobservice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seed of the catalogue (infra/initdb/30-jobs-config.sql) names the optional video upscale: the video capability
 * takes an {@code upscale} input (none by default), and SeedVR2 3B is a model row — Apache-2.0, commercial use allowed,
 * its price said in its description from the measured runs. Read as text: the database itself is the ITs' business.
 */
class SeedUpscaleCatalogueTest {

  private static final String SEED = read();

  private static String read() {
    try {
      return Files.readString(Path.of("infra/initdb/30-jobs-config.sql"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("the video capability accepts upscale: none, 1080p or 2160p, none by default")
  void theVideoCapabilityAcceptsUpscale() {
    String video = SEED.substring(SEED.indexOf("('orazaka.core.media.video'"));
    video = video.substring(0, video.indexOf("true),"));
    assertThat(video).contains("\"upscale\": {\"type\": \"string\", \"enum\": [\"none\", \"1080p\", \"2160p\"], \"default\": \"none\"");
  }

  @Test
  @DisplayName("SeedVR2 3B is a model row: category video-upscale, Apache-2.0, commercial use yes, runs on our worker")
  void seedvr2IsAModelRow() {
    assertThat(SEED).contains("('seedvr2-3b', 'SeedVR2 3B (video upscale, GPU)', 'video-upscale', NULL, 'localai-video', FALSE");
    assertThat(SEED).contains("licence = 'Apache-2.0'");
    assertThat(SEED).containsPattern("UPDATE orazaka_models SET [^;]*capabilities = 'video-upscale'[^;]*commercial_use = 'yes'[^;]*WHERE model_name = 'seedvr2-3b';");
  }
}
