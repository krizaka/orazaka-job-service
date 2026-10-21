package com.krizaka.orazaka.jobservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.jobservice.application.service.JobRetentionService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class JobErasureControllerTest {

  private final JobRetentionService retention = mock(JobRetentionService.class);
  private final JobErasureController controller =
      new JobErasureController(retention, "var/test-uploads");

  @Test
  @DisplayName("an erasure that removed everything answers 200 with the counts")
  void answers200() {
    when(retention.erase(any(), eq("actor-1"), eq(List.of("a", "b"))))
        .thenReturn(new JobRetentionService.Erasure(2, 0));

    var response =
        controller.erase(new JobErasureController.ErasureRequest("actor-1", List.of("a", "b")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(new JobErasureController.ErasureResponse(2, 0));
  }

  @Test
  @DisplayName("an erasure that kept a row answers 503, so the caller does not claim it is done")
  void answers503WhenSomethingRemains() {
    when(retention.erase(any(), eq("actor-1"), any()))
        .thenReturn(new JobRetentionService.Erasure(1, 1));

    var response =
        controller.erase(new JobErasureController.ErasureRequest("actor-1", List.of("a", "b")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isEqualTo(new JobErasureController.ErasureResponse(1, 1));
  }
}
