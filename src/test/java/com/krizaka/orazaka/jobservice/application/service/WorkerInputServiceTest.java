package com.krizaka.orazaka.jobservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.assets.application.service.EncryptedAssetService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class WorkerInputServiceTest {

  private static final String JOB = "3f2c9a10-7d1e-4b8a-9c55-0a1b2c3d4e5f";
  private static final byte[] PHOTO = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};

  @TempDir Path uploads;

  private final EncryptedAssetService assets = mock(EncryptedAssetService.class);

  private WorkerInputService service() {
    return new WorkerInputService(assets, new ObjectMapper(), uploads.toString());
  }

  private Path archive(String user, String imageKeyValue) throws IOException {
    Path input = uploads.resolve(user).resolve(JOB).resolve("input");
    Files.createDirectories(input);
    Path json = input.resolve("input.json");
    Files.writeString(json, imageKeyValue);
    when(assets.readAllBytes(eq(json), eq(true))).thenReturn(Files.readAllBytes(json));
    return json;
  }

  private Path photo(String user, String name) throws IOException {
    Path dir = uploads.resolve(user);
    Files.createDirectories(dir);
    Path file = dir.resolve(name);
    Files.write(file, PHOTO);
    when(assets.readAllBytes(eq(file), eq(true))).thenReturn(PHOTO);
    return file;
  }

  @Test
  @DisplayName("The image the job's archived input names is served, in the clear, to its actor")
  void servesTheActorsImage() throws IOException {
    Path file = photo("user-1", "11111111-1111-1111-1111-111111111111.jpg");
    archive("user-1", "{\"prompt\":\"x\",\"imagePath\":\"" + file + "\"}");

    assertThat(service().startingImage(JOB, "user-1")).contains(PHOTO);
  }

  @Test
  @DisplayName("Another actor's file is not found, even when the archive names it")
  void aFileOfSomeoneElseIsNotFound() throws IOException {
    Path foreign = photo("user-2", "22222222-2222-2222-2222-222222222222.jpg");
    archive("user-1", "{\"imagePath\":\"" + foreign + "\"}");

    assertThat(service().startingImage(JOB, "user-1")).isEmpty();
  }

  @Test
  @DisplayName("A path that climbs out of the owner's directory is not found")
  void traversalIsNotFound() throws IOException {
    photo("user-2", "secret.jpg");
    archive("user-1", "{\"imagePath\":\"" + uploads + "/user-1/../user-2/secret.jpg\"}");

    assertThat(service().startingImage(JOB, "user-1")).isEmpty();
  }

  @Test
  @DisplayName("A job with no archived input, or no image in it, is not found")
  void nothingToServe() throws IOException {
    assertThat(service().startingImage(JOB, "user-1")).isEmpty();
    archive("user-1", "{\"prompt\":\"text only\"}");
    assertThat(service().startingImage(JOB, "user-1")).isEmpty();
  }

  @Test
  @DisplayName("A malformed job or actor id is refused, never turned into a path")
  void malformedIdsAreRefused() {
    assertThatThrownBy(() -> service().startingImage("../x", "user-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service().startingImage(JOB, "../user-2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service().startingImage(JOB, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
