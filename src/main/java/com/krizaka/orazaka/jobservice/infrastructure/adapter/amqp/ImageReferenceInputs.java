package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.core.domain.model.image.ImageReference;
import com.krizaka.orazaka.core.domain.model.image.ImageRequest;
import com.krizaka.orazaka.jobservice.infrastructure.support.AssetFileResolver;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The reference pictures of an image job (krizaka-internal#158), read once at the AMQP boundary
 * (ERR-127): which assets, at which weight, resolved among the acting user's own files.
 *
 * <p>A Studio step renders a list input one element per line, a CLI or API caller may send a JSON
 * array or a comma-separated string: all three read the same. Each id is a bare UUID — an upload or
 * the output of one of the actor's own jobs ({@link AssetFileResolver#resolveOutput}), so another
 * actor's picture is simply not found.
 *
 * @param assetIds the reference asset ids, in the order the model receives them
 * @param weight the weight asked for every reference, {@code 0} to {@code 1}
 */
record ImageReferenceInputs(List<String> assetIds, double weight) {

  private static final Pattern ASSET_ID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static final Pattern SEPARATORS = Pattern.compile("[\\s,]+");

  private static final ImageReferenceInputs NONE =
      new ImageReferenceInputs(List.of(), ImageReference.FULL);

  ImageReferenceInputs {
    assetIds = List.copyOf(assetIds);
    if (assetIds.size() > ImageRequest.MAX_REFERENCES) {
      throw new IllegalArgumentException(
          "at most " + ImageRequest.MAX_REFERENCES + " reference images, was " + assetIds.size());
    }
    for (String id : assetIds) {
      if (!ASSET_ID.matcher(id).matches()) {
        throw new IllegalArgumentException("a reference must be an asset id, was '" + id + "'");
      }
    }
    if (Double.isNaN(weight) || weight < 0 || weight > 1) {
      throw new IllegalArgumentException("referenceWeight must be between 0 and 1");
    }
  }

  /**
   * @param payload the job command's payload ({@code references}, {@code referenceWeight})
   * @return the references asked for, none when the payload names none
   * @throws IllegalArgumentException when a value is present and malformed
   */
  static ImageReferenceInputs from(Map<String, Object> payload) {
    List<String> ids = ids(payload.get("references"));
    Object rawWeight = payload.get("referenceWeight");
    double weight = ImageReference.FULL;
    if (rawWeight instanceof Number number) {
      weight = number.doubleValue();
    } else if (rawWeight != null && !rawWeight.toString().isBlank()) {
      try {
        weight = Double.parseDouble(rawWeight.toString().strip());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "referenceWeight must be a number, was '" + rawWeight + "'", e);
      }
    }
    return ids.isEmpty() && weight == ImageReference.FULL
        ? NONE
        : new ImageReferenceInputs(ids, weight);
  }

  private static List<String> ids(Object value) {
    if (value == null) {
      return List.of();
    }
    Collection<?> items =
        value instanceof Collection<?> list
            ? list
            : Arrays.asList(SEPARATORS.split(value.toString().strip()));
    List<String> ids = new ArrayList<>();
    for (Object item : items) {
      String id = item == null ? "" : item.toString().strip();
      if (!id.isEmpty()) {
        ids.add(id);
      }
    }
    return ids;
  }

  /**
   * @param uploadDir the upload root
   * @param userId the acting user of this job
   * @return the pictures, in order
   * @throws IllegalArgumentException when a reference is not one of the actor's files, or is not a
   *     still picture
   */
  List<ImageReference> load(String uploadDir, String userId) {
    List<ImageReference> references = new ArrayList<>();
    for (String id : assetIds) {
      File file =
          AssetFileResolver.resolveOutput(uploadDir, userId, id)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "the reference image " + id + " is not among your files"));
      byte[] bytes;
      try {
        bytes = Files.readAllBytes(file.toPath());
      } catch (IOException e) {
        throw new IllegalArgumentException("the reference image " + id + " cannot be read", e);
      }
      references.add(new ImageReference(bytes, mediaType(bytes), weight));
    }
    return references;
  }

  private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};
  private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8};
  private static final byte[] RIFF_SIGNATURE = {'R', 'I', 'F', 'F'};
  private static final byte[] WEBP_SIGNATURE = {'W', 'E', 'B', 'P'};
  private static final int RIFF_FORMAT_OFFSET = 8;
  private static final int MIN_PNG_LENGTH = 8;
  private static final int MIN_WEBP_LENGTH = 12;

  /** The type read from the file's first bytes — never from its name, which the user chose. */
  static String mediaType(byte[] bytes) {
    if (bytes.length >= MIN_PNG_LENGTH && startsWith(bytes, 0, PNG_SIGNATURE)) {
      return "image/png";
    }
    if (startsWith(bytes, 0, JPEG_SIGNATURE)) {
      return "image/jpeg";
    }
    if (bytes.length >= MIN_WEBP_LENGTH
        && startsWith(bytes, 0, RIFF_SIGNATURE)
        && startsWith(bytes, RIFF_FORMAT_OFFSET, WEBP_SIGNATURE)) {
      return "image/webp";
    }
    return "application/octet-stream";
  }

  private static boolean startsWith(byte[] bytes, int offset, byte[] signature) {
    return bytes.length >= offset + signature.length
        && Arrays.equals(bytes, offset, offset + signature.length, signature, 0, signature.length);
  }
}
