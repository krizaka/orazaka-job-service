package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.assets.infrastructure.config.AssetEncryptionProperties;
import com.krizaka.orazaka.jobs.domain.exception.JobExecutionException;
import com.krizaka.orazaka.jobs.domain.model.JobCommand;
import com.krizaka.orazaka.jobs.domain.port.JobExecutor;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * Shared file loading for the executors that read an uploaded asset.
 *
 * <p>This used to be a {@code default} method on the sealed strategy interface. It moved here when
 * that interface became the Tier-1 {@link JobExecutor}: reading a file is <b>implementation</b>,
 * and a Tier-1 port must not do I/O (ADR-038). An out-of-tree executor gets the contract without
 * inheriting this service's idea of where files live.
 *
 * <p>A base class rather than a static helper because it carries no state and no dependency: the
 * three media executors extend it to share one method, which is exactly what inheritance is cheap
 * for. It is deliberately <b>not</b> a {@code *Util} — [ERR-127] bans those, and a static method
 * taking the message would be one.
 */
abstract class MediaJobExecutor implements JobExecutor {

  private final EncryptedAssetService encryptedAssetService;
  private final boolean acceptPlaintext;

  /**
   * @param encryptedAssetService opens the asset a job points at
   * @param encryption the cutover tolerance; an unconverted file is readable only while it is on
   */
  protected MediaJobExecutor(
      EncryptedAssetService encryptedAssetService, AssetEncryptionProperties encryption) {
    this.encryptedAssetService =
        Objects.requireNonNull(encryptedAssetService, "EncryptedAssetService required");
    this.acceptPlaintext = encryption.acceptPlaintext();
  }

  /**
   * Reads the asset a job points at.
   *
   * @param command the job, whose payload must carry {@code filePath}
   * @param mediaType a label for the error message, e.g. {@code Audio}
   * @return the file's bytes
   * @throws JobExecutionException when the path is absent, the file is missing, or the read fails
   */
  protected byte[] extractFileBytes(JobCommand command, String mediaType)
      throws JobExecutionException {
    String filePath = command.filePath();
    if (filePath == null) {
      throw new JobExecutionException("Payload does not contain filePath field");
    }
    File file = new File(filePath);
    if (!file.exists()) {
      throw new JobExecutionException(mediaType + " file not found at " + filePath);
    }
    try {
      // Through the store, so an encrypted input opens and a plaintext one is refused once the
      // migration tolerance is off (ADR-054 §5).
      return encryptedAssetService.readAllBytes(file.toPath(), acceptPlaintext);
    } catch (IOException e) {
      throw new JobExecutionException(
          "Failed to read " + mediaType.toLowerCase(Locale.ROOT) + " file: " + e.getMessage(), e);
    }
  }
}
