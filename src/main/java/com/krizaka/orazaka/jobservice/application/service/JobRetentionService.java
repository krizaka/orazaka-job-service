package com.krizaka.orazaka.jobservice.application.service;

import com.krizaka.orazaka.assets.domain.port.AssetKeys;
import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.domain.port.StoredObject;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import com.krizaka.orazaka.jobs.domain.model.DataClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Deletes what the job plane may no longer keep: a job's row and its files, on the window its data
 * class chooses (ADR-065).
 *
 * <p>This context purges what it owns. The studio service's {@code RetentionSweeper} deletes runs,
 * and a run purge that also deleted its jobs would cross into this database — which SEAM-001
 * forbids — or ask for it by message, which makes retention depend on a delivery: one lost event
 * and the data survives with nothing to say so. The class travels on the job instead, and the plane
 * that holds the material decides when it goes.
 *
 * <p><b>The file, then the row.</b> The row is the only pointer to the directory; deleted first, a
 * crash before the files leaves an encrypted directory nothing will ever select again. In this
 * order the crash leaves a row whose files are gone, and the next sweep selects that row again,
 * deletes nothing that is not there, and then the row. A tombstone is written before either, so a
 * directory an execution recreates after its purge — a timed-out execution is not cancelled — is
 * deleted again by {@link #reconcileOrphans}.
 *
 * <p><b>The key material.</b> An asset's data key exists only wrapped, inside the header of the
 * file it encrypts (ADR-054). Deleting the file deletes the only copy of that key. The master key
 * survives, because it wraps every other file; what it can no longer open is a file that is not
 * there. The residue is a copy of the bytes somewhere else — a backup, or blocks the volume has not
 * reused — and neither is in reach of this service.
 *
 * <p>Plain parameterised SQL over this service's own table, as {@link WorkerRegistryService} does —
 * no JPA entity outside {@code orazaka-persistence} (AGENTS.md §5).
 */
@Service
public class JobRetentionService {

  private static final Logger logger = LoggerFactory.getLogger(JobRetentionService.class);

  /**
   * How long a tombstone keeps a purged job's directory from coming back. Far beyond the execution
   * timeout, which bounds how late an abandoned execution can plausibly still write.
   */
  static final Duration TOMBSTONE_HORIZON = Duration.ofDays(7);

  private final JdbcTemplate jdbcTemplate;
  private final DurableAssetStore durableAssetStore;
  private final MediaStorageProperties storage;
  private final int ordinaryRetentionDays;

  /** Keeps ordinary jobs forever: the window is opt-in, {@code MEDIA_RETENTION_DAYS}. */
  public JobRetentionService(
      JdbcTemplate jdbcTemplate,
      DurableAssetStore durableAssetStore,
      MediaStorageProperties storage) {
    this(jdbcTemplate, durableAssetStore, storage, 0);
  }

  /**
   * @param ordinaryRetentionDays days an ordinary (STANDARD) job and its files are kept after it
   *     ends; {@code 0} keeps them. Data, not code: a dev environment sets 30.
   */
  @Autowired
  public JobRetentionService(
      JdbcTemplate jdbcTemplate,
      DurableAssetStore durableAssetStore,
      MediaStorageProperties storage,
      @Value("${media.retention.days:0}") int ordinaryRetentionDays) {
    this.ordinaryRetentionDays = Math.max(0, ordinaryRetentionDays);
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.durableAssetStore =
        Objects.requireNonNull(durableAssetStore, "DurableAssetStore cannot be null");
    this.storage = Objects.requireNonNull(storage, "MediaStorageProperties cannot be null");
  }

  /** Deletes the durable copy of a job's files; {@code false} when the store refused. */
  private boolean deleteDurable(String userId, String jobId) {
    if (!durableAssetStore.enabled() || userId.isEmpty()) {
      return true;
    }
    try {
      durableAssetStore.delete(AssetKeys.jobPrefix(storage.tenant(), userId, jobId));
      return true;
    } catch (IOException | IllegalArgumentException e) {
      logger.error("Could not delete a purged job's durable copy; its row is kept", e);
      return false;
    }
  }

  /**
   * Purges every job whose class's window has closed since it reached a terminal state.
   *
   * <p>Not transactional, and deliberately: each statement commits on its own, so the tombstone
   * exists before the files go and the row outlives them.
   *
   * @param uploadRoot the root job directories live under, as the executor resolves it
   * @return how many jobs were purged
   */
  public int purgeExpired(Path uploadRoot) {
    Objects.requireNonNull(uploadRoot, "uploadRoot cannot be null");
    int purged = 0;
    for (DataClass dataClass : DataClass.values()) {
      Optional<Duration> window = dataClass.jobRetentionAfterTerminal();
      if (window.isEmpty() && ordinaryRetentionDays > 0) {
        window = Optional.of(Duration.ofDays(ordinaryRetentionDays));
      }
      if (window.isEmpty()) {
        continue;
      }
      for (Map.Entry<String, String> job : expired(dataClass, window.get())) {
        if (purge(uploadRoot, dataClass, job.getKey(), job.getValue())) {
          purged++;
        }
      }
    }
    if (purged > 0) {
      logger.info("Retention purged {} job(s): rows and files", purged);
    }
    return purged;
  }

  /**
   * Deletes the durable copy of every upload older than the ordinary retention window. Uploads
   * belong to no job, so the job purge never reaches them. A no-op without a window or a store.
   *
   * @return how many uploads were deleted
   */
  public int purgeExpiredUploads() {
    if (ordinaryRetentionDays <= 0 || !durableAssetStore.enabled()) {
      return 0;
    }
    java.sql.Timestamp now = jdbcTemplate.queryForObject("SELECT now()", java.sql.Timestamp.class);
    if (now == null) {
      return 0;
    }
    Instant cutoff = now.toInstant().minus(Duration.ofDays(ordinaryRetentionDays));
    int purged = 0;
    try {
      for (StoredObject user :
          durableAssetStore.list(AssetKeys.tenantUsersPrefix(storage.tenant()))) {
        if (!user.directory()) {
          continue;
        }
        for (StoredObject upload : durableAssetStore.list(user.key() + "jobs/uploads/")) {
          if (!upload.directory() && upload.lastModified().isBefore(cutoff)) {
            durableAssetStore.delete(upload.key());
            purged++;
          }
        }
      }
    } catch (IOException e) {
      logger.warn("Upload retention stopped, the next sweep resumes: {}", e.getMessage());
    }
    if (purged > 0) {
      logger.info("Retention deleted {} expired upload(s)", purged);
    }
    return purged;
  }

  /**
   * Deletes, again, the directory of a purged job that something wrote to afterwards, and forgets
   * tombstones older than {@link #TOMBSTONE_HORIZON}.
   *
   * @param uploadRoot the root job directories live under
   * @return how many directories had come back and were deleted
   */
  public int reconcileOrphans(Path uploadRoot) {
    Objects.requireNonNull(uploadRoot, "uploadRoot cannot be null");
    List<Map.Entry<String, String>> tombstones =
        jdbcTemplate.query(
            "SELECT job_id, user_id FROM orazaka_job_purge"
                + " WHERE purged_at > now() - make_interval(secs => ?)",
            (rs, rowNum) -> Map.entry(rs.getString(1), nullToEmpty(rs.getString(2))),
            TOMBSTONE_HORIZON.toSeconds());
    int returned = 0;
    for (Map.Entry<String, String> tombstone : tombstones) {
      Optional<Path> directory = jobDirectory(uploadRoot, tombstone.getValue(), tombstone.getKey());
      if (directory.isPresent()
          && Files.exists(directory.get())
          && deleteDurable(tombstone.getValue(), tombstone.getKey())
          && delete(directory.get())) {
        logger.warn(
            "Job {} was written to after its purge; its directory was deleted again",
            tombstone.getKey());
        returned++;
      }
    }
    jdbcTemplate.update(
        "DELETE FROM orazaka_job_purge WHERE purged_at <= now() - make_interval(secs => ?)",
        TOMBSTONE_HORIZON.toSeconds());
    return returned;
  }

  private List<Map.Entry<String, String>> expired(DataClass dataClass, Duration window) {
    return jdbcTemplate.query(
        "SELECT id, user_id FROM orazaka_jobs"
            + " WHERE data_class = ? AND status IN ('COMPLETED', 'FAILED')"
            + " AND updated_at <= now() - make_interval(secs => ?)",
        (rs, rowNum) -> Map.entry(rs.getString(1), nullToEmpty(rs.getString(2))),
        dataClass.name(),
        window.toSeconds());
  }

  private boolean purge(Path uploadRoot, DataClass dataClass, String jobId, String userId) {
    jdbcTemplate.update(
        "INSERT INTO orazaka_job_purge (job_id, user_id) VALUES (?, ?)"
            + " ON CONFLICT (job_id) DO NOTHING",
        jobId,
        userId.isEmpty() ? null : userId);
    if (!deleteDurable(userId, jobId)) {
      return false;
    }
    Optional<Path> directory = jobDirectory(uploadRoot, userId, jobId);
    if (directory.isPresent() && Files.exists(directory.get()) && !delete(directory.get())) {
      // The row stays: it is the only pointer to what could not be deleted, and the next sweep
      // selects it again.
      return false;
    }
    jdbcTemplate.update(
        "DELETE FROM orazaka_jobs WHERE id = ? AND data_class = ?", jobId, dataClass.name());
    return true;
  }

  /**
   * The directory a job's files live in, {@code <root>/<userId>/<jobId>}, or empty when the ids
   * cannot name one.
   *
   * <p>Both ids are single path segments or nothing: a value that climbs or nests would make this
   * sweep delete a directory it does not own. A job with no actor never had a directory — the
   * executor builds the path from the actor id and fails to archive without one.
   */
  static Optional<Path> jobDirectory(Path uploadRoot, String userId, String jobId) {
    if (!isSegment(userId) || !isSegment(jobId)) {
      return Optional.empty();
    }
    Path root = uploadRoot.toAbsolutePath().normalize();
    Path directory = root.resolve(userId).resolve(jobId).normalize();
    return directory.getParent() != null && root.equals(directory.getParent().getParent())
        ? Optional.of(directory)
        : Optional.empty();
  }

  private static boolean isSegment(String value) {
    return value != null
        && !value.isBlank()
        && !".".equals(value)
        && !"..".equals(value)
        && value.indexOf('/') < 0
        && value.indexOf('\\') < 0;
  }

  private static boolean delete(Path directory) {
    try (Stream<Path> walk = Files.walk(directory)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
      return true;
    } catch (IOException | UncheckedIOException e) {
      logger.error(
          "Could not delete a purged job's directory; its row is kept for the next sweep", e);
      return false;
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
