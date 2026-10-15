package com.krizaka.orazaka.jobservice.application.service;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.jobservice.infrastructure.support.AssetFileResolver;
import com.krizaka.orazaka.jobservice.infrastructure.support.PathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Gives a worker on another host the starting image of a job it was sent (ADR-077).
 *
 * <p>The counterpart of {@link WorkerOutputService}: a Studio step that animates a photo names it
 * by asset id, this service resolved it to a file of the run's actor at receipt and archived the
 * job's input, and a GPU droplet shares none of that disk. The worker asks for the image here
 * instead; the file is read through the platform's key (the droplet never holds it) and handed over
 * in the clear over TLS, authenticated by a {@code SERVICE} token.
 *
 * <p><b>Ownership is the path.</b> The image is the one the job's own archived input names, and it
 * is served only when it lies inside {@code {uploadDir}/{userId}/} — the actor the worker relays. A
 * job id or an actor that matches nothing is "not found", the same answer as an image that never
 * existed.
 */
@Service
public class WorkerInputService {

  private static final Logger logger = LoggerFactory.getLogger(WorkerInputService.class);

  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  /** A starting image is a photo, not a film: headroom for a 24 MP JPEG, nothing more. */
  static final long MAX_BYTES = 64L * 1024 * 1024;

  private final EncryptedAssetService encryptedAssetService;
  private final ObjectMapper objectMapper;
  private final String uploadDir;

  public WorkerInputService(
      EncryptedAssetService encryptedAssetService,
      ObjectMapper objectMapper,
      @Value("${spring.servlet.multipart.location:var/orazaka-uploads}") String uploadDir) {
    this.encryptedAssetService =
        Objects.requireNonNull(encryptedAssetService, "EncryptedAssetService cannot be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
    this.uploadDir = Objects.requireNonNull(uploadDir, "uploadDir cannot be null");
  }

  /**
   * The starting image of a job, in the clear.
   *
   * @param jobId the job the worker was sent
   * @param userId the run's actor (the worker relays the one its job command carried)
   * @return the image bytes, or empty when this actor's job names no readable image
   * @throws IllegalArgumentException a malformed id — the caller's
   */
  public Optional<byte[]> startingImage(String jobId, String userId) {
    if (jobId == null || !ID.matcher(jobId).matches()) {
      throw new IllegalArgumentException("malformed job id");
    }
    if (userId == null || !ID.matcher(userId).matches()) {
      throw new IllegalArgumentException("malformed actor id");
    }
    try {
      Path root = PathResolver.resolve(uploadDir).toAbsolutePath().normalize();
      Path ownerRoot = root.resolve(userId).normalize();
      Path input = ownerRoot.resolve(jobId).resolve("input").resolve("input.json").normalize();
      if (!input.startsWith(ownerRoot) || !Files.isRegularFile(input)) {
        return Optional.empty();
      }
      Map<?, ?> payload =
          objectMapper.readValue(encryptedAssetService.readAllBytes(input, true), Map.class);
      Optional<Path> image = namedImage(payload, ownerRoot);
      if (image.isEmpty() || Files.size(image.get()) > MAX_BYTES) {
        return Optional.empty();
      }
      byte[] bytes = encryptedAssetService.readAllBytes(image.get(), true);
      logger.info(
          "Handed {} bytes of the starting image of job {} to a worker", bytes.length, jobId);
      return Optional.of(bytes);
    } catch (IOException unreadable) {
      logger.warn("Starting image of job {} unreadable: {}", jobId, unreadable.getMessage());
      return Optional.empty();
    }
  }

  /**
   * A photo the actor uploaded, by its asset id, in the clear.
   *
   * <p>A Studio step that animates a photo is dispatched straight to the worker, so no job input is
   * archived and the id is never resolved to a path: the worker names the asset it was given and
   * this service resolves it against the actor's own files, exactly as {@link AssetFileResolver}
   * does for every other capability (ownership is the path; another actor's id is "not found").
   *
   * @param userId the run's actor
   * @param assetId the asset id (a UUID)
   * @return the image bytes, or empty when this actor has no such readable asset
   * @throws IllegalArgumentException a malformed actor id
   */
  public Optional<byte[]> uploadedImage(String userId, String assetId) {
    if (userId == null || !ID.matcher(userId).matches()) {
      throw new IllegalArgumentException("malformed actor id");
    }
    if (assetId == null || !ID.matcher(assetId).matches()) {
      throw new IllegalArgumentException("malformed asset id");
    }
    try {
      Optional<java.io.File> file =
          AssetFileResolver.resolve(PathResolver.resolveToString(uploadDir), userId, assetId);
      if (file.isEmpty() || file.get().length() > MAX_BYTES) {
        return Optional.empty();
      }
      byte[] bytes = encryptedAssetService.readAllBytes(file.get().toPath(), true);
      logger.info("Handed {} bytes of an uploaded image to a worker", bytes.length);
      return Optional.of(bytes);
    } catch (IOException unreadable) {
      logger.warn("Uploaded image unreadable: {}", unreadable.getMessage());
      return Optional.empty();
    }
  }

  /** The file the archived input names (resolved by the listener), if it is the actor's own. */
  private static Optional<Path> namedImage(Map<?, ?> payload, Path ownerRoot) {
    for (String key : new String[] {"imagePath", "image"}) {
      if (payload.get(key) instanceof String value && !value.isBlank() && value.startsWith("/")) {
        Path candidate = Path.of(value).toAbsolutePath().normalize();
        if (candidate.startsWith(ownerRoot) && Files.isRegularFile(candidate)) {
          return Optional.of(candidate);
        }
      }
    }
    return Optional.empty();
  }
}
