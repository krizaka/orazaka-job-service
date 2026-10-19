package com.krizaka.orazaka.jobservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import com.krizaka.orazaka.assets.infrastructure.storage.DirectoryAssetStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

  @Test
  @SuppressWarnings("unchecked")
  void withARetentionWindowAnOrdinaryJobIsPurgedToo_files_durableCopy_thenRow() throws IOException {
    DurableAssetStore durable = mock(DurableAssetStore.class);
    when(durable.enabled()).thenReturn(true);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("STANDARD"), eq(30L * 86_400)))
        .thenReturn(List.of(Map.entry("job-9", "user-9")));

    int purged = new JobRetentionService(jdbc, durable, TENANT, 30).purgeExpired(root);

    assertThat(purged).isEqualTo(1);
    verify(durable).delete("t/acme/u/user-9/jobs/job-9/");
    verify(jdbc).update(anyString(), eq("job-9"), eq("STANDARD"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void withoutAWindowAnOrdinaryJobIsKept() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    int purged =
        new JobRetentionService(jdbc, mock(DurableAssetStore.class), TENANT, 0).purgeExpired(root);

    assertThat(purged).isZero();
    verify(jdbc, never()).query(anyString(), any(RowMapper.class), eq("STANDARD"), any());
  }

  @Test
  void uploadsOlderThanTheWindowAreDeleted_freshOnesAndOtherWindowsKept() throws IOException {
    Path store = root.resolve("durable");
    DurableAssetStore durable = new DirectoryAssetStore(store);
    Path src = root.resolve("src");
    Files.writeString(src, "x");
    durable.put("t/acme/u/u1/jobs/uploads/old.png", src);
    durable.put("t/acme/u/u1/jobs/uploads/new.png", src);
    durable.put("t/acme/u/u2/jobs/uploads/old2.png", src);
    FileTime old = FileTime.from(Instant.now().minus(40, ChronoUnit.DAYS));
    Files.setLastModifiedTime(store.resolve("t/acme/u/u1/jobs/uploads/old.png"), old);
    Files.setLastModifiedTime(store.resolve("t/acme/u/u2/jobs/uploads/old2.png"), old);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT now()", java.sql.Timestamp.class))
        .thenReturn(java.sql.Timestamp.from(Instant.now()));

    int purged = new JobRetentionService(jdbc, durable, TENANT, 30).purgeExpiredUploads();

    assertThat(purged).isEqualTo(2);
    assertThat(store.resolve("t/acme/u/u1/jobs/uploads/old.png")).doesNotExist();
    assertThat(store.resolve("t/acme/u/u2/jobs/uploads/old2.png")).doesNotExist();
    assertThat(store.resolve("t/acme/u/u1/jobs/uploads/new.png")).exists();
  }

  @Test
  void withoutAWindowUploadsAreKept() {
    DurableAssetStore durable = mock(DurableAssetStore.class);
    when(durable.enabled()).thenReturn(true);
    assertThat(
            new JobRetentionService(mock(JdbcTemplate.class), durable, TENANT, 0)
                .purgeExpiredUploads())
        .isZero();
  }
}
