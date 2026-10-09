package com.krizaka.orazaka.jobservice.infrastructure.support;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves an uploaded asset id to a file <b>inside its owner's directory, and nowhere else</b>.
 *
 * <p>The job service is where this belongs, not the producer. It already holds the upload root
 * ({@code spring.servlet.multipart.location}) and already writes into {@code
 * {uploadDir}/{userId}/{jobId}/} through {@link MediaFileStore}, so resolving here adds no
 * knowledge the module did not have. The Studio context has no upload root at all: giving it one to
 * resolve assets at dispatch would put storage layout into a service that owns a catalogue and a
 * saga, and would couple two contexts by a shared filesystem mount they otherwise do not share
 * (SEAM-001). Resolving at RECEIPT also means every producer gets it — the conversation service's
 * three analyse endpoints resolve assets in their own controller today, and a pack's dispatcher
 * would otherwise have to reimplement it.
 *
 * <p><b>Ownership is the path, and that is deliberate.</b> An {@code assetId} inside a blueprint is
 * user input; treating it as a filename to be looked up anywhere would let one actor's run read
 * another's uploads. Every lookup is rooted at {@code {uploadDir}/{userId}/} for the acting user of
 * <i>this</i> job, so an asset belonging to somebody else is not "denied" — it is not found, which
 * is the same answer as one that never existed and leaks nothing about which it was.
 *
 * <p>Two guards, because one of them is only true by accident. The prefix is matched against {@code
 * listFiles} names, which cannot contain a separator, so {@code ../} in an asset id matches nothing
 * rather than escaping — but that is a property of the matching, not an intention. The containment
 * check below states the intention, so a future change from {@code listFiles} to a path join cannot
 * silently turn this into a traversal.
 *
 * <p>Mirrors the conversation service's resolver of the same name. Duplicated rather than shared
 * because the two hosts share no module below Tier-2, and a Tier-1 contract for "find a file under
 * a directory" would publish the storage layout as an interface.
 */
public final class AssetFileResolver {

  private static final Logger logger = LoggerFactory.getLogger(AssetFileResolver.class);

  /** An asset id is a UUID; nothing else in a payload is mistaken for one. */
  private static final Pattern ASSET_ID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private AssetFileResolver() {}

  /**
   * Resolves an asset id to the acting user's own file.
   *
   * @param uploadDir the root upload directory
   * @param userId the acting user — the run's actor, never a value from the payload
   * @param assetId the asset id, treated as a filename prefix
   * @return the file, or empty when this user has no such asset
   */
  public static Optional<File> resolve(String uploadDir, String userId, Object assetId) {
    if (uploadDir == null || userId == null || userId.isBlank() || assetId == null) {
      return Optional.empty();
    }
    String prefix = assetId.toString();
    if (prefix.isBlank()) {
      return Optional.empty();
    }
    File userDir = new File(uploadDir, userId);

    // temp/ first — freshly uploaded files land there before anything persists them.
    Optional<File> found = findByPrefix(new File(userDir, "temp"), prefix, userDir);
    return found.isPresent() ? found : findByPrefix(userDir, prefix, userDir);
  }

