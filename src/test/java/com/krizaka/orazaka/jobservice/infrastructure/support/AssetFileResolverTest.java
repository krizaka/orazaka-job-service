package com.krizaka.orazaka.jobservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What an actor's own asset ids resolve to, and what everything else must not.
 *
 * <p>The list cases exist because {@code realestate-reels} was diagnosed twice as an environment
 * problem when it was this: {@code assemble} passes {@code photos} — a <b>list</b> of asset ids —
 * and resolution only ever handled the scalar {@code assetId}, so the composer received ids where
 * it expected paths and failed with "compose requires at least one readable photo" (ADR-046).
 */
class AssetFileResolverTest {

  private static final String OWNER = "550e8400-e29b-41d4-a716-446655440001";
  private static final String INTRUDER = "550e8400-e29b-41d4-a716-446655440002";

  private static String seedAsset(Path uploadDir, String owner) throws IOException {
    String assetId = UUID.randomUUID().toString();
    Path temp = uploadDir.resolve(owner).resolve("temp");
    Files.createDirectories(temp);
    Files.writeString(temp.resolve(assetId + ".png"), "not really a png");
    return assetId;
  }

  @Test
  @DisplayName("a scalar assetId resolves to the owner's file")
  void scalarAssetResolves(@TempDir Path uploadDir) throws IOException {
    String assetId = seedAsset(uploadDir, OWNER);

    Optional<File> resolved = AssetFileResolver.resolve(uploadDir.toString(), OWNER, assetId);

    assertThat(resolved).isPresent();
    assertThat(resolved.get().getName()).startsWith(assetId);
  }

  @Test
  @DisplayName("another actor's asset is not found — the same answer as one that never existed")
  void anotherActorsAssetIsNotFound(@TempDir Path uploadDir) throws IOException {
    String assetId = seedAsset(uploadDir, OWNER);

    assertThat(AssetFileResolver.resolve(uploadDir.toString(), INTRUDER, assetId)).isEmpty();
  }

  @Test
  @DisplayName("a list of asset ids becomes a list of absolute paths [the reels defect]")
  void listValuedAssetsResolve(@TempDir Path uploadDir) throws IOException {
    String first = seedAsset(uploadDir, OWNER);
    String second = seedAsset(uploadDir, OWNER);

    Map<String, Object> resolved =
        AssetFileResolver.resolvePayload(
            uploadDir.toString(), OWNER, Map.of("photos", List.of(first, second)));

    assertThat(resolved.get("photos")).isInstanceOf(List.class);
    List<?> photos = (List<?>) resolved.get("photos");
    assertThat(photos).hasSize(2);
    assertThat(photos)
        .allSatisfy(path -> assertThat(new File(String.valueOf(path))).isAbsolute().exists());
  }

  @Test
  @DisplayName("a scalar asset id anywhere in the payload resolves too")
  void scalarPayloadValueResolves(@TempDir Path uploadDir) throws IOException {
    String clip = seedAsset(uploadDir, OWNER);

    Map<String, Object> resolved =
        AssetFileResolver.resolvePayload(uploadDir.toString(), OWNER, Map.of("clip", clip));

    assertThat(new File(String.valueOf(resolved.get("clip")))).isAbsolute().exists();
  }

  @Test
  @DisplayName("a list holding another actor's id keeps that id — it is not their file")
  void listDoesNotCrossActors(@TempDir Path uploadDir) throws IOException {
    String mine = seedAsset(uploadDir, OWNER);
    String theirs = seedAsset(uploadDir, INTRUDER);

    Map<String, Object> resolved =
        AssetFileResolver.resolvePayload(
            uploadDir.toString(), OWNER, Map.of("photos", List.of(mine, theirs)));

    List<?> photos = (List<?>) resolved.get("photos");
    assertThat(photos.get(1)).isEqualTo(theirs);
    assertThat(new File(String.valueOf(photos.get(0)))).isAbsolute().exists();
  }

  @Test
  @DisplayName("prose and ids that are not assets are left exactly as they were")
  void nonAssetValuesAreUntouched(@TempDir Path uploadDir) throws IOException {
    seedAsset(uploadDir, OWNER);
    Map<String, Object> payload =
        Map.of(
            "prompt",
            "Décris cette pièce pour une annonce immobilière.",
            "correlationId",
            UUID.randomUUID().toString(),
            "ordinal",
            0,
            "tags",
            List.of("immobilier", "lyon"));

    assertThat(AssetFileResolver.resolvePayload(uploadDir.toString(), OWNER, payload))
        .isEqualTo(payload);
  }

  @Test
  @DisplayName("a directory whose name starts with the id is not an asset")
  void directoriesAreNotAssets(@TempDir Path uploadDir) throws IOException {
    String jobId = UUID.randomUUID().toString();
    Files.createDirectories(uploadDir.resolve(OWNER).resolve(jobId));

    assertThat(AssetFileResolver.resolve(uploadDir.toString(), OWNER, jobId)).isEmpty();
  }
}
