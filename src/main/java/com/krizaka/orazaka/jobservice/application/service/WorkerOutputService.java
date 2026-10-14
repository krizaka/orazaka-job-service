package com.krizaka.orazaka.jobservice.application.service;

import com.krizaka.orazaka.jobs.domain.model.DataClass;
import com.krizaka.orazaka.jobservice.infrastructure.support.MediaFileStore;
import com.krizaka.orazaka.persistence.domain.model.JobDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Takes a finished output from a worker that does not share the platform's disk.
 *
 * <p>A worker on the Mac writes into the upload directory the services serve from. A worker on an
 * hourly GPU droplet cannot: its disk is destroyed with it, and was never readable by the asset
 * route anyway. It hands the file over here instead, and this service does the three things that
 * make it a readable asset (ADR-077, orazaka-worker-media#3):
 *
 * <ol>
 *   <li><b>adopts the job row</b> when there is none — a Studio step drained by a worker never had
 *       one, which is why its video could be produced, billed and never opened. The owner is the
 *       row's when the row exists; only an absent row takes the run's actor the worker relays, the
 *       same identity the job command carried (and a worker holds a {@code SERVICE} token, ADR-035);
 *   <li><b>seals it</b> with the platform's key ({@link MediaFileStore}, ADR-054) — the droplet never
 *       holds that key;
 *   <li>answers the URL the asset is served under, {@code /api/v1/assets/{jobId}/{filename}}.
 * </ol>
 */
@Service
public class WorkerOutputService {

  private static final Logger logger = LoggerFactory.getLogger(WorkerOutputService.class);

  /** An output's name: one word, never a path. */
  private static final Pattern FILENAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

  /** A job id as the platform mints them (UUIDs), and nothing that could be a path. */
  private static final Pattern JOB_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  /** The largest output accepted: a 20 s 720p clip is ~20 MB; this is headroom, not a target. */
  static final int MAX_BYTES = 256 * 1024 * 1024;

  private final JobPersistenceProvider jobPersistenceProvider;
  private final MediaFileStore mediaFileStore;
  private final String uploadDir;

  public WorkerOutputService(
      JobPersistenceProvider jobPersistenceProvider,
      MediaFileStore mediaFileStore,
      @Value("${spring.servlet.multipart.location:var/orazaka-uploads}") String uploadDir) {
    this.jobPersistenceProvider =
        Objects.requireNonNull(jobPersistenceProvider, "JobPersistenceProvider cannot be null");
    this.mediaFileStore = Objects.requireNonNull(mediaFileStore, "MediaFileStore cannot be null");
    this.uploadDir = Objects.requireNonNull(uploadDir, "uploadDir cannot be null");
  }

  /**
   * Stores one output of a job and returns the URL it is served under.
   *
   * @param jobId the job that produced it
   * @param filename its name within the job's output ({@code video.mp4})
   * @param data the plaintext bytes
   * @param actorId the run's actor, used only when the job has no row yet
   * @param featureKey the job's capability, used only when the job has no row yet
   * @param dataClass the job's declared data class, required to adopt a row (ADR-065: never
   *     defaulted)
   * @return {@code /api/v1/assets/{jobId}/{filename}}
   * @throws IllegalArgumentException a malformed id, name, size or declaration — the caller's
   * @throws IllegalStateException the file could not be written
   */
  public String store(
      String jobId,
      String filename,
      byte[] data,
      String actorId,
      String featureKey,
      String dataClass) {
    if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
      throw new IllegalArgumentException("malformed job id");
    }
    if (filename == null || !FILENAME.matcher(filename).matches() || filename.contains("..")) {
      throw new IllegalArgumentException("an output name is one word, never a path");
    }
    if (data == null || data.length == 0) {
      throw new IllegalArgumentException("an output cannot be empty");
    }
    if (data.length > MAX_BYTES) {
      throw new IllegalArgumentException("an output cannot exceed " + MAX_BYTES + " bytes");
    }

    String owner = ownerOf(jobId, actorId, featureKey, dataClass);
    String url = mediaFileStore.save(uploadDir, owner, jobId, data, filename);
    if (url == null || url.isEmpty()) {
      throw new IllegalStateException("the output of job " + jobId + " could not be written");
    }
    logger.info("Stored {} bytes of {}/{} handed over by a worker", data.length, jobId, filename);
    return url;
  }

  private String ownerOf(String jobId, String actorId, String featureKey, String dataClass) {
    Optional<JobDto> existing = jobPersistenceProvider.getJob(jobId);
    if (existing.isPresent()) {
      return existing.get().userId();
    }
    if (isBlank(actorId) || isBlank(featureKey)) {
      throw new IllegalArgumentException(
          "job " + jobId + " has no row: the actor and the capability are needed to adopt it");
    }
    DataClass declared = parse(dataClass);
    jobPersistenceProvider.createJob(jobId, actorId, featureKey, Map.of(), declared);
    logger.info("Adopted worker-drained job {} for feature {}", jobId, featureKey);
    return actorId;
  }

  private static DataClass parse(String value) {
    if (isBlank(value)) {
      throw new IllegalArgumentException("a job's data class is declared, never defaulted");
    }
    try {
      return DataClass.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("unknown data class " + value, unknown);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
