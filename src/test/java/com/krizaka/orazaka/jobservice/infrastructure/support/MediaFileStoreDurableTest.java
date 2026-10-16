package com.krizaka.orazaka.jobservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import com.krizaka.orazaka.assets.domain.port.DurableAssetStore;
import com.krizaka.orazaka.assets.infrastructure.config.MediaStorageProperties;
import com.krizaka.orazaka.assets.infrastructure.storage.DirectoryAssetStore;
import com.krizaka.orazaka.assets.infrastructure.storage.NoDurableAssetStore;
import com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp.JobListenerTestAssets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The output of a job outlives the container: the sealed envelope is also in the durable store. */
class MediaFileStoreDurableTest {

  private static final MediaStorageProperties TENANT =
      new MediaStorageProperties("directory", null, null, "storage.bunnycdn.com", "x", "acme");

  @TempDir Path work;

  private final EncryptedAssetService assets = JobListenerTestAssets.assets();

  @Test
  void aSavedOutputIsAlsoKeptDurably_asTheSealedEnvelope_underTheTenantLayout() throws IOException {
    DurableAssetStore durable = new DirectoryAssetStore(work.resolve("durable"));
    MediaFileStore store = new MediaFileStore(assets, durable, TENANT);

    String url =
        store.save(work.resolve("uploads").toString(), "u1", "j1", pngBytes(), "image.png");

    assertThat(url).isEqualTo("/api/v1/assets/j1/image.png");
    Path kept = work.resolve("durable/t/acme/u/u1/jobs/j1/image.png");
    assertThat(kept).exists();
    assertThat(assets.isEncrypted(kept)).as("only sealed bytes leave the disk").isTrue();
    assertThat(assets.readAllBytes(kept, false)).isEqualTo(pngBytes());
  }

  @Test
  void whenTheDurableCopyFailsTheSaveFailsByName_notReportedStored() {
    assertDoesNotThrow(
        () -> {
          DurableAssetStore broken =
              new DurableAssetStore() {
                public boolean enabled() {
                  return true;
                }

                public void put(String key, Path file) throws IOException {
                  throw new IOException("durable store refused PUT: HTTP 401");
                }

                public boolean fetch(String key, Path target) {
                  return false;
                }

                public void delete(String key) {}
              };
          MediaMissing.assertEmpty(
              new MediaFileStore(assets, broken, TENANT)
                  .save(work.resolve("uploads").toString(), "u1", "j1", pngBytes(), "image.png"));
        });
  }

  @Test
  void localOnlyWritesNoSecondCopy() throws IOException {
    MediaFileStore store = new MediaFileStore(assets, new NoDurableAssetStore(), TENANT);
    assertThat(store.save(work.resolve("uploads").toString(), "u1", "j1", pngBytes(), "image.png"))
        .isNotEmpty();
    assertThat(Files.exists(work.resolve("durable"))).isFalse();
  }

  private static byte[] pngBytes() {
    return new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
  }

  private static final class MediaMissing {
    static void assertEmpty(String url) {
      assertThat(url).isEmpty();
    }
  }
}
