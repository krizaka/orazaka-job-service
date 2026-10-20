package com.krizaka.orazaka.jobservice.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which queue a live worker drains: the registry's HEALTHY rows against the category's routing key.
 */
class RegistryQueueConsumersTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final RegistryQueueConsumers consumers = new RegistryQueueConsumers(jdbc);

  private void videoQueue() {
    when(jdbc.queryForList(anyString(), eq(String.class), eq("video"), eq(false)))
        .thenReturn(List.of("job.video.generate"));
  }

  private void healthyBindings(String... rows) {
    when(jdbc.queryForList(
            "SELECT bindings::text FROM worker_registry WHERE status = 'HEALTHY'", String.class))
        .thenReturn(List.of(rows));
  }

  @Test
  @DisplayName(
      "A category the job service runs itself is always drained: the registry is not asked")
  void inProcessCategoryIsDrained() {
    when(jdbc.queryForList(anyString(), eq(String.class), eq("image"), eq(false)))
        .thenReturn(List.of());

    assertThat(consumers.drained("image", false)).isTrue();
  }

  @Test
  @DisplayName("A healthy worker bound to job.video.* drains the video category")
  void healthyWorkerDrainsVideo() {
    videoQueue();
    healthyBindings("[\"job.video.*\", \"job.compose.*\"]");

    assertThat(consumers.drained("video", false)).isTrue();
  }

  @Test
  @DisplayName("No healthy worker (all stale, or none registered): the video queue is not drained")
  void nobodyHealthy() {
    videoQueue();
    healthyBindings();

    assertThat(consumers.drained("video", false)).isFalse();
  }

  @Test
  @DisplayName(
      "A worker bound to other queues does not drain video; a malformed row never breaks the answer")
  void otherBindingsAndMalformedRows() {
    videoQueue();
    healthyBindings("not json", "[\"job.compose.*\"]");

    assertThat(consumers.drained("video", false)).isFalse();
  }

  @Test
  @DisplayName(
      "A database not migrated yet (no table) means nothing is known: nothing is greyed out")
  void unreadableRegistryGreysNothingOut() {
    when(jdbc.queryForList(anyString(), eq(String.class), eq("video"), eq(false)))
        .thenThrow(new BadSqlGrammarException("q", "s", new SQLException("no table")));

    assertThat(consumers.drained("video", false)).isTrue();
  }

  @Test
  @DisplayName(
      "A hosted model has its own queue (data); a category with no hosted row falls back to the self-hosted one")
  void hostedQueueIsItsOwnWithAFallback() {
    when(jdbc.queryForList(anyString(), eq(String.class), eq("video"), eq(true)))
        .thenReturn(List.of("job.hostedvideo.generate"));
    when(jdbc.queryForList(anyString(), eq(String.class), eq("music"), eq(true)))
        .thenReturn(List.of());
    when(jdbc.queryForList(anyString(), eq(String.class), eq("music"), eq(false)))
        .thenReturn(List.of("job.sound.generate"));
    healthyBindings("[\"job.hostedvideo.*\"]");

    assertThat(consumers.queueOf("video", true)).contains("job.hostedvideo.generate");
    assertThat(consumers.drained("video", true)).as("the hosted worker is alive").isTrue();
    assertThat(consumers.queueOf("music", true)).contains("job.sound.generate");
    assertThat(consumers.drained("music", true)).as("a GPU-side queue nobody drains").isFalse();
  }
}
