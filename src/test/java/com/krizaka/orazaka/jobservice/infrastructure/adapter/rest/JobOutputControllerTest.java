package com.krizaka.orazaka.jobservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.jobservice.application.service.WorkerInputService;
import com.krizaka.orazaka.jobservice.application.service.WorkerOutputService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class JobOutputControllerTest {

  private final WorkerOutputService service = mock(WorkerOutputService.class);
  private final WorkerInputService input = mock(WorkerInputService.class);
  private final JobOutputController controller = new JobOutputController(service, input);

  @Test
  @DisplayName("A stored output answers 201 with the URL the asset route serves")
  void answersTheAssetUrl() {
    byte[] clip = {1, 2, 3};
    when(service.store("j-1", "video.mp4", clip, "u", "f", "STANDARD"))
        .thenReturn("/api/v1/assets/j-1/video.mp4");

    ResponseEntity<Map<String, String>> response =
        controller.store("j-1", "video.mp4", clip, "u", "f", "STANDARD");

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(response.getBody()).containsEntry("url", "/api/v1/assets/j-1/video.mp4");
  }

  @Test
  @DisplayName("A malformed hand-over is a 400 that says why")
  void malformedIsBadRequest() {
    ResponseEntity<Map<String, String>> response =
        controller.refused(new IllegalArgumentException("an output name is one word"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).containsEntry("error", "an output name is one word");
  }

  @Test
  @DisplayName("A job's starting image is handed over, and 404 when the actor's job names none")
  void handsOverTheStartingImage() {
    when(input.startingImage("j-1", "u")).thenReturn(Optional.of(new byte[] {9, 9}));
    when(input.startingImage("j-2", "u")).thenReturn(Optional.empty());

    assertThat(controller.image("j-1", "u").getBody()).containsExactly(9, 9);
    assertThat(controller.image("j-2", "u").getStatusCode().value()).isEqualTo(404);
  }
}
