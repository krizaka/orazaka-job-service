package com.krizaka.orazaka.jobservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Retention deletes the durable copy by prefix, and keeps the row when the store refuses. */
class JobRetentionDurableTest {

  private static final MediaStorageProperties TENANT =
      new MediaStorageProperties("bunny", "z", "k", "e", null, "acme");

  @TempDir Path root;

  @SuppressWarnings("unchecked")
  private JdbcTemplate jdbcWithOneExpiredSensitiveJob() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("SENSITIVE"), any()))
        .thenReturn(List.of(Map.entry("job-1", "user-1")));
    return jdbc;
  }

  @Test
  void aPurgedJobsDurablePrefixIsDeleted_thenItsRow() throws IOException {
    DurableAssetStore durable = mock(DurableAssetStore.class);
    when(durable.enabled()).thenReturn(true);
    JdbcTemplate jdbc = jdbcWithOneExpiredSensitiveJob();

    int purged = new JobRetentionService(jdbc, durable, TENANT).purgeExpired(root);

    assertThat(purged).isEqualTo(1);
    verify(durable).delete("t/acme/u/user-1/jobs/job-1/");
    verify(jdbc).update(anyString(), eq("job-1"), eq("SENSITIVE"));
  }

  @Test
  void whenTheStoreRefuses_theRowIsKeptForTheNextSweep() throws IOException {
    DurableAssetStore durable = mock(DurableAssetStore.class);
    when(durable.enabled()).thenReturn(true);
    org.mockito.Mockito.doThrow(new IOException("HTTP 500")).when(durable).delete(anyString());
    JdbcTemplate jdbc = jdbcWithOneExpiredSensitiveJob();

    int purged = new JobRetentionService(jdbc, durable, TENANT).purgeExpired(root);

    assertThat(purged).isZero();
    verify(jdbc, never()).update(anyString(), eq("job-1"), eq("SENSITIVE"));
  }
}
