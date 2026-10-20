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
    when(jdbc.queryForList(anyString(), eq(String.class), eq("video")))
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
    when(jdbc.queryForList(anyString(), eq(String.class), eq("image"))).thenReturn(List.of());

    assertThat(consumers.drained("image")).isTrue();
  }

  @Test
  @DisplayName("A healthy worker bound to job.video.* drains the video category")
  void healthyWorkerDrainsVideo() {
    videoQueue();
    healthyBindings("[\"job.video.*\", \"job.compose.*\"]");

    assertThat(consumers.drained("video")).isTrue();
  }

  @Test
  @DisplayName("No healthy worker (all stale, or none registered): the video queue is not drained")
  void nobodyHealthy() {
    videoQueue();
    healthyBindings();

    assertThat(consumers.drained("video")).isFalse();
  }

  @Test
  @DisplayName(
      "A worker bound to other queues does not drain video; a malformed row never breaks the answer")
  void otherBindingsAndMalformedRows() {
    videoQueue();
    healthyBindings("not json", "[\"job.compose.*\"]");

    assertThat(consumers.drained("video")).isFalse();
  }

  @Test
  @DisplayName(
      "A database not migrated yet (no table) means nothing is known: nothing is greyed out")
  void unreadableRegistryGreysNothingOut() {
    when(jdbc.queryForList(anyString(), eq(String.class), eq("video")))
        .thenThrow(new BadSqlGrammarException("q", "s", new SQLException("no table")));

    assertThat(consumers.drained("video")).isTrue();
  }
}
