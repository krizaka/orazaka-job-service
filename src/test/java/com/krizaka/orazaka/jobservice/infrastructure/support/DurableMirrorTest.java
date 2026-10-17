package com.krizaka.orazaka.jobservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.assets.domain.port.AssetKeys;
import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import com.krizaka.orazaka.assets.infrastructure.storage.DirectoryAssetStore;
import com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp.JobListenerTestAssets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a run shows after a redeploy: its inputs and its result, and the files its user uploaded. A
 * "redeploy" is a fresh empty upload directory over the same durable store.
 */
class DurableMirrorTest {

  private static final MediaStorageProperties TENANT =
      new MediaStorageProperties("directory", null, null, "storage.bunnycdn.com", "x", "acme");
  private static final String ASSET = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

  @TempDir Path work;

  private final EncryptedAssetService assets = JobListenerTestAssets.assets();

  private DurableAssetStore durable() {
    return new DirectoryAssetStore(work.resolve("durable"));
  }

  private DurableMirror mirror() {
    return new DurableMirror(durable(), TENANT);
  }

  @Test
  void anArchivedInputAndResultComeBackOnAFreshDisk() throws IOException {
    Path before = work.resolve("before/u1/j1/input/input.json");
    Files.createDirectories(before.getParent());
    assets.write(before, "{\"prompt\":\"a pear\"}".getBytes());
    mirror().put("u1", "j1", before);
    Path result = work.resolve("before/u1/j1/output/result.json");
    Files.createDirectories(result.getParent());
    assets.write(result, "{\"ok\":true}".getBytes());
    mirror().put("u1", "j1", result);

    Path after = work.resolve("after/u1/j1/input/input.json");
    assertThat(mirror().restore("u1", "j1", after)).isTrue();
    assertThat(assets.readAllBytes(after, false)).isEqualTo("{\"prompt\":\"a pear\"}".getBytes());
    assertThat(mirror().restore("u1", "j1", work.resolve("after/u1/j1/output/result.json")))
        .isTrue();
    assertThat(mirror().restore("u2", "j1", work.resolve("after/u2/j1/input/input.json")))
        .as("another user's prefix is another namespace")
        .isFalse();
  }

  @Test
  void anUploadComesBackByItsAssetId_forItsOwnerOnly() throws IOException {
    Path src = work.resolve("src.png");
    assets.write(src, new byte[] {1, 2, 3, 4});
    durable().put(AssetKeys.uploadFile("acme", "u1", ASSET + ".png"), src);
    String freshDisk = work.resolve("fresh").toString();

    assertThat(AssetFileResolver.resolve(freshDisk, "u1", ASSET)).isEmpty();
    var restored = mirror().restoreUpload(freshDisk, "u1", ASSET);

    assertThat(restored).isPresent();
    assertThat(restored.get().getName()).isEqualTo(ASSET + ".png");
    assertThat(AssetFileResolver.resolve(freshDisk, "u1", ASSET)).isPresent();
    assertThat(mirror().restoreUpload(freshDisk, "u2", ASSET)).isEmpty();
  }

  @Test
  void aPayloadNamingAnUploadOrAListOfThemIsRestoredBeforeTheResolverRuns() throws IOException {
    Path src = work.resolve("src.jpg");
    assets.write(src, new byte[] {9, 9});
    durable().put(AssetKeys.uploadFile("acme", "u1", ASSET + ".jpg"), src);
    String freshDisk = work.resolve("fresh").toString();
    Map<String, Object> payload = Map.of("photos", List.of(ASSET), "prompt", "x");

    mirror().restoreUploadsNamedBy(freshDisk, "u1", payload);

    Map<String, Object> resolved = AssetFileResolver.resolvePayload(freshDisk, "u1", payload);
    assertThat((List<?>) resolved.get("photos")).singleElement().asString().endsWith(".jpg");
  }

  @Test
  void aWorkerStillGetsTheStartingImageOfARunAfterARedeploy() throws IOException {
    Path fresh = work.resolve("fresh").toAbsolutePath();
    Path photo = fresh.resolve("u1/temp/" + ASSET + ".png");
    Path sealedPhoto = work.resolve("sealed-photo");
    assets.write(sealedPhoto, new byte[] {7, 7, 7});
    durable().put(AssetKeys.uploadFile("acme", "u1", ASSET + ".png"), sealedPhoto);
    Path sealedInput = work.resolve("elsewhere/input.json");
    Files.createDirectories(sealedInput.getParent());
    assets.write(sealedInput, ("{\"imagePath\":\"" + photo + "\"}").getBytes());
    mirror().put("u1", "j-1", sealedInput);

    var service =
        new com.krizaka.orazaka.jobservice.application.service.WorkerInputService(
            assets, new tools.jackson.databind.ObjectMapper(), fresh.toString(), mirror());

    assertThat(service.startingImage("j-1", "u1")).contains(new byte[] {7, 7, 7});
    assertThat(service.uploadedImage("u1", ASSET)).contains(new byte[] {7, 7, 7});
    assertThat(service.startingImage("j-1", "u2")).isEmpty();
  }

  @Test
  void localOnlyRestoresNothing() {
    DurableMirror none = DurableMirror.localOnly();
    assertThat(none.restore("u1", "j1", work.resolve("x/input.json"))).isFalse();
    assertThat(none.restoreUpload(work.toString(), "u1", ASSET)).isEmpty();
  }
}
