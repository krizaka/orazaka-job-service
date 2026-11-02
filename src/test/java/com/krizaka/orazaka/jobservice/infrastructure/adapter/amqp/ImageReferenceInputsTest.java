package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** [krizaka-internal#158] The reference pictures of an image job, read once at the boundary. */
class ImageReferenceInputsTest {

  private static final String OWL = "11111111-1111-4111-8111-111111111111";
  private static final String DUCK = "22222222-2222-4222-8222-222222222222";
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0};

  @TempDir Path uploads;

  @Test
  @DisplayName("one per line (a Studio list), a JSON array or commas all read the same, in order")
  void idsReadTheSameWhateverTheShape() {
    assertThat(ImageReferenceInputs.from(Map.of("references", OWL + "\n" + DUCK)).assetIds())
        .containsExactly(OWL, DUCK);
    assertThat(ImageReferenceInputs.from(Map.of("references", List.of(OWL, DUCK))).assetIds())
        .containsExactly(OWL, DUCK);
    assertThat(ImageReferenceInputs.from(Map.of("references", OWL + ", " + DUCK)).assetIds())
        .containsExactly(OWL, DUCK);
    assertThat(ImageReferenceInputs.from(Map.of("references", "")).assetIds()).isEmpty();
    assertThat(ImageReferenceInputs.from(Map.of()).weight()).isEqualTo(1.0);
    assertThat(
            ImageReferenceInputs.from(Map.of("references", OWL, "referenceWeight", "0.6")).weight())
        .isEqualTo(0.6);
  }

  @Test
  @DisplayName("a value that is not an asset id, a bad weight or too many references is refused")
  void malformedInputsAreRefused() {
    assertThatThrownBy(() -> ImageReferenceInputs.from(Map.of("references", "../etc/passwd")))
        .hasMessageContaining("must be an asset id");
    assertThatThrownBy(
            () -> ImageReferenceInputs.from(Map.of("references", OWL, "referenceWeight", "2")))
        .hasMessageContaining("between 0 and 1");
    assertThatThrownBy(
            () -> ImageReferenceInputs.from(Map.of("references", OWL, "referenceWeight", "high")))
        .hasMessageContaining("must be a number");
    assertThatThrownBy(
            () ->
                ImageReferenceInputs.from(
                    Map.of("references", String.join("\n", java.util.Collections.nCopies(9, OWL)))))
        .hasMessageContaining("at most 8");
  }

  @Test
  @DisplayName("a reference loads from the actor's own files; another actor's is not found")
  void referencesLoadOnlyFromTheActorsFiles() throws Exception {
    Path mine = Files.createDirectories(uploads.resolve("user-1"));
    Files.write(mine.resolve(OWL + ".png"), PNG);
    Files.createDirectories(uploads.resolve("user-2"));
    Files.write(uploads.resolve("user-2").resolve(DUCK + ".png"), PNG);

    var loaded =
        ImageReferenceInputs.from(Map.of("references", OWL))
            .load(
                uploads.toString(),
                "user-1",
                Files::readAllBytes,
                id -> java.util.Optional.empty());
    assertThat(loaded).hasSize(1);
    assertThat(loaded.getFirst().mediaType()).isEqualTo("image/png");

    assertThatThrownBy(
            () ->
                ImageReferenceInputs.from(Map.of("references", DUCK))
                    .load(
                        uploads.toString(),
                        "user-1",
                        Files::readAllBytes,
                        id -> java.util.Optional.empty()))
        .hasMessageContaining("is not among your files");
  }

  @Test
  @DisplayName("the type is read from the bytes, never from the name")
  void theTypeComesFromTheBytes() throws Exception {
    Path mine = Files.createDirectories(uploads.resolve("user-1"));
    Files.write(mine.resolve(OWL + ".png"), "not a picture".getBytes());

    assertThatThrownBy(
            () ->
                ImageReferenceInputs.from(Map.of("references", OWL))
                    .load(
                        uploads.toString(),
                        "user-1",
                        Files::readAllBytes,
                        id -> java.util.Optional.empty()))
        .hasMessageContaining("PNG, JPEG or WebP");
    assertThat(ImageReferenceInputs.mediaType(new byte[] {(byte) 0xFF, (byte) 0xD8, 0}))
        .isEqualTo("image/jpeg");
  }
}
