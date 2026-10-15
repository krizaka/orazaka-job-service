package com.krizaka.orazaka.jobservice.infrastructure.support;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.assets.domain.port.AssetKeys;
import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Saves generated media bytes to the user's job output directory.
 *
 * <p>Replaces the former static {@code JobMediaHelper.saveMediaToFile(...)}: file-writing
 * orchestration is an injectable component rather than a static utility [ERR-127].
 */
@Component
public class MediaFileStore {

  private final EncryptedAssetService encryptedAssetService;

  private final DurableAssetStore durableAssetStore;
  private final MediaStorageProperties storage;

  /**
   * @param encryptedAssetService seals what this store writes; there is no plaintext branch
   * @param durableAssetStore the second copy that survives the container (none on a local-disk
   *     deployment)
   * @param storage the tenant segment of the durable key
   */
  public MediaFileStore(
      EncryptedAssetService encryptedAssetService,
      DurableAssetStore durableAssetStore,
      MediaStorageProperties storage) {
    this.encryptedAssetService =
        Objects.requireNonNull(encryptedAssetService, "EncryptedAssetService required");
    this.durableAssetStore =
        Objects.requireNonNull(durableAssetStore, "DurableAssetStore required");
    this.storage = Objects.requireNonNull(storage, "MediaStorageProperties required");
  }

  private static final Logger logger = LoggerFactory.getLogger(MediaFileStore.class);

  /**
   * Saves media bytes to {@code <uploadDir>/<userId>/<jobId>/output/<filename>} and returns the
   * public URL path, or an empty string if the data is null/empty or the write fails.
   *
   * @param uploadDir the base upload directory
   * @param userId the user ID
   * @param jobId the job ID
   * @param data the raw media bytes
   * @param filename the output filename (e.g. "video.mp4", "image.png")
   * @return the relative URL path, or empty string on no-op/failure
   */
  public String save(String uploadDir, String userId, String jobId, byte[] data, String filename) {
    if (data == null || data.length == 0) {
      return "";
    }
    try {
      Path root = PathResolver.resolve(uploadDir).toAbsolutePath().normalize();
      Path dir = root.resolve(userId).resolve(jobId).resolve("output");

      Path filePath = dir.resolve(filename).normalize();
      // Containment before the write, not after. Today `filename` is a literal and the ids come
      // off the broker, so this is not reachable — but it guards a *write* primitive, and the
      // cost of the guarantee not depending on every future caller is one comparison.
      if (!filePath.startsWith(root)) {
        logger.error("Refused to write outside the upload root: {}", filePath);
        return "";
      }

      Files.createDirectories(dir);
      // Encrypted at rest (ADR-054). The envelope is written atomically into place, so a process
      // killed mid-write leaves no half-sealed file for the next reader to fail on.
      encryptedAssetService.write(filePath, data);
      if (durableAssetStore.enabled()) {
        // The sealed envelope, not the plaintext. A copy that could not be made is a failed save:
        // an output reported stored here and gone at the next deploy is the bug this prevents.
        durableAssetStore.put(
            AssetKeys.jobFile(storage.tenant(), userId, jobId, filename), filePath);
      }

      logger.info("Saved generated media file to: {}", filePath);
      // The owner is deliberately absent from the URL. It is read from the job record when the
      // asset is served, so the link cannot carry — or be edited to carry — an authorisation claim.
      return "/api/v1/assets/" + jobId + "/" + filename;
    } catch (IOException | IllegalArgumentException e) {
      logger.error("Failed to save generated media to file", e);
      return "";
    }
  }
}
