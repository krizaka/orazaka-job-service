package com.krizaka.orazaka.jobservice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoutingKeysTest {

  @Test
  @DisplayName("A binding matches like an AMQP topic: * is one word, # is any number of words")
  void topicMatching() {
    assertThat(RoutingKeys.matches("job.video.*", "job.video.generate")).isTrue();
    assertThat(RoutingKeys.matches("job.video.generate", "job.video.generate")).isTrue();
    assertThat(RoutingKeys.matches("job.#", "job.video.generate")).isTrue();
    assertThat(RoutingKeys.matches("job.video.#", "job.video")).isTrue();
    assertThat(RoutingKeys.matches("job.*", "job.video.generate")).isFalse();
    assertThat(RoutingKeys.matches("job.video.*", "job.media.generate")).isFalse();
    assertThat(RoutingKeys.matches("job.compose.*", "job.video.generate")).isFalse();
    assertThat(RoutingKeys.matches("job.video.*", "job.video.generate.extra")).isFalse();
  }
}
