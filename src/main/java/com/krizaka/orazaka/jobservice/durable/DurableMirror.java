package com.krizaka.orazaka.jobservice.durable;

import com.krizaka.orazaka.assets.domain.port.AssetKeys;
import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.domain.port.StoredObject;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import com.krizaka.orazaka.assets.infrastructure.storage.NoDurableAssetStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The durable second copy of what a job reads and writes besides its media: the archived input
 * ({@code input.json}, the input file), the result ({@code result.json}) and the files a user
 * uploaded. A redeploy wipes the container's disk; these are fetched back, sealed as they were
 * written, when a read finds the file gone.
 *
 * <p>Ownership is the key: every key starts with the actor's own prefix, so an id that belongs to
 * somebody else is simply not there. A copy that could not be made is logged, never thrown — the
 * job already ran, and failing it would lose the work to keep an archive.
 */
@Component
public class DurableMirror {

  private static final Logger logger = LoggerFactory.getLogger(DurableMirror.class);

  private static final Pattern ASSET_ID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private final DurableAssetStore store;
  private final MediaStorageProperties storage;

  public DurableMirror(DurableAssetStore store, MediaStorageProperties storage) {
    this.store = Objects.requireNonNull(store, "DurableAssetStore cannot be null");
    this.storage = Objects.requireNonNull(storage, "MediaStorageProperties cannot be null");
  }

  /** A mirror that keeps nothing: the local-disk deployment, and most unit tests. */
  public static DurableMirror localOnly() {
    return new DurableMirror(
        new NoDurableAssetStore(),
        new MediaStorageProperties("local", null, null, "storage.bunnycdn.com", null, "default"));
  }

  /** Copies a sealed job file (named by its file name) to the durable store; best effort. */
  public void put(String userId, String jobId, Path sealed) {
    if (!store.enabled()) {
      return;
    }
    try {
      store.put(
          AssetKeys.jobFile(storage.tenant(), userId, jobId, sealed.getFileName().toString()),
          sealed);
    } catch (IOException | IllegalArgumentException e) {
      logger.error("Could not keep a durable copy of {}: {}", sealed.getFileName(), e.getMessage());
    }
  }

  /** Brings a job file back onto the disk when it is gone; {@code true} when it is there now. */
  public boolean restore(String userId, String jobId, Path target) {
    if (!store.enabled()) {
      return false;
    }
    try {
      return store.fetch(
          AssetKeys.jobFile(storage.tenant(), userId, jobId, target.getFileName().toString()),
          target);
    } catch (IOException | IllegalArgumentException e) {
      logger.warn("Durable store could not restore {}: {}", target.getFileName(), e.getMessage());
      return false;
    }
  }

  /** Brings an upload back to exactly this path (an archived payload names it); idempotent. */
  public boolean restoreUploadFile(String userId, Path target) {
    if (!store.enabled()) {
      return false;
    }
    try {
      return store.fetch(
          AssetKeys.uploadFile(storage.tenant(), userId, target.getFileName().toString()), target);
    } catch (IOException | IllegalArgumentException e) {
      logger.warn("Durable store could not restore an upload: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Brings one of the actor's own uploads back to {@code <uploadDir>/<userId>/temp/}, found by its
   * asset id (the stored name is {@code <assetId><extension>}).
   */
  public Optional<File> restoreUpload(String uploadDir, String userId, String assetId) {
    if (!store.enabled() || assetId == null || !ASSET_ID.matcher(assetId).matches()) {
      return Optional.empty();
    }
    try {
      String prefix = AssetKeys.uploadPrefix(storage.tenant(), userId);
      List<StoredObject> uploads = store.list(prefix);
      for (StoredObject upload : uploads) {
        String name = upload.key().substring(prefix.length());
        if (upload.directory() || !name.startsWith(assetId)) {
          continue;
        }
        Path target = Path.of(uploadDir, userId, "temp", name).toAbsolutePath().normalize();
        if (store.fetch(upload.key(), target)) {
          logger.info("Restored upload {} from the durable store", assetId);
          return Optional.of(target.toFile());
        }
      }
    } catch (IOException | IllegalArgumentException e) {
      logger.warn("Durable store could not restore upload {}: {}", assetId, e.getMessage());
    }
    return Optional.empty();
  }

  /**
   * Restores every upload of the actor a payload names (an id, or a list of them) that the disk no
   * longer has, so the resolver that follows finds them.
   *
   * @param onDisk whether an asset id already resolves to a file on this disk (the caller's own
   *     resolver: this package depends on nothing of the service)
   */
  public void restoreUploadsNamedBy(
      String uploadDir, String userId, Object value, Predicate<String> onDisk) {
    if (!store.enabled() || userId == null) {
      return;
    }
    if (value instanceof String candidate && ASSET_ID.matcher(candidate).matches()) {
      if (!onDisk.test(candidate)) {
        restoreUpload(uploadDir, userId, candidate);
      }
    } else if (value instanceof List<?> items) {
      items.forEach(item -> restoreUploadsNamedBy(uploadDir, userId, item, onDisk));
    } else if (value instanceof java.util.Map<?, ?> map) {
      map.values().forEach(item -> restoreUploadsNamedBy(uploadDir, userId, item, onDisk));
    }
  }
}
