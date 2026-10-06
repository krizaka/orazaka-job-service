package com.orazaka.jobservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the sweep may delete. Its effect on the real schema and the real files is {@link
 * ProtectedJobRetentionIT}; this is the part a database cannot show — which directory a row's ids
 * are allowed to name.
 */
class JobRetentionServiceTest {

  private static final Path ROOT = Path.of("/srv/uploads");

  @Test
  @DisplayName("a job's directory is <root>/<actor>/<job>")
  void aJobDirectoryIsTheActorThenTheJob() {
    assertThat(JobRetentionService.jobDirectory(ROOT, "actor-1", "job-1"))
        .contains(Path.of("/srv/uploads/actor-1/job-1"));
  }

  @Test
  @DisplayName("ids that climb, nest or are absent name no directory at all")
  void idsThatAreNotOneSegmentNameNothing() {
    assertThat(JobRetentionService.jobDirectory(ROOT, "..", "uploads")).isEmpty();
    assertThat(JobRetentionService.jobDirectory(ROOT, "actor-1", "..")).isEmpty();
    assertThat(JobRetentionService.jobDirectory(ROOT, "actor-1", "a/../../etc")).isEmpty();
    assertThat(JobRetentionService.jobDirectory(ROOT, "actor-1", "a\\b")).isEmpty();
    assertThat(JobRetentionService.jobDirectory(ROOT, "", "job-1")).isEmpty();
    assertThat(JobRetentionService.jobDirectory(ROOT, null, "job-1")).isEmpty();
    assertThat(JobRetentionService.jobDirectory(ROOT, "actor-1", ".")).isEmpty();
  }

  @Test
  @DisplayName("a STANDARD job is never selected: the protected window is the only one this sweeps")
  void onlyProtectedClassesAreSelected() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    new JobRetentionService(jdbc).purgeExpired(ROOT);

    verify(jdbc, never())
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
            org.mockito.ArgumentMatchers.eq("STANDARD"),
            org.mockito.ArgumentMatchers.any());
    verify(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
            org.mockito.ArgumentMatchers.eq("SENSITIVE"),
            org.mockito.ArgumentMatchers.eq(0L));
    verify(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
            org.mockito.ArgumentMatchers.eq("REGULATED"),
            org.mockito.ArgumentMatchers.eq(0L));
  }
}