  /**
   * Rewrites every asset id in a payload — scalar or inside a list — to its absolute path.
   *
   * <p><b>Why this exists.</b> {@code resolve} answers for one id under the key {@code assetId},
   * which is all the vision fan-out needed. A composition step passes {@code photos}: a
   * <b>list</b>. Nothing resolved it, so the media worker received ids where it expected paths,
   * fell back to joining them onto the upload root without the owner's directory or the file
   * extension, found no readable file and failed with <i>"compose requires at least one readable
   * photo"</i> — a message that reads like bad user input and was our own gap. {@code
   * realestate-reels} was diagnosed as an environment limitation twice on the strength of it
   * (ADR-046).
   *
   * <p><b>Keyed on the value's shape, not on the key's name.</b> The alternative was a list of
   * asset-bearing keys — {@code photos}, {@code clip}, and whatever the next pack invents — which
   * would put pack vocabulary inside engine code [PACK-002] and be wrong the first time an author
   * chose a different word. An asset id is a UUID, so a UUID that names a file <i>this actor
   * owns</i> is an asset reference and nothing else plausibly is. A prompt is not a bare UUID; a
   * {@code correlationId} is one but names no file, so it resolves to nothing and is left alone.
   *
   * <p>Ownership is unchanged from {@link #resolve}: every lookup is rooted at the acting user's
   * own directory, so an id belonging to somebody else stays an id — the step then fails on the
   * unreadable path rather than reading a stranger's file.
   *
   * @param uploadDir the root upload directory
   * @param userId the acting user — the run's actor, never a value from the payload
   * @param payload the job payload
   * @return a payload with the actor's own asset ids replaced by absolute paths, or the same
   *     instance when it holds none
   */
  public static Map<String, Object> resolvePayload(
      String uploadDir, String userId, Map<String, Object> payload) {
    if (payload == null || payload.isEmpty() || uploadDir == null || userId == null) {
      return payload;
    }
    Map<String, Object> resolved = new LinkedHashMap<>(payload);
    boolean changed = false;
    for (Map.Entry<String, Object> entry : payload.entrySet()) {
      Object rewritten = rewrite(uploadDir, userId, entry.getValue());
      if (rewritten != entry.getValue()) {
        resolved.put(entry.getKey(), rewritten);
        changed = true;
      }
    }
    return changed ? resolved : payload;
  }

  /** One payload value: an id, a list of them, or something this has no business touching. */
  private static Object rewrite(String uploadDir, String userId, Object value) {
    if (value instanceof String candidate) {
      return asAssetPath(uploadDir, userId, candidate).map(Object.class::cast).orElse(value);
    }
    if (value instanceof List<?> items) {
      List<Object> rewritten = new ArrayList<>(items.size());
      boolean changed = false;
      for (Object item : items) {
        Object one = rewrite(uploadDir, userId, item);
        changed |= one != item;
        rewritten.add(one);
      }
      return changed ? List.copyOf(rewritten) : value;
    }
    return value;
  }

  /** An id shaped like an asset that names a file this actor owns. */
  private static Optional<String> asAssetPath(String uploadDir, String userId, String candidate) {
    if (!ASSET_ID.matcher(candidate).matches()) {
      return Optional.empty();
    }
    return resolve(uploadDir, userId, candidate).map(File::getAbsolutePath);
  }

  private static Optional<File> findByPrefix(File directory, String prefix, File ownerRoot) {
    if (!directory.exists()) {
      return Optional.empty();
    }
    File[] matches = directory.listFiles((dir, name) -> name.startsWith(prefix));
    if (matches == null || matches.length == 0) {
      return Optional.empty();
    }
    // A regular file, never a directory. Output is written to {uploadDir}/{userId}/{jobId}/, so
    // without this a job id resolves to its own output directory and travels on as if it were an
    // uploaded asset.
    return (matches[0].isFile() && withinOwnerRoot(matches[0], ownerRoot))
        ? Optional.of(matches[0])
        : Optional.empty();
  }

  /** The intention behind the prefix match, stated so a later refactor cannot lose it. */
  private static boolean withinOwnerRoot(File candidate, File ownerRoot) {
    try {
      Path resolved = candidate.getCanonicalFile().toPath();
      Path root = ownerRoot.getCanonicalFile().toPath();
      if (resolved.startsWith(root)) {
        return true;
      }
      logger.error(
          "Refused an asset outside its owner's directory: {} is not under {}", resolved, root);
      return false;
    } catch (IOException unreadable) {
      // A path that cannot be canonicalised cannot be shown to be contained. Refuse it.
      logger.error("Refused an asset whose path could not be resolved: {}", candidate, unreadable);
      return false;
    }
  }
}
