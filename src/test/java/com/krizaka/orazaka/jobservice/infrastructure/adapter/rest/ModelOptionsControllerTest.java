package com.krizaka.orazaka.jobservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.jobservice.catalog.ModelOption;
import com.krizaka.orazaka.jobservice.catalog.ModelOptionsService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelOptionsControllerTest {

  private final ModelOptionsService service = mock(ModelOptionsService.class);
  private final ModelOptionsController controller = new ModelOptionsController(service);

  @Test
  @DisplayName("Answers with the service's options for the category, contract fields intact")
  void answersWithTheOptions() {
    ModelOption option =
        new ModelOption(
            "stable-diffusion-3.5-large",
            "Stable Diffusion 3.5 Large",
            "do-inference-images",
            "cloud",
            "fast",
            8,
            8,
            "Hosted.",
            true,
            null,
            true);
    when(service.options("image")).thenReturn(List.of(option));

    assertThat(controller.options("image")).containsExactly(option);
  }

  @Test
  @DisplayName("A category with no published model answers an empty list, not an error")
  void emptyCategory() {
    when(service.options("nothing")).thenReturn(List.of());

    assertThat(controller.options("nothing")).isEmpty();
  }
}
